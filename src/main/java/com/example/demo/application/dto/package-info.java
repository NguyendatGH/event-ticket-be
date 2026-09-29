/**
 * Hình dạng JSON vào/ra của API (Java record). Tên field = tên JSON trong ui-api-contract.md, đổi tên là đổi API.
 * {@code XxxRequest}: body gửi lên (kèm annotation validation như @NotBlank, kiểm tra nhờ @Valid ở controller).
 * {@code XxxResponse} và các record khác: dữ liệu trả về; thường có factory {@code from(entity, ...)} để dựng từ entity.
 */
package com.example.demo.application.dto;
