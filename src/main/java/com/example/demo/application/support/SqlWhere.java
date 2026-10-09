package com.example.demo.application.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SqlWhere {

    private final List<String> conditions = new ArrayList<>();
    private final Map<String, Object> params = new HashMap<>();

    public SqlWhere add(String condition) {
        conditions.add(condition);
        return this;
    }

    public SqlWhere add(String condition, String name, Object value) {
        param(name, value);
        return add(condition);
    }

    public SqlWhere param(String name, Object value) {
        params.put(name, value);
        return this;
    }

    public String sql() {
        return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    }

    public Map<String, Object> params() {
        return params;
    }
}
