package com.example.demo.infrastructure.web;

import com.example.demo.application.ContactService;
import com.example.demo.application.dto.ContactRequest;
import com.example.demo.application.dto.ContactResponse;
import com.example.demo.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/v1/contact: form trang Liên hệ, public. Gọi ContactService. */
@RestController
@Tag(name = "Contact", description = "Gửi liên hệ")
public class ContactController {

    private final ContactService contactService;

    public ContactController(ContactService contactService) {
        this.contactService = contactService;
    }

    @PostMapping("/api/v1/contact")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Gửi liên hệ", description = "Public; đã đăng nhập thì gắn user")
    public ContactResponse send(@Valid @RequestBody ContactRequest req) {
        return contactService.send(req, CurrentUser.id().orElse(null));
    }
}
