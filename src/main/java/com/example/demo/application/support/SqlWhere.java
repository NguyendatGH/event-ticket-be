package com.example.demo.application.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ghép mệnh đề WHERE động cho JdbcClient: các điều kiện nối bằng "and", giá trị đi kèm là tham số đặt tên.
 * <pre>
 * SqlWhere w = new SqlWhere().add("e.status = :status", "status", status);
 * if (q != null) w.add("e.name ilike :q", "q", Texts.likePattern(q));
 * jdbc.sql("select count(*) from events e" + w.sql()).params(w.params())...
 * </pre>
 * Quy tắc an toàn (chống SQL injection): chuỗi điều kiện chỉ là hằng viết trong code;
 * giá trị người dùng gửi lên LUÔN đi qua tham số (:name), không bao giờ nối vào chuỗi SQL.
 */
public final class SqlWhere {

    private final List<String> conditions = new ArrayList<>();
    private final Map<String, Object> params = new HashMap<>();

    /** Điều kiện không cần tham số, vd {@code "e.featured"}. */
    public SqlWhere add(String condition) {
        conditions.add(condition);
        return this;
    }

    /** Điều kiện có một tham số :name. */
    public SqlWhere add(String condition, String name, Object value) {
        param(name, value);
        return add(condition);
    }

    /** Thêm tham số không gắn với điều kiện nào (vd điều kiện dùng 2 tham số, hoặc tham số dùng trong ORDER BY). */
    public SqlWhere param(String name, Object value) {
        params.put(name, value);
        return this;
    }

    /** " where a and b and c", hoặc "" khi chưa có điều kiện nào. */
    public String sql() {
        return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    }

    public Map<String, Object> params() {
        return params;
    }
}
