package com.example.orders.adapter.rest;

import jakarta.servlet.Filter;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@Import(RequestIdFilterIntegrationTest.ProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequestIdFilterIntegrationTest {
    private static final String VALID = """
            {"currency":"USD","items":[{"price":"1.00","quantity":1}],"discountPercent":"0"}
            """;
    @LocalServerPort
    private int port;
    @Autowired
    private ServletContext servletContext;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private AtomicInteger requestIdLookups;

    @BeforeEach
    void resetProbe() {
        requestIdLookups.set(0);
    }

    @Test
    void registersExactlyOneInstanceOfEachFilter() {
        assertEquals(1, context.getBeansOfType(RequestIdFilter.class).size());
        assertEquals(1, context.getBeansOfType(CalculationBodyLimitFilter.class).size());
        for (Class<?> type : new Class<?>[]{RequestIdFilter.class, CalculationBodyLimitFilter.class}) {
            var registrations = servletContext.getFilterRegistrations().values().stream()
                    .filter(registration -> type.getName().equals(registration.getClassName())).toList();
            assertEquals(1, registrations.size(), type.getSimpleName());
            assertTrue(registrations.getFirst().getUrlPatternMappings().contains("/*"));
        }
        assertTrue(RequestIdFilter.class.getAnnotation(Order.class).value()
                < CalculationBodyLimitFilter.class.getAnnotation(Order.class).value());
    }

    @Test
    void successfulCalculationPreservesClientIdAndRunsIdSelectionOnce() throws Exception {
        HttpResponse<String> response = post(VALID, "client-request-123");
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        assertEquals(java.util.List.of("client-request-123"), response.headers().allValues(RequestIdFilter.HEADER_NAME));
        var body = mapper.readTree(response.body());
        assertEquals("USD", body.get("currency").asString());
        assertEquals("1.00", body.get("total").asString());
        assertEquals("1.00", body.get("payableAmount").asString());
        assertEquals(1, requestIdLookups.get());
    }

    @Test
    void successfulCalculationGeneratesOneUuidWhenHeaderIsMissing() throws Exception {
        HttpResponse<String> response = post(VALID, null);
        assertEquals(200, response.statusCode());
        assertEquals(1, response.headers().allValues(RequestIdFilter.HEADER_NAME).size());
        assertEquals(4, UUID.fromString(response.headers().firstValue(RequestIdFilter.HEADER_NAME).orElseThrow()).version());
        assertEquals(1, requestIdLookups.get());
    }

    @Test
    void correlationRunsBeforeEarlyBodyRejection() throws Exception {
        HttpResponse<String> response = post(" ".repeat(CalculationBodyLimitFilter.MAX_BODY_BYTES + 1), "oversized-request");
        assertEquals(400, response.statusCode());
        assertEquals(java.util.List.of("oversized-request"), response.headers().allValues(RequestIdFilter.HEADER_NAME));
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/problem+json"));
        var body = mapper.readTree(response.body());
        assertEquals(400, body.get("status").intValue());
        assertEquals("Request body must not exceed 32768 bytes", body.get("detail").asString());
        assertEquals("/api/orders/calculate", body.get("instance").asString());
        assertEquals(1, requestIdLookups.get());
    }

    private HttpResponse<String> post(String body, String id) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/orders/calculate"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (id != null) {
            request.header(RequestIdFilter.HEADER_NAME, id);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        AtomicInteger requestIdLookups() {
            return new AtomicInteger();
        }

        @Bean
        FilterRegistrationBean<Filter> requestIdProbe(AtomicInteger lookups) {
            Filter probe = (request, response, chain) -> chain.doFilter(
                    new HttpServletRequestWrapper((HttpServletRequest) request) {
                        @Override
                        public Enumeration<String> getHeaders(String name) {
                            if (RequestIdFilter.HEADER_NAME.equalsIgnoreCase(name)) {
                                lookups.incrementAndGet();
                            }
                            return super.getHeaders(name);
                        }
                    }, response);
            var registration = new FilterRegistrationBean<>(probe);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
            return registration;
        }
    }
}
