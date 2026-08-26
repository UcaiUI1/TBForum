package cn.tbforum.service;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 本地磁盘存储实现：文件写入 uploads/ 目录，返回 /uploads/filename 路径。
 * 通过 app.storage.type=local 激活（默认），未来可扩展 app.storage.type=minio 切换。
 */
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {
    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final Pattern SAFE_FORMAT = Pattern.compile("[a-z0-9]{2,10}");
    private static final Pattern MANAGED_URL = Pattern.compile("/uploads/([A-Za-z0-9_-]{1,160}\\.[a-z0-9]{2,10})");
    private static final int MAX_CREATE_ATTEMPTS = 3;

    private final Path root;

    public LocalStorageService(@Value("${app.storage.local.dir:uploads}") String dir) {
        try {
            Path configuredRoot = Paths.get(dir).toAbsolutePath().normalize();
            Files.createDirectories(configuredRoot);
            this.root = configuredRoot.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("无法创建上传目录", e);
        }
    }

    @Override
    public String store(byte[] data, String format, String prefix) {
        if (data == null || data.length == 0) {
            throw UserService.bad("文件不能为空");
        }
        if (prefix == null
                || !SAFE_PREFIX.matcher(prefix).matches()
                || format == null
                || !SAFE_FORMAT.matcher(format).matches()) {
            throw UserService.bad("非法文件名");
        }

        for (int attempt = 0; attempt < MAX_CREATE_ATTEMPTS; attempt++) {
            String filename = prefix + UUID.randomUUID() + "." + format;
            Path target = root.resolve(filename).normalize();
            if (!target.getParent().equals(root)) {
                throw UserService.bad("非法存储路径");
            }
            try {
                Files.write(target, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return "/uploads/" + filename;
            } catch (FileAlreadyExistsException ignored) {
                // UUID 冲突时重新生成；始终使用 CREATE_NEW，禁止覆盖已有文件。
            } catch (IOException e) {
                throw UserService.bad("文件存储失败");
            }
        }
        throw UserService.bad("文件存储失败");
    }

    @Override
    public Optional<StoredFile> read(String url) {
        Optional<Path> resolved = resolve(url);
        if (resolved.isEmpty()) return Optional.empty();

        Path target = resolved.get();
        try {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            Path realTarget = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realTarget.getParent().equals(root)) return Optional.empty();
            return Optional.of(new StoredFile(Files.readAllBytes(realTarget)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String url) {
        Optional<Path> resolved = resolve(url);
        if (resolved.isEmpty()) return false;
        try {
            Files.deleteIfExists(resolved.get());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private Optional<Path> resolve(String url) {
        if (url == null) return Optional.empty();
        var matcher = MANAGED_URL.matcher(url);
        if (!matcher.matches()) return Optional.empty();

        Path target = root.resolve(matcher.group(1)).normalize();
        return target.getParent().equals(root) ? Optional.of(target) : Optional.empty();
    }
}
