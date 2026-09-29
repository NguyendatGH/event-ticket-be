package com.example.demo.infrastructure.web;

import com.example.demo.infrastructure.seed.DevDataSeeder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Chỉ profile dev. Tiện tay reset dữ liệu mẫu sau khi test checkout. */
@RestController
@RequestMapping("/dev")
@Profile("dev")
@Tag(name = "Dev", description = "Công cụ dev, chỉ bật ở profile dev")
@SecurityRequirements
public class DevController {

    private final DevDataSeeder seeder;

    public DevController(DevDataSeeder seeder) {
        this.seeder = seeder;
    }

    @PostMapping("/seed")
    @Operation(summary = "Xóa event/tier/inventory/order và seed lại", description = "Chạy lại db/seed-dev.sql (classpath seed/seed-dev.sql), giữ users và organizers")
    public Map<String, Object> seed() {
        return seeder.reseed();
    }
}
