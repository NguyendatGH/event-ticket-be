package com.example.demo.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackConfigTest {

    @Test
    void consolePatternMasksMessageAndKeepsTraceId() throws Exception {
        LoggerContext ctx = new LoggerContext();
        ctx.putProperty("LOG_CORRELATION_PATTERN", "[trace_id=%X{trace_id:-}] ");
        ctx.putProperty("APPLICATION_NAME", "[ticketing] ");
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(ctx);
        configurator.doConfigure(getClass().getResource("/logback-spring.xml"));

        assertThat(ctx.getStatusManager().getCopyOfStatusList())
                .noneMatch(s -> s.getLevel() == Status.ERROR);

        ConsoleAppender<?> console = (ConsoleAppender<?>) ctx.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
        PatternLayoutEncoder encoder = (PatternLayoutEncoder) console.getEncoder();

        LoggingEvent event = new LoggingEvent("x", ctx.getLogger("test"), Level.INFO, "password=secret1 acc 1234567890", null, null);
        event.setMDCPropertyMap(Map.of("trace_id", "t-42"));
        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);

        assertThat(line).contains("[trace_id=t-42]").contains("password=*** acc ******7890").doesNotContain("secret1");
    }
}
