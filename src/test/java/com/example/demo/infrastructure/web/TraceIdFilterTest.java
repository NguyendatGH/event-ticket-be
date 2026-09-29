package com.example.demo.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    private String run(MockHttpServletRequest req, MockHttpServletResponse res) throws Exception {
        AtomicReference<String> seenInChain = new AtomicReference<>();
        filter.doFilter(req, res, (rq, rs) -> seenInChain.set(MDC.get(TraceIdFilter.MDC_KEY)));
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).as("MDC phải được dọn sau request").isNull();
        return seenInChain.get();
    }

    @Test
    void echoesClientRequestId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(TraceIdFilter.HEADER, "fe-abc.123");
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(run(req, res)).isEqualTo("fe-abc.123");
        assertThat(res.getHeader(TraceIdFilter.HEADER)).isEqualTo("fe-abc.123");
    }

    @Test
    void generatesUuidWhenMissingOrUnsafe() throws Exception {
        for (String bad : new String[]{null, "", "has space", "x".repeat(65), "<script>"}) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            if (bad != null) req.addHeader(TraceIdFilter.HEADER, bad);
            MockHttpServletResponse res = new MockHttpServletResponse();

            String id = run(req, res);
            assertThat(id).matches("[0-9a-f-]{36}");
            assertThat(res.getHeader(TraceIdFilter.HEADER)).isEqualTo(id);
        }
    }
}
