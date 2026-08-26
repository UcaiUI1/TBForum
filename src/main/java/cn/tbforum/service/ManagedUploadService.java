package cn.tbforum.service;

import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.UploadedFile;
import cn.tbforum.domain.UploadedFile.Purpose;
import cn.tbforum.domain.UploadedFile.Status;
import cn.tbforum.domain.User;
import cn.tbforum.repo.UploadedFileRepository;
import cn.tbforum.repo.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ManagedUploadService {
    private static final Pattern MANAGED_URL = Pattern.compile("/uploads/[A-Za-z0-9_-]{1,160}\\.[a-z0-9]{2,10}");

    private final UploadedFileRepository files;
    private final UserRepository users;
    private final StorageService storage;
    private final AppProperties properties;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;

    public ManagedUploadService(
            UploadedFileRepository files,
            UserRepository users,
            StorageService storage,
            AppProperties properties,
            ApplicationEventPublisher events,
            @Autowired(required = false) MeterRegistry meterRegistry) {
        this.files = files;
        this.users = users;
        this.storage = storage;
        this.properties = properties;
        this.events = events;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public String store(User owner, Purpose purpose, byte[] data, String format) {
        if (owner == null || owner.id == null || purpose == null) throw UserService.bad("上传参数无效");
        if (data == null || data.length == 0) throw UserService.bad("文件不能为空");

        User lockedOwner = users.findByIdForUpdate(owner.id).orElseThrow(() -> UserService.unauthorized("登录已失效"));
        long total = files.totalBytesByOwner(lockedOwner);
        long quota = properties.getUpload().getMaxUserStorage();
        if (quota <= 0 || data.length > quota - Math.min(total, quota)) {
            increment("tbforum.uploads.quota_rejected", "purpose", purpose.name());
            throw UserService.bad("用户上传空间已满");
        }

        String prefix = (purpose == Purpose.AVATAR ? "avatar_" : "post_") + owner.id + "_";
        String url = storage.store(data, format, prefix);
        try {
            UploadedFile file = new UploadedFile();
            file.owner = lockedOwner;
            file.purpose = purpose;
            file.url = url;
            file.sizeBytes = data.length;
            file.expiresAt = Instant.now().plus(properties.getUpload().getPendingTtl());
            files.saveAndFlush(file);
            deleteStoredFileIfTransactionRollsBack(url);
            increment("tbforum.uploads.stored", "purpose", purpose.name());
            return url;
        } catch (RuntimeException e) {
            storage.delete(url);
            throw e;
        }
    }

    @Transactional
    public String claimAvatar(User owner, String requestedUrl, String previousUrl) {
        String normalized = requestedUrl == null || requestedUrl.isBlank() ? null : requestedUrl.trim();
        if (Objects.equals(normalized, previousUrl)) return normalized;

        if (normalized != null) claim(normalized, owner, Purpose.AVATAR, owner.id);
        releaseUrl(previousUrl, owner, Purpose.AVATAR);
        return normalized;
    }

    @Transactional
    public void syncPostImages(User owner, Long postId, String content) {
        Set<String> requested = extractManagedUrls(content);
        for (String url : requested) claim(url, owner, Purpose.POST_IMAGE, postId);

        for (UploadedFile existing : files.findReferencedForUpdate(Purpose.POST_IMAGE, postId, Status.REFERENCED)) {
            if (!requested.contains(existing.url)) release(existing);
        }
    }

    @Transactional
    public void releasePostImages(Long postId) {
        for (UploadedFile file : files.findReferencedForUpdate(Purpose.POST_IMAGE, postId, Status.REFERENCED)) {
            release(file);
        }
    }

    @Transactional(readOnly = true)
    public Optional<ReadableUpload> readReferenced(String url) {
        if (url == null || !MANAGED_URL.matcher(url).matches()) return Optional.empty();
        if (files.findByUrlAndStatus(url, Status.REFERENCED).isEmpty()) return Optional.empty();
        return storage.read(url).map(stored -> new ReadableUpload(stored.data(), extensionOf(url)));
    }

    @Transactional
    public boolean reconcileLegacyReference(User owner, Purpose purpose, Long referenceId, String url) {
        if (owner == null
                || owner.id == null
                || purpose == null
                || referenceId == null
                || url == null
                || !MANAGED_URL.matcher(url).matches()) {
            return false;
        }
        if (files.findByUrl(url).isPresent()) return false;

        Optional<StorageService.StoredFile> stored = storage.read(url);
        if (stored.isEmpty()) return false;

        UploadedFile file = new UploadedFile();
        file.owner = owner;
        file.purpose = purpose;
        file.status = Status.REFERENCED;
        file.url = url;
        file.sizeBytes = stored.get().sizeBytes();
        file.referenceId = referenceId;
        file.referencedAt = Instant.now();
        file.expiresAt = null;
        files.saveAndFlush(file);
        increment("tbforum.uploads.legacy_reconciled", "purpose", purpose.name());
        return true;
    }

    @Scheduled(fixedDelayString = "#{@appProperties.upload.cleanupInterval.toMillis()}")
    @Transactional
    public int cleanupExpired() {
        int batchSize = Math.max(1, properties.getUpload().getCleanupBatchSize());
        var expired = files.findExpiredForUpdate(Status.PENDING, Instant.now(), PageRequest.of(0, batchSize));
        int deleted = 0;
        for (UploadedFile file : expired) {
            if (storage.delete(file.url)) {
                files.delete(file);
                increment("tbforum.uploads.deleted", "reason", "expired");
                deleted++;
            } else {
                increment("tbforum.uploads.delete_failures", "reason", "expired");
            }
        }
        return deleted;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteReleased(UploadReleasedEvent event) {
        files.findByUrlForUpdate(event.url()).ifPresent(file -> {
            if (file.status != Status.PENDING || file.expiresAt == null || file.expiresAt.isAfter(Instant.now()))
                return;
            if (storage.delete(file.url)) {
                files.delete(file);
                increment("tbforum.uploads.deleted", "reason", "released");
            } else {
                increment("tbforum.uploads.delete_failures", "reason", "released");
            }
        });
    }

    private void claim(String url, User owner, Purpose purpose, Long referenceId) {
        if (url == null || !MANAGED_URL.matcher(url).matches()) throw UserService.bad("引用了无效上传文件");
        UploadedFile file = files.findByUrlForUpdate(url).orElseThrow(() -> UserService.bad("上传文件不存在或已过期"));
        if (!file.owner.id.equals(owner.id)) throw UserService.forbidden("不能引用其他用户的上传文件");
        if (file.purpose != purpose) throw UserService.bad("上传文件用途不匹配");
        if (file.status == Status.REFERENCED && !Objects.equals(file.referenceId, referenceId)) {
            throw UserService.bad("上传文件已被其他内容引用");
        }

        file.status = Status.REFERENCED;
        file.referenceId = referenceId;
        file.referencedAt = Instant.now();
        file.expiresAt = null;
        files.save(file);
    }

    private void releaseUrl(String url, User owner, Purpose purpose) {
        if (url == null || !MANAGED_URL.matcher(url).matches()) return;
        files.findByUrlForUpdate(url).ifPresent(file -> {
            if (file.owner.id.equals(owner.id) && file.purpose == purpose) release(file);
        });
    }

    private void release(UploadedFile file) {
        file.status = Status.PENDING;
        file.referenceId = null;
        file.referencedAt = null;
        file.expiresAt = Instant.now();
        files.save(file);
        events.publishEvent(new UploadReleasedEvent(file.url));
    }

    static Set<String> extractManagedUrls(String content) {
        Set<String> urls = new LinkedHashSet<>();
        if (content == null || content.isBlank()) return urls;
        Matcher matcher = MANAGED_URL.matcher(content);
        while (matcher.find()) urls.add(matcher.group());
        return urls;
    }

    private void deleteStoredFileIfTransactionRollsBack(String url) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) storage.delete(url);
            }
        });
    }

    private void increment(String name, String... tags) {
        if (meterRegistry == null) return;
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (RuntimeException ignored) {
        }
    }

    public record UploadReleasedEvent(String url) {}

    public record ReadableUpload(byte[] data, String extension) {
        public ReadableUpload {
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    private static String extensionOf(String url) {
        return url.substring(url.lastIndexOf('.') + 1);
    }
}
