package com.example.orders.adapter.rest;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();
    private Map<String, String> originalContext;

    @BeforeEach
    void saveContext() {
        originalContext = MDC.getCopyOfContextMap();
        MDC.clear();
    }

    @AfterEach
    void restoreContext() {
        if (originalContext == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(originalContext);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "0", "A-client_01.abc-XYZ", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void preservesExactlyOneValidValue(String value) throws Exception {
        MockHttpServletRequest request = request(value);
        assertEquals(value, exercise(request));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " leading", "trailing ", "a b", "a\tb", "a\rb", "a\nb",
            "a,b", "a, b", "_abc", ".abc", "-abc", "a/b", "a:b", "\u00e9", "a\u0000b",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void replacesInvalidValuesWithUuid(String value) throws Exception {
        String generated = exercise(request(value));
        assertUuid(generated);
        assertNotEquals(value, generated);
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void generatesUuidWhenMissing() throws Exception {
        assertUuid(exercise(new MockHttpServletRequest()));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void replacesDuplicateValuesIncludingIdenticalAndMixedCaseHeaderNames() throws Exception {
        for (String second : new String[]{"first", "second"}) {
            MockHttpServletRequest request = request("first");
            request.addHeader("x-request-id", second);
            String generated = exercise(request);
            assertUuid(generated);
            assertNotEquals("first", generated);
            assertNotEquals(second, generated);
        }
    }

    @Test
    void restoresPreviousIdAndPreservesUnrelatedMdcEntries() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "outer-context");
        MDC.put("other", "untouched");
        assertEquals("client", exercise(request("client")));
        assertEquals("outer-context", MDC.get(RequestIdFilter.MDC_KEY));
        assertEquals("untouched", MDC.get("other"));
    }

    @Test
    void restoresContextAndPropagatesExceptionsUnchanged() throws Exception {
        for (Exception failure : new Exception[]{new ServletException("failure"), new IOException("failure"),
                new IllegalStateException("failure")}) {
            for (String previous : new String[]{null, "outer-context"}) {
                if (previous == null) {
                    MDC.remove(RequestIdFilter.MDC_KEY);
                } else {
                    MDC.put(RequestIdFilter.MDC_KEY, previous);
                }
                MockHttpServletRequest request = request("failing-request");
                MockHttpServletResponse response = new MockHttpServletResponse();
                Exception observed = assertThrows(failure.getClass(), () -> filter.doFilter(request, response,
                        (req, res) -> {
                            assertEquals("failing-request", MDC.get(RequestIdFilter.MDC_KEY));
                            assertEquals("failing-request", req.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE));
                            assertEquals("failing-request", response.getHeader(RequestIdFilter.HEADER_NAME));
                            if (failure instanceof ServletException servlet) {
                                throw servlet;
                            }
                            if (failure instanceof IOException io) {
                                throw io;
                            }
                            throw (RuntimeException) failure;
                        }));
                assertSame(failure, observed);
                assertEquals(previous, MDC.get(RequestIdFilter.MDC_KEY));
            }
        }
    }

    @Test
    void sequentialRequestsDoNotReuseIdsOrLeakContext() throws Exception {
        assertEquals("first", exercise(request("first")));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
        String second = exercise(new MockHttpServletRequest());
        assertUuid(second);
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
        String third = exercise(new MockHttpServletRequest());
        assertUuid(third);
        assertNotEquals(second, third);
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    private MockHttpServletRequest request(String id) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, id);
        return request;
    }

    private String exercise(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.addHeader(RequestIdFilter.HEADER_NAME, "old-value");
        filter.doFilter(request, response, (req, res) -> {
            String id = response.getHeader(RequestIdFilter.HEADER_NAME);
            assertNotNull(id);
            assertEquals(id, req.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE));
            assertEquals(id, MDC.get(RequestIdFilter.MDC_KEY));
            assertEquals(1, response.getHeaders(RequestIdFilter.HEADER_NAME).size());
        });
        return response.getHeader(RequestIdFilter.HEADER_NAME);
    }

    private void assertUuid(String id) {
        assertEquals(UUID.fromString(id).toString(), id);
        assertEquals(4, UUID.fromString(id).version());
    }
}
