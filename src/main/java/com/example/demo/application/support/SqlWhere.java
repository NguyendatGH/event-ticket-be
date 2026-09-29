package com.example.demo.application.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ghép WHERE động cho JdbcClient: điều kiện nối bằng "and", giá trị đi kèm là tham số đặt tên.
 * Chống SQL injection: chuỗi điều kiện chỉ là hằng viết trong code, giá trị người dùng LUÔN qua tham số (:name).
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
