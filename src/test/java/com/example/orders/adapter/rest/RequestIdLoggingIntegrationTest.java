package com.example.orders.adapter.rest;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.test.annotation.DirtiesContext;
import com.example.orders.domain.Order;
import com.example.orders.domain.OrderService;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.tomcat.threads.max=1", "server.tomcat.threads.min-spare=1"})
@Import(RequestIdLoggingIntegrationTest.ErrorConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequestIdLoggingIntegrationTest {
    private static final String VALID = """
            {"currency":"USD","items":[{"price":"123.45","quantity":1}],"discountPercent":"0"}
            """;
    private static final String PRIVATE_MARKER = "request-body-must-not-be-logged";
    private static final String ERROR_PRIVATE_MARKER = "private-http-header-marker-7c89";
    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private CleanupProbe probe;
    private Logger root;
    private ListAppender<ILoggingEvent> capture;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        capture = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                // Freeze thread-local MDC on the emitting HTTP thread, before filter cleanup.
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        capture.list = new CopyOnWriteArrayList<>();
        capture.setContext(root.getLoggerContext());
        capture.start();
        root.addAppender(capture);
    }

    @AfterEach
    void stopCapture() {
        root.detachAppender(capture);
        capture.stop();
    }

    @Test
    void realHttpErrorLogCarriesResponseIdWithoutRequestData() throws Exception {
        HttpResponse<String> response = post(VALID, "logged-error-request");
        assertEquals(500, response.statusCode());
        String id = response.headers().firstValue(RequestIdFilter.HEADER_NAME).orElseThrow();
        assertEquals("logged-error-request", id);
        assertEquals(1, response.headers().allValues(RequestIdFilter.HEADER_NAME).size());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        assertEquals("An unexpected error occurred", mapper.readTree(response.body()).get("detail").asString());

        ILoggingEvent event = capture.list.stream()
                .filter(log -> log.getLoggerName().equals(RestExceptionHandler.class.getName()))
                .filter(log -> log.getFormattedMessage().equals("Unexpected order calculation failure"))
                .findFirst().orElseThrow();
        assertEquals(id, event.getMDCPropertyMap().get(RequestIdFilter.MDC_KEY));
        assertEquals("ERROR", event.getLevel().toString());
        assertNotNull(event.getThrowableProxy());
        assertEquals(IllegalStateException.class.getName(), event.getThrowableProxy().getClassName());
        assertEquals("Simulated calculation failure", event.getThrowableProxy().getMessage());
        assertTrue(event.getThrowableProxy().getStackTraceElementProxyArray().length > 0);
        assertNotNull(event.getThrowableProxy().getCause());
        assertEquals("Simulated safe cause", event.getThrowableProxy().getCause().getMessage());
        assertEquals(1, event.getThrowableProxy().getSuppressed().length);
        assertEquals("Simulated safe suppressed failure", event.getThrowableProxy().getSuppressed()[0].getMessage());
        assertNotNull(event.getThreadName());
        assertTrue(event.getTimeStamp() > 0);
        assertFalse(event.getFormattedMessage().contains("123.45"));
        assertClean(probe.await(id));
        assertEquals(ERROR_PRIVATE_MARKER, probe.await(id).sensitiveHeader());

        var console = (ConsoleAppender<?>) root.getAppender("CONSOLE");
        assertNotNull(console);
        var encoder = (PatternLayoutEncoder) console.getEncoder();
        assertTrue(encoder.getPattern().contains("%5p [requestId=%X{requestId:-}]"));
        assertTrue(encoder.getPattern().contains("%d{"));
        assertTrue(encoder.getPattern().contains("logger"));
        assertTrue(encoder.getPattern().contains("%m%n"));
        String rendered = new String(encoder.encode(event), encoder.getCharset());
        assertTrue(rendered.contains("[requestId=" + id + "]"));
        assertTrue(rendered.contains("Unexpected order calculation failure"));
        assertTrue(rendered.contains("Simulated calculation failure"));
        assertTrue(rendered.contains("Simulated safe cause"));
        assertTrue(rendered.contains("Simulated safe suppressed failure"));
        assertFalse(response.body().contains(ERROR_PRIVATE_MARKER));
        assertCapturedLogsExcludePrivateMarkers(encoder);
    }

    @Test
    void reusedHttpThreadHasNoMdcLeakAfterErrorsAndEarlyRejections() throws Exception {
        HttpResponse<String> first = post(VALID, "first-error");
        HttpResponse<String> second = post(VALID, null);
        HttpResponse<String> invalid = post("{\"private\":\"" + PRIVATE_MARKER + "\"}", "invalid-request");
        HttpResponse<String> oversized = post(" ".repeat(CalculationBodyLimitFilter.MAX_BODY_BYTES + 1), "large-request");
        assertEquals(500, first.statusCode());
        assertEquals(500, second.statusCode());
        assertEquals(400, invalid.statusCode());
        assertEquals(400, oversized.statusCode());
        String generated = second.headers().firstValue(RequestIdFilter.HEADER_NAME).orElseThrow();
        assertEquals(4, UUID.fromString(generated).version());
        assertNotEquals("first-error", generated);

        Long threadId = null;
        for (HttpResponse<String> response : java.util.List.of(first, second, invalid, oversized)) {
            Cleanup result = probe.await(response.headers().firstValue(RequestIdFilter.HEADER_NAME).orElseThrow());
            assertClean(result);
            if (threadId == null) {
                threadId = result.threadId();
            }
            assertEquals(threadId.longValue(), result.threadId());
        }
        var errors = capture.list.stream().filter(log -> log.getLoggerName().equals(RestExceptionHandler.class.getName()))
                .filter(log -> log.getLevel().toString().equals("ERROR")).toList();
        assertEquals(2, errors.size());
        assertEquals("first-error", errors.get(0).getMDCPropertyMap().get(RequestIdFilter.MDC_KEY));
        assertEquals(generated, errors.get(1).getMDCPropertyMap().get(RequestIdFilter.MDC_KEY));
        var console = (ConsoleAppender<?>) root.getAppender("CONSOLE");
        assertCapturedLogsExcludePrivateMarkers((PatternLayoutEncoder) console.getEncoder());
    }

    private void assertCapturedLogsExcludePrivateMarkers(PatternLayoutEncoder encoder) {
        for (ILoggingEvent event : capture.list) {
            for (String marker : new String[]{PRIVATE_MARKER, ERROR_PRIVATE_MARKER}) {
                assertFalse(event.getFormattedMessage().contains(marker), "Formatted message leaked request data");
                assertFalse(String.valueOf(event.getMessage()).contains(marker), "Message template leaked request data");
                assertFalse(java.util.Arrays.deepToString(event.getArgumentArray()).contains(marker),
                        "Log arguments leaked request data");
                if (event.getThrowableProxy() != null) {
                    // Includes messages, causes, suppressed exceptions and stack frames.
                    assertFalse(ThrowableProxyUtil.asString(event.getThrowableProxy()).contains(marker),
                            "Attached throwable leaked request data");
                }
                assertFalse(new String(encoder.encode(event), encoder.getCharset()).contains(marker),
                        "Rendered logging output leaked request data");
            }
        }
    }

    private void assertClean(Cleanup result) {
        assertNull(result.before());
        assertNull(result.after());
    }

    private HttpResponse<String> post(String body, String id) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/orders/calculate"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("X-Test-Sensitive", ERROR_PRIVATE_MARKER)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (id != null) {
            request.header(RequestIdFilter.HEADER_NAME, id);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    record Cleanup(String before, String after, long threadId, String sensitiveHeader) {
    }

    static class CleanupProbe {
        private final ConcurrentHashMap<String, CompletableFuture<Cleanup>> results = new ConcurrentHashMap<>();

        void record(String id, Cleanup result) {
            results.computeIfAbsent(id, key -> new CompletableFuture<>()).complete(result);
        }

        Cleanup await(String id) throws Exception {
            return results.computeIfAbsent(id, key -> new CompletableFuture<>()).get(5, TimeUnit.SECONDS);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ErrorConfiguration {
        @Bean
        @Primary
        OrderService failingOrderService() {
            return new OrderService() {
                @Override
                public BigDecimal calculateTotal(Order order) {
                    var failure = new IllegalStateException("Simulated calculation failure",
                            new IllegalArgumentException("Simulated safe cause"));
                    failure.addSuppressed(new IllegalStateException("Simulated safe suppressed failure"));
                    throw failure;
                }
            };
        }

        @Bean
        CleanupProbe cleanupProbe() {
            return new CleanupProbe();
        }

        @Bean
        FilterRegistrationBean<Filter> cleanupProbeFilter(CleanupProbe probe) {
            Filter filter = (request, response, chain) -> {
                String before = MDC.get(RequestIdFilter.MDC_KEY);
                try {
                    chain.doFilter(request, response);
                } finally {
                    String id = ((HttpServletResponse) response).getHeader(RequestIdFilter.HEADER_NAME);
                    if (id != null) {
                        probe.record(id, new Cleanup(before, MDC.get(RequestIdFilter.MDC_KEY), Thread.currentThread().threadId(),
                                ((HttpServletRequest) request).getHeader("X-Test-Sensitive")));
                    }
                }
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
            return registration;
        }
    }
}
