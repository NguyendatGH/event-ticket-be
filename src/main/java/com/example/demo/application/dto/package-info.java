/**
 * Hình dạng JSON vào/ra của API (record). Tên field = tên JSON, đổi tên là đổi API.
 * {@code XxxRequest} body gửi lên (validation bằng annotation + @Valid ở controller);
 * {@code XxxResponse} dữ liệu trả về, thường có factory {@code from(entity, ...)}.
 */
package com.example.demo.application.dto;
