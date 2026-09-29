package com.example.demo.infrastructure.web;

import com.example.demo.application.dto.UploadResponse;
import com.example.demo.domain.common.DomainException;
import com.example.demo.infrastructure.storage.ImageStorage;
import com.example.demo.infrastructure.storage.ImageType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

/**
 * POST /api/v1/uploads/images: upload ảnh, trả URL để gắn vào hồ sơ/sự kiện. Cần đăng nhập.
 * Lưu qua ImageStorage: Cloudinary nếu có CLOUDINARY_URL, không thì thư mục uploads/ (xem StorageConfig).
 */
@RestController
@RequestMapping("/api/v1/uploads")
@Tag(name = "Uploads", description = "Upload ảnh (avatar, ảnh bìa sự kiện, logo BTC)")
public class UploadController {

    static final long MAX_BYTES = 5L * 1024 * 1024;   // khớp spring.servlet.multipart.max-file-size
    private static final Set<String> FOLDERS = Set.of("avatars", "events", "organizers", "misc");

    private final ImageStorage storage;

    public UploadController(ImageStorage storage) {
        this.storage = storage;
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload ảnh", description = "jpeg/png/webp/gif (nhận diện theo nội dung file), tối đa 5MB. "
            + "folder = avatars | events | organizers, bỏ trống = misc")
    public UploadResponse uploadImage(@RequestPart("file") MultipartFile file,
                                      @RequestParam(defaultValue = "misc") String folder) throws IOException {
        if (!FOLDERS.contains(folder)) {
            throw DomainException.badRequest("VALIDATION", "Dữ liệu gửi lên không hợp lệ")
                    .withError("folder", "Chỉ nhận avatars, events hoặc organizers");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new DomainException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "File vượt quá dung lượng cho phép (tối đa 5MB)");
        }
        byte[] data = file.getBytes();
        ImageType type = ImageType.detect(data).orElseThrow(() ->
                DomainException.badRequest("UNSUPPORTED_FILE_TYPE", "Chỉ nhận ảnh JPEG, PNG, WebP hoặc GIF"));
        return new UploadResponse(storage.store(data, folder, type), type.contentType, data.length);
    }
}
