package com.example.demo.infrastructure.storage;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static com.example.demo.infrastructure.storage.StorageConfig.composeCloudinaryUrl;
import static org.assertj.core.api.Assertions.assertThat;

class StorageConfigTest {

    @Test
    void gheps3ORoiThanhChuoiCloudinary() {
        String url = composeCloudinaryUrl("demo-cloud", "123456789012345", "abcDEF-secret");

        assertThat(url).isEqualTo("cloudinary://123456789012345:abcDEF-secret@demo-cloud");
        // CloudinaryImageStorage parse lại đúng 3 phần này
        assertThat(URI.create(url).getHost()).isEqualTo("demo-cloud");
        assertThat(URI.create(url).getRawUserInfo().split(":", 2)).containsExactly("123456789012345", "abcDEF-secret");
    }

    @Test
    void thieuMotOThiCoiNhuChuaCauHinh() {
        assertThat(composeCloudinaryUrl("", "123", "secret")).as("thiếu cloud name").isEmpty();
        assertThat(composeCloudinaryUrl("demo-cloud", "", "secret")).as("thiếu api key").isEmpty();
        assertThat(composeCloudinaryUrl("demo-cloud", "123", "")).as("thiếu api secret").isEmpty();
    }

    @Test
    void catKhoangTrangThuaKhiCopyPaste() {
        assertThat(composeCloudinaryUrl(" demo-cloud ", " 123 ", " secret "))
                .isEqualTo("cloudinary://123:secret@demo-cloud");
    }
}
