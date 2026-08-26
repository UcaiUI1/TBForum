package cn.tbforum.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.UploadedFile;
import cn.tbforum.domain.UploadedFile.Purpose;
import cn.tbforum.domain.UploadedFile.Status;
import cn.tbforum.domain.User;
import cn.tbforum.repo.UploadedFileRepository;
import cn.tbforum.repo.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ManagedUploadServiceTest {
    @Mock
    UploadedFileRepository files;

    @Mock
    UserRepository users;

    @Mock
    StorageService storage;

    @Mock
    ApplicationEventPublisher events;

    AppProperties properties;
    ManagedUploadService service;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        service = new ManagedUploadService(files, users, storage, properties, events, null);
    }

    @Test
    void storeLocksUserChecksQuotaAndRegistersPendingFile() {
        User alice = user(7L, "alice");
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(alice));
        when(files.totalBytesByOwner(alice)).thenReturn(100L);
        when(storage.store(any(byte[].class), eq("png"), eq("post_7_"))).thenReturn("/uploads/post_7_file.png");

        String url = service.store(alice, Purpose.POST_IMAGE, new byte[] {1, 2, 3}, "png");

        assertThat(url).isEqualTo("/uploads/post_7_file.png");
        ArgumentCaptor<UploadedFile> captor = ArgumentCaptor.forClass(UploadedFile.class);
        verify(files).saveAndFlush(captor.capture());
        assertThat(captor.getValue())
                .extracting(f -> f.owner, f -> f.purpose, f -> f.status, f -> f.sizeBytes)
                .containsExactly(alice, Purpose.POST_IMAGE, Status.PENDING, 3L);
        assertThat(captor.getValue().expiresAt).isAfter(Instant.now());
    }

    @Test
    void storeRejectsUploadThatWouldExceedPerUserQuota() {
        User alice = user(7L, "alice");
        properties.getUpload().setMaxUserStorage(10);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(alice));
        when(files.totalBytesByOwner(alice)).thenReturn(9L);

        assertThatThrownBy(() -> service.store(alice, Purpose.AVATAR, new byte[] {1, 2}, "png"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("上传空间已满");
        verify(storage, never()).store(any(), anyString(), anyString());
        verify(files, never()).saveAndFlush(any());
    }

    @Test
    void claimAvatarReferencesNewFileAndReleasesPreviousFile() {
        User alice = user(7L, "alice");
        UploadedFile next = file(alice, Purpose.AVATAR, Status.PENDING, "/uploads/avatar_7_new.png", null);
        UploadedFile previous = file(alice, Purpose.AVATAR, Status.REFERENCED, "/uploads/avatar_7_old.png", 7L);
        when(files.findByUrlForUpdate(next.url)).thenReturn(Optional.of(next));
        when(files.findByUrlForUpdate(previous.url)).thenReturn(Optional.of(previous));

        assertThat(service.claimAvatar(alice, next.url, previous.url)).isEqualTo(next.url);

        assertThat(next.status).isEqualTo(Status.REFERENCED);
        assertThat(next.referenceId).isEqualTo(7L);
        assertThat(next.expiresAt).isNull();
        assertThat(previous.status).isEqualTo(Status.PENDING);
        assertThat(previous.referenceId).isNull();
        assertThat(previous.expiresAt).isNotNull();
        verify(events).publishEvent(new ManagedUploadService.UploadReleasedEvent(previous.url));
    }

    @Test
    void claimRejectsFileOwnedByAnotherUser() {
        User alice = user(7L, "alice");
        User bob = user(8L, "bob");
        UploadedFile file = file(bob, Purpose.AVATAR, Status.PENDING, "/uploads/avatar_8_file.png", null);
        when(files.findByUrlForUpdate(file.url)).thenReturn(Optional.of(file));

        assertThatThrownBy(() -> service.claimAvatar(alice, file.url, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(file.status).isEqualTo(Status.PENDING);
    }

    @Test
    void syncPostImagesClaimsReferencedUrlsAndReleasesRemovedUrls() {
        User alice = user(7L, "alice");
        UploadedFile added = file(alice, Purpose.POST_IMAGE, Status.PENDING, "/uploads/post_7_added.png", null);
        UploadedFile kept = file(alice, Purpose.POST_IMAGE, Status.REFERENCED, "/uploads/post_7_kept.png", 12L);
        UploadedFile removed = file(alice, Purpose.POST_IMAGE, Status.REFERENCED, "/uploads/post_7_removed.png", 12L);
        when(files.findByUrlForUpdate(added.url)).thenReturn(Optional.of(added));
        when(files.findByUrlForUpdate(kept.url)).thenReturn(Optional.of(kept));
        when(files.findReferencedForUpdate(Purpose.POST_IMAGE, 12L, Status.REFERENCED))
                .thenReturn(List.of(kept, removed));

        service.syncPostImages(
                alice,
                12L,
                "![added](/uploads/post_7_added.png) ![kept](/uploads/post_7_kept.png) "
                        + "![duplicate](/uploads/post_7_added.png)");

        assertThat(added.status).isEqualTo(Status.REFERENCED);
        assertThat(added.referenceId).isEqualTo(12L);
        assertThat(kept.status).isEqualTo(Status.REFERENCED);
        assertThat(removed.status).isEqualTo(Status.PENDING);
        verify(events).publishEvent(new ManagedUploadService.UploadReleasedEvent(removed.url));
    }

    @Test
    void cleanupExpiredDeletesDatabaseRowOnlyAfterStorageDeletionSucceeds() {
        User alice = user(7L, "alice");
        UploadedFile deletable = file(alice, Purpose.POST_IMAGE, Status.PENDING, "/uploads/post_7_old.png", null);
        UploadedFile retry = file(alice, Purpose.POST_IMAGE, Status.PENDING, "/uploads/post_7_retry.png", null);
        when(files.findExpiredForUpdate(eq(Status.PENDING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(deletable, retry));
        when(storage.delete(deletable.url)).thenReturn(true);
        when(storage.delete(retry.url)).thenReturn(false);

        assertThat(service.cleanupExpired()).isEqualTo(1);

        verify(files).delete(deletable);
        verify(files, never()).delete(retry);
    }

    @Test
    void releasedFileDeletionRechecksPendingStateBeforeDeleting() {
        User alice = user(7L, "alice");
        UploadedFile released = file(alice, Purpose.AVATAR, Status.PENDING, "/uploads/avatar_7_old.png", null);
        released.expiresAt = Instant.now().minusSeconds(1);
        when(files.findByUrlForUpdate(released.url)).thenReturn(Optional.of(released));
        when(storage.delete(released.url)).thenReturn(true);

        service.deleteReleased(new ManagedUploadService.UploadReleasedEvent(released.url));

        verify(files).delete(released);
    }

    @Test
    void managedUrlExtractionIgnoresTraversalAndDeduplicatesUrls() {
        assertThat(ManagedUploadService.extractManagedUrls(
                        "/uploads/post_7_ok.png /uploads/../secret.txt /uploads/post_7_ok.png"))
                .containsExactly("/uploads/post_7_ok.png");
    }

    @Test
    void readReturnsOnlyReferencedFilesThatStillExist() {
        String url = "/uploads/post_7_file.png";
        UploadedFile referenced = file(user(7L, "alice"), Purpose.POST_IMAGE, Status.REFERENCED, url, 12L);
        when(files.findByUrlAndStatus(url, Status.REFERENCED)).thenReturn(Optional.of(referenced));
        when(storage.read(url)).thenReturn(Optional.of(new StorageService.StoredFile(new byte[] {1, 2, 3})));

        assertThat(service.readReferenced(url)).get().satisfies(upload -> {
            assertThat(upload.extension()).isEqualTo("png");
            assertThat(upload.data()).containsExactly(1, 2, 3);
        });

        when(storage.read(url)).thenReturn(Optional.empty());
        assertThat(service.readReferenced(url)).isEmpty();
        assertThat(service.readReferenced("/uploads/../secret.png")).isEmpty();
    }

    @Test
    void readRejectsPendingFileBeforeTouchingStorage() {
        String url = "/uploads/post_7_pending.png";
        when(files.findByUrlAndStatus(url, Status.REFERENCED)).thenReturn(Optional.empty());

        assertThat(service.readReferenced(url)).isEmpty();

        verify(storage, never()).read(anyString());
    }

    @Test
    void reconcilesExistingLegacyReferenceOnce() {
        User alice = user(7L, "alice");
        String url = "/uploads/avatar_7_legacy.png";
        when(files.findByUrl(url)).thenReturn(Optional.empty());
        when(storage.read(url)).thenReturn(Optional.of(new StorageService.StoredFile(new byte[] {1, 2, 3})));

        assertThat(service.reconcileLegacyReference(alice, Purpose.AVATAR, 7L, url))
                .isTrue();

        ArgumentCaptor<UploadedFile> captor = ArgumentCaptor.forClass(UploadedFile.class);
        verify(files).saveAndFlush(captor.capture());
        assertThat(captor.getValue())
                .extracting(
                        f -> f.owner,
                        f -> f.purpose,
                        f -> f.status,
                        f -> f.referenceId,
                        f -> f.sizeBytes,
                        f -> f.expiresAt)
                .containsExactly(alice, Purpose.AVATAR, Status.REFERENCED, 7L, 3L, null);
    }

    @Test
    void reconciliationDoesNotReplaceExistingLifecycleRecord() {
        User alice = user(7L, "alice");
        String url = "/uploads/avatar_7_pending.png";
        when(files.findByUrl(url)).thenReturn(Optional.of(file(alice, Purpose.AVATAR, Status.PENDING, url, null)));

        assertThat(service.reconcileLegacyReference(alice, Purpose.AVATAR, 7L, url))
                .isFalse();

        verify(storage, never()).read(anyString());
        verify(files, never()).saveAndFlush(any());
    }

    private static User user(long id, String username) {
        User user = new User();
        user.id = id;
        user.username = username;
        return user;
    }

    private static UploadedFile file(User owner, Purpose purpose, Status status, String url, Long referenceId) {
        UploadedFile file = new UploadedFile();
        file.owner = owner;
        file.purpose = purpose;
        file.status = status;
        file.url = url;
        file.referenceId = referenceId;
        file.sizeBytes = 10;
        return file;
    }
}
