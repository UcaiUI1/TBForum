package cn.tbforum.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class LocalStorageServiceTest {
    @TempDir
    Path uploadDirectory;

    @Test
    void concurrentStyleWritesUseDistinctNamesAndNeverOverwrite() throws Exception {
        LocalStorageService storage = new LocalStorageService(uploadDirectory.toString());

        String first = storage.store(new byte[] {1}, "png", "avatar_1_");
        String second = storage.store(new byte[] {2}, "png", "avatar_1_");

        assertThat(first).isNotEqualTo(second).matches("/uploads/avatar_1_[0-9a-f-]{36}\\.png");
        assertThat(second).matches("/uploads/avatar_1_[0-9a-f-]{36}\\.png");
        assertThat(Files.readAllBytes(resolve(first))).containsExactly(1);
        assertThat(Files.readAllBytes(resolve(second))).containsExactly(2);
    }

    @Test
    void rejectsPrefixThatCouldEscapeStorageDirectory() {
        LocalStorageService storage = new LocalStorageService(uploadDirectory.toString());

        assertThatThrownBy(() -> storage.store(new byte[] {1}, "png", "../outside_"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("非法文件名");
        assertThat(uploadDirectory).isEmptyDirectory();
    }

    @Test
    void rejectsFormatContainingPathCharacters() {
        LocalStorageService storage = new LocalStorageService(uploadDirectory.toString());

        assertThatThrownBy(() -> storage.store(new byte[] {1}, "../png", "avatar_1_"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("非法文件名");
        assertThat(uploadDirectory).isEmptyDirectory();
    }

    @Test
    void deletesOnlyManagedFilesAndTreatsMissingFilesAsSuccess() throws Exception {
        LocalStorageService storage = new LocalStorageService(uploadDirectory.toString());
        String url = storage.store(new byte[] {1}, "png", "avatar_1_");

        assertThat(storage.delete(url)).isTrue();
        assertThat(Files.exists(resolve(url))).isFalse();
        assertThat(storage.delete(url)).isTrue();
        assertThat(storage.delete("/uploads/../outside.png")).isFalse();
        assertThat(storage.delete("https://example.com/outside.png")).isFalse();
    }

    @Test
    void readsOnlyExistingManagedFiles() {
        LocalStorageService storage = new LocalStorageService(uploadDirectory.toString());
        String url = storage.store(new byte[] {1, 2, 3}, "png", "avatar_1_");

        assertThat(storage.read(url))
                .get()
                .extracting(StorageService.StoredFile::data)
                .isEqualTo(new byte[] {1, 2, 3});
        assertThat(storage.read("/uploads/missing.png")).isEmpty();
        assertThat(storage.read("/uploads/../outside.png")).isEmpty();
        assertThat(storage.read("https://example.com/outside.png")).isEmpty();
    }

    private Path resolve(String url) {
        return uploadDirectory.resolve(url.substring("/uploads/".length()));
    }
}
