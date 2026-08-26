package cn.tbforum.controller;

import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.UploadedFile.Purpose;
import cn.tbforum.domain.User;
import cn.tbforum.service.ManagedUploadService;
import cn.tbforum.service.UserService;
import java.awt.image.BufferedImage;
import java.io.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Validated
@RequestMapping("/api")
public class UploadController {
    private final UserService userService;
    private final ManagedUploadService uploads;
    private final AppProperties properties;

    public UploadController(UserService userService, ManagedUploadService uploads, AppProperties properties) {
        this.userService = userService;
        this.uploads = uploads;
        this.properties = properties;
    }

    @PostMapping("/upload")
    public Map<String, String> upload(
            @RequestParam("file") MultipartFile file, @RequestParam("purpose") Purpose purpose) throws IOException {
        User user = userService.current();
        userService.requireNotRestricted(user);
        AppProperties.Upload limits = properties.getUpload();
        if (file.isEmpty()) {
            throw UserService.bad("文件不能为空");
        }
        if (file.getSize() > limits.getMaxFileSize()) {
            throw UserService.bad("文件大小超过限制");
        }

        ProcessedImage image = process(file.getBytes(), limits);
        String url = uploads.store(user, purpose, image.data(), image.format());
        return Map.of("url", url);
    }

    private static ProcessedImage process(byte[] data, AppProperties.Upload limits) {
        String detectedFormat = detectImageFormat(data);
        if (detectedFormat == null) {
            throw UserService.bad("仅支持 png/jpg/gif/webp 格式");
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (input == null) {
                throw UserService.bad("文件损坏或非有效图片");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw UserService.bad("文件损坏或不支持的图片编码");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height, limits);
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw UserService.bad("文件损坏或非有效图片");
                }
                return encode(decoded, detectedFormat, limits);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw UserService.bad("文件损坏或非有效图片");
        }
    }

    private static void validateDimensions(int width, int height, AppProperties.Upload limits) {
        long pixels = (long) width * height;
        if (width <= 0
                || height <= 0
                || width > limits.getMaxWidth()
                || height > limits.getMaxHeight()
                || pixels > limits.getMaxPixels()) {
            throw UserService.bad("图片尺寸超过限制");
        }
    }

    private static ProcessedImage encode(BufferedImage image, String detectedFormat, AppProperties.Upload limits) {
        // TwelveMonkeys 提供 WebP 解码器但不提供写入器，因此安全解码后转为 PNG。
        String outputFormat = "webp".equals(detectedFormat) ? "png" : detectedFormat;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, outputFormat, output)) {
                throw UserService.bad("图片重编码失败");
            }
            byte[] encoded = output.toByteArray();
            if (encoded.length > limits.getMaxFileSize()) {
                throw UserService.bad("图片重编码后超过大小限制");
            }
            return new ProcessedImage(encoded, outputFormat);
        } catch (IOException e) {
            throw UserService.bad("图片重编码失败");
        }
    }

    /** 通过文件头魔数判定允许的真实格式，不信任客户端文件名和 Content-Type。 */
    private static String detectImageFormat(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        if ((bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a) {
            return "png";
        }
        if ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (bytes[0] == 'G'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == '8'
                && (bytes[4] == '7' || bytes[4] == '9')
                && bytes[5] == 'a') {
            return "gif";
        }
        if (bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private record ProcessedImage(byte[] data, String format) {}
}
