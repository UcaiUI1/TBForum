package cn.tbforum.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.UploadedFile.Purpose;
import cn.tbforum.domain.User;
import cn.tbforum.service.ManagedUploadService;
import cn.tbforum.service.UserService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class UploadControllerTest {
    private static final byte[] ONE_PIXEL_WEBP =
            Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAUAmJaQAA3AA/v89WAAAAA==");

    @Mock
    private UserService userService;

    @Mock
    private ManagedUploadService uploads;

    private AppProperties properties;
    private UploadController controller;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        controller = new UploadController(userService, uploads, properties);
        User user = new User();
        user.id = 7L;
        when(userService.current()).thenReturn(user);
    }

    @Test
    void decodesAndReencodesPngInsteadOfTrustingContentType() throws Exception {
        byte[] png = image("png", 8, 6);
        MockMultipartFile file = new MockMultipartFile("file", "avatar.txt", "text/plain", png);
        when(uploads.store(any(User.class), any(Purpose.class), any(byte[].class), anyString()))
                .thenReturn("/uploads/safe.png");

        assertThat(controller.upload(file, Purpose.AVATAR)).containsEntry("url", "/uploads/safe.png");

        ArgumentCaptor<byte[]> data = ArgumentCaptor.forClass(byte[].class);
        verify(uploads)
                .store(
                        any(User.class),
                        org.mockito.ArgumentMatchers.eq(Purpose.AVATAR),
                        data.capture(),
                        org.mockito.ArgumentMatchers.eq("png"));
        assertThat(data.getValue()).startsWith(0x89, 0x50, 0x4e, 0x47);
    }

    @Test
    void rejectsImageWhoseDimensionsExceedConfiguredLimitBeforeStorage() throws Exception {
        properties.getUpload().setMaxWidth(4);
        MockMultipartFile file = new MockMultipartFile("file", "wide.png", "image/png", image("png", 5, 1));

        assertThatThrownBy(() -> controller.upload(file, Purpose.AVATAR))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("图片尺寸超过限制");
        verify(uploads, never()).store(any(), any(), any(), anyString());
    }

    @Test
    void rejectsImageWhosePixelCountExceedsConfiguredLimit() throws Exception {
        properties.getUpload().setMaxPixels(15);
        MockMultipartFile file = new MockMultipartFile("file", "pixels.png", "image/png", image("png", 4, 4));

        assertThatThrownBy(() -> controller.upload(file, Purpose.AVATAR))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("图片尺寸超过限制");
        verify(uploads, never()).store(any(), any(), any(), anyString());
    }

    @Test
    void rejectsMalformedImageEvenWhenMagicNumberLooksValid() {
        byte[] fakePng = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};
        MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/png", fakePng);

        assertThatThrownBy(() -> controller.upload(file, Purpose.AVATAR))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("文件损坏");
        verify(uploads, never()).store(any(), any(), any(), anyString());
    }

    @Test
    void fullyDecodesWebpAndStoresItAsReencodedPng() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "pixel.webp", "image/webp", ONE_PIXEL_WEBP);
        when(uploads.store(any(User.class), any(Purpose.class), any(byte[].class), anyString()))
                .thenReturn("/uploads/pixel.png");

        controller.upload(file, Purpose.POST_IMAGE);

        ArgumentCaptor<byte[]> data = ArgumentCaptor.forClass(byte[].class);
        verify(uploads)
                .store(
                        any(User.class),
                        org.mockito.ArgumentMatchers.eq(Purpose.POST_IMAGE),
                        data.capture(),
                        org.mockito.ArgumentMatchers.eq("png"));
        assertThat(data.getValue()).startsWith(0x89, 0x50, 0x4e, 0x47);
    }

    @Test
    void rejectsMalformedWebpInsteadOfSavingMagicNumberMatch() {
        byte[] fakeWebp = new byte[] {'R', 'I', 'F', 'F', 16, 0, 0, 0, 'W', 'E', 'B', 'P', 'B', 'A', 'D', '!'};
        MockMultipartFile file = new MockMultipartFile("file", "fake.webp", "image/webp", fakeWebp);

        assertThatThrownBy(() -> controller.upload(file, Purpose.POST_IMAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("文件损坏");
        verify(uploads, never()).store(any(), any(), any(), anyString());
    }

    @Test
    void rejectsReencodedOutputThatWouldAmplifyStoredFileBeyondLimit() {
        properties.getUpload().setMaxFileSize(ONE_PIXEL_WEBP.length);
        MockMultipartFile file = new MockMultipartFile("file", "pixel.webp", "image/webp", ONE_PIXEL_WEBP);

        assertThatThrownBy(() -> controller.upload(file, Purpose.POST_IMAGE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("重编码后超过大小限制");
        verify(uploads, never()).store(any(), any(), any(), anyString());
    }

    @Test
    void rejectsFileLargerThanConfiguredByteLimit() throws Exception {
        byte[] png = image("png", 2, 2);
        properties.getUpload().setMaxFileSize(png.length - 1L);
        MockMultipartFile file = new MockMultipartFile("file", "large.png", "image/png", png);

        assertThatThrownBy(() -> controller.upload(file, Purpose.AVATAR))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("文件大小超过限制");
        verify(uploads, never()).store(any(), any(), any(), anyString());
    }

    private static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, output)) {
                throw new IOException("No ImageIO writer for " + format);
            }
            return output.toByteArray();
        }
    }
}
