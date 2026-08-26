package cn.tbforum.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import cn.tbforum.service.ManagedUploadService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class UploadResourceControllerTest {
    @Mock
    ManagedUploadService uploads;

    @Test
    void servesReferencedImageWithControlledHeaders() {
        when(uploads.readReferenced("/uploads/avatar_1_file.png"))
                .thenReturn(Optional.of(new ManagedUploadService.ReadableUpload(new byte[] {1, 2, 3}, "png")));
        UploadResourceController controller = new UploadResourceController(uploads);

        var response = controller.read("avatar_1_file.png");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("public, max-age=31536000, immutable");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).containsExactly(1, 2, 3);
    }

    @Test
    void returnsNotFoundForUnreferencedOrUnsupportedFile() {
        UploadResourceController controller = new UploadResourceController(uploads);
        when(uploads.readReferenced("/uploads/missing.png")).thenReturn(Optional.empty());
        when(uploads.readReferenced("/uploads/file.exe"))
                .thenReturn(Optional.of(new ManagedUploadService.ReadableUpload(new byte[] {1}, "exe")));

        assertThatThrownBy(() -> controller.read("missing.png"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.read("file.exe"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
