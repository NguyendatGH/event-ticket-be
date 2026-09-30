package com.example.demo.infrastructure.mail;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc file HTML trong {@code resources/mail/} rồi thay các chỗ {@code {{tenBien}}} bằng giá trị thật.
 * <p>
 * TẠI SAO không dùng Thymeleaf: cả app chỉ có 3 mail, nội dung tĩnh, không vòng lặp hay điều kiện.
 * Thêm một template engine = thêm dependency, thêm cấu hình, thêm thứ phải học; còn {@code String.replace}
 * thì đọc là hiểu ngay. Khi nào mail nhiều và phức tạp (bảng vé, vòng lặp...) hãy đổi sang Thymeleaf.
 * <p>
 * Class này chỉ có hàm static vì nó không phụ thuộc cấu hình gì, không có state thay đổi theo request
 * (cache chỉ là bộ nhớ đệm nội dung file) → không cần làm bean Spring cho phức tạp.
 */
final class MailTemplates {

    /** {@code {{tenBien}}}: tên biến chỉ gồm chữ/số/gạch dưới nên regex \w+ là đủ. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    /**
     * Cache nội dung file theo tên template: file nằm trong jar, không bao giờ đổi lúc chạy,
     * nên đọc đĩa mỗi lần gửi là phí. ConcurrentHashMap vì nhiều thread có thể gửi mail cùng lúc.
     */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private MailTemplates() {
    }

    /**
     * Dựng HTML từ template {@code resources/mail/<name>.html}.
     *
     * @param name   tên file, không có đuôi (ví dụ {@code "password-reset"})
     * @param values map tên biến -> giá trị THÔ (hàm này tự escape HTML, nơi gọi đừng escape trước)
     * @throws UncheckedIOException nếu thiếu file template — Mailer đã bọc try/catch nên lỗi này không lan ra ngoài
     */
    static String render(String name, Map<String, String> values) {
        String template = CACHE.computeIfAbsent(name, MailTemplates::load);

        // Quét template MỘT lượt duy nhất thay vì replace lần lượt từng biến. Lý do: nếu khách đặt tên là
        // "{{amount}}" thì cách replace nhiều lượt sẽ đem tên đó đi thay ở lượt sau (dữ liệu biến thành placeholder).
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.getOrDefault(m.group(1), "");
            // quoteReplacement: trong appendReplacement thì "$" và "\" có nghĩa đặc biệt, phải vô hiệu hoá
            m.appendReplacement(out, Matcher.quoteReplacement(escapeHtml(value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Đổi ký tự đặc biệt của HTML thành entity.
     * <p>
     * BẮT BUỘC làm: tên khách, tên ban tổ chức là dữ liệu NGƯỜI DÙNG TỰ NHẬP. Nếu chèn thô vào HTML,
     * một cái tên như {@code <script>...</script>} hay {@code "><a href=...>} sẽ phá vỡ layout mail và
     * trở thành lỗ hổng injection (mail client nào chạy script/ảnh ngoài thì còn lộ thông tin).
     * Escape rồi thì trình đọc mail vẫn hiện đúng chữ, nhưng coi nó là VĂN BẢN chứ không phải thẻ HTML.
     */
    static String escapeHtml(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.replace("&", "&amp;")      // & phải đổi ĐẦU TIÊN, nếu không sẽ đổi luôn dấu & của các entity bên dưới
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")      // thoát khỏi attribute dạng "..."
                .replace("'", "&#39;");       // thoát khỏi attribute dạng '...'
    }

    /** Số tiền kiểu Việt Nam: {@code 1234000} -> {@code "1.234.000 ₫"} (VND không có phần thập phân). */
    static String formatVnd(long amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        // Tạo mới mỗi lần gọi vì DecimalFormat KHÔNG thread-safe; gửi mail không nhiều nên không cần tối ưu.
        return new DecimalFormat("#,##0", symbols).format(amount) + " ₫";
    }

    private static String load(String name) {
        ClassPathResource file = new ClassPathResource("mail/" + name + ".html");
        try (InputStream in = file.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);   // đọc UTF-8 để tiếng Việt không lỗi font
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được template mail: mail/" + name + ".html", e);
        }
    }
}
