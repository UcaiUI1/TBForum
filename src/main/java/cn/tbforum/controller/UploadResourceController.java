package cn.tbforum.controller;

import cn.tbforum.service.ManagedUploadService;
import cn.tbforum.service.UserService;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UploadResourceController {
    private static final Map<String, MediaType> MEDIA_TYPES = Map.of(
            "png", MediaType.IMAGE_PNG,
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "gif", MediaType.IMAGE_GIF,
            "webp", MediaType.parseMediaType("image/webp"));

    private final ManagedUploadService uploads;

    public UploadResourceController(ManagedUploadService uploads) {
        this.uploads = uploads;
    }

    @GetMapping("/uploads/{filename:.+}")
    ResponseEntity<byte[]> read(@PathVariable String filename) {
        String url = "/uploads/" + filename;
        var upload = uploads.readReferenced(url).orElseThrow(() -> UserService.notFound("文件不存在"));
        MediaType mediaType = MEDIA_TYPES.get(upload.extension());
        if (mediaType == null) throw UserService.notFound("文件不存在");

        byte[] data = upload.data();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(data.length)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .header("X-Content-Type-Options", "nosniff")
                .body(data);
    }
}
