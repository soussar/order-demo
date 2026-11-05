package com.example.orders.adapter.rest;

import com.example.orders.application.CalculateOrderUseCase;
import com.example.orders.domain.Order;
import com.example.orders.domain.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import jakarta.servlet.Filter;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class HttpErrorContractTest {
    private static final String PATH = "/api/orders/calculate";
    private static final String EXPECTED_ID_ATTRIBUTE = HttpErrorContractTest.class.getName() + ".expectedRequestId";
    private static final String VALID = """
            {"currency":"USD","items":[{"price":"1.00","quantity":1}],"discountPercent":"0"}
            """;
    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;
    private MockMvc failing;
    private Filter[] filters;

    @BeforeEach
    void setUp() {
        List<Filter> ordered = new ArrayList<>(List.of(context.getBean(RequestIdFilter.class),
                context.getBean(CalculationBodyLimitFilter.class)));
        AnnotationAwareOrderComparator.sort(ordered);
        assertInstanceOf(RequestIdFilter.class, ordered.getFirst());
        filters = ordered.toArray(Filter[]::new);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(filters).alwaysExpect(this::assertSingleRequestId).build();
        OrderService broken = new OrderService() {
            @Override
            public BigDecimal calculateTotal(Order order) {
                throw new IllegalStateException("private-internal-marker");
            }
        };
        failing = MockMvcBuilders.standaloneSetup(new CalculateOrderController(
                        new CalculateOrderUseCase(broken), new OrderRestMapper(new OrderService())))
                .setControllerAdvice(new RestExceptionHandler()).addFilters(filters)
                .alwaysExpect(this::assertSingleRequestId).build();
    }

    static Stream<Arguments> requestHeaders() {
        return Stream.of("*/*", "application/json", "application/problem+json", "text/plain")
                .flatMap(accept -> Stream.of(
                        Arguments.of(accept, new String[0], null),
                        Arguments.of(accept, new String[]{"caller-123"}, "caller-123"),
                        Arguments.of(accept, new String[]{"Trace_02.alpha-XYZ"}, "Trace_02.alpha-XYZ"),
                        Arguments.of(accept, new String[]{"invalid id"}, null),
                        Arguments.of(accept, new String[]{"duplicate", "duplicate"}, null),
                        Arguments.of(accept, new String[]{"first,second"}, null)));
    }

    @ParameterizedTest
    @MethodSource("requestHeaders")
    void successAndValidationRespectEndpointProduces(String accept, String[] ids, String expectedId) throws Exception {
        boolean supportsSuccess = accept.equals("*/*") || accept.equals("application/json");
        ResultActions success = mvc.perform(withIds(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .accept(accept).content(VALID), ids, expectedId));
        if (supportsSuccess) {
            success.andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        } else {
            assertProblem(success, 406, "Not Acceptable");
        }
        for (String body : new String[]{"{", VALID.replace("\"1.00\"", "\"1E+1000000000\""),
                VALID.replace("\"quantity\":1", "\"quantity\":10001")}) {
            assertProblem(mvc.perform(withIds(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .accept(accept).content(body), ids, expectedId)),
                    supportsSuccess ? 400 : 406, supportsSuccess ? "Bad Request" : "Not Acceptable");
        }
    }

    @ParameterizedTest
    @MethodSource("requestHeaders")
    void frameworkErrorsKeepStatusHeadersAndProblemBody(String accept, String[] ids, String expectedId) throws Exception {
        assertProblem(mvc.perform(withIds(get(PATH).accept(accept), ids, expectedId)), 405, "Method Not Allowed")
                .andExpect(header().string("Allow", "POST"));
        assertProblem(mvc.perform(withIds(post(PATH).accept(accept).contentType(MediaType.TEXT_PLAIN)
                .content(VALID), ids, expectedId)), 415, "Unsupported Media Type");
    }

    @ParameterizedTest
    @MethodSource("requestHeaders")
    void unexpectedFailureIsSanitizedWhenEndpointCanBeSelected(String accept, String[] ids, String expectedId) throws Exception {
        boolean supportsSuccess = accept.equals("*/*") || accept.equals("application/json");
        assertProblem(failing.perform(withIds(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .accept(accept).content(VALID), ids, expectedId)),
                supportsSuccess ? 500 : 406, supportsSuccess ? "Internal Server Error" : "Not Acceptable")
                .andExpect(content().string(not(containsString("private-internal-marker"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("com.example"))))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
        if (supportsSuccess) {
            failing.perform(withIds(post(PATH).contentType(MediaType.APPLICATION_JSON).accept(accept).content(VALID), ids, expectedId))
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
        }
    }

    @ParameterizedTest
    @MethodSource("requestHeaders")
    void oversizedBodyHasSafeProblemContractBeforeHandlerSelection(String accept, String[] ids, String expectedId) throws Exception {
        assertProblem(mvc.perform(withIds(post(PATH).contentType(MediaType.APPLICATION_JSON).accept(accept)
                        .content(" ".repeat(CalculationBodyLimitFilter.MAX_BODY_BYTES + 1)), ids, expectedId)), 400, "Bad Request")
                .andExpect(jsonPath("$.detail").value("Request body must not exceed 32768 bytes"));
    }

    @Test
    void indexedDecimalErrorsRemainPreciseForJsonClients() throws Exception {
        String prefix = "{\"currency\":\"USD\",\"items\":[{\"price\":\"1\",\"quantity\":1},";
        for (String decimal : new String[]{"0".repeat(65), "1E+999999999", "1000000000000",
                "0.0000001", "999999999999.9999999"}) {
            assertProblem(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON).content(prefix + "{\"price\":\"" + decimal
                                    + "\",\"quantity\":1}],\"discountPercent\":\"0\"}")), 400, "Bad Request")
                    .andExpect(jsonPath("$.detail").value(containsString("items[1].price")));
        }
        assertProblem(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON).content(prefix
                                + "{\"price\":1,\"quantity\":1}],\"discountPercent\":\"0\"}")), 400, "Bad Request")
                .andExpect(jsonPath("$.detail").value("items[1].price must have the required JSON type"));
    }

    @Test
    void missingAndIncorrectLengthCannotBypassBodyLimit() throws Exception {
        ObjectMapper mapper = context.getBean(ObjectMapper.class);
        for (long length : new long[]{-1, 0, 1, CalculationBodyLimitFilter.MAX_BODY_BYTES + 100L}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH) {
                @Override
                public long getContentLengthLong() {
                    return length;
                }
            };
            request.setContent(new byte[CalculationBodyLimitFilter.MAX_BODY_BYTES + 1]);
            request.addHeader("Accept", "application/json");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filters[0].doFilter(request, response, (req, res) -> filters[1].doFilter(req, res,
                    (ignoredRequest, ignoredResponse) -> fail("Rejected body must not reach MVC")));
            assertEquals(1, response.getHeaders(RequestIdFilter.HEADER_NAME).size());
            assertEquals(4, UUID.fromString(response.getHeader(RequestIdFilter.HEADER_NAME)).version());
            assertEquals(400, response.getStatus());
            assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, response.getContentType());
            var problem = mapper.readTree(response.getContentAsByteArray());
            // RFC 9457 defines an omitted type as about:blank; Spring may omit this default.
            if (problem.get("type") != null) {
                assertEquals("about:blank", problem.get("type").asString());
            }
            assertEquals("Bad Request", problem.get("title").asString());
            assertEquals(400, problem.get("status").intValue());
            assertEquals(PATH, problem.get("instance").asString());
            assertEquals("Request body must not exceed 32768 bytes", problem.get("detail").asString());
        }
    }

    // A null expected ID explicitly means UUID replacement/generation.
    private MockHttpServletRequestBuilder withIds(MockHttpServletRequestBuilder request, String[] ids, String expectedId) {
        for (String id : ids) {
            request.header(RequestIdFilter.HEADER_NAME, id);
        }
        if (expectedId != null) {
            request.requestAttr(EXPECTED_ID_ATTRIBUTE, expectedId);
        }
        return request;
    }

    private void assertSingleRequestId(org.springframework.test.web.servlet.MvcResult result) {
        var response = result.getResponse();
        assertEquals(1, response.getHeaders(RequestIdFilter.HEADER_NAME).size());
        String actual = response.getHeader(RequestIdFilter.HEADER_NAME);
        var supplied = Collections.list(result.getRequest().getHeaders(RequestIdFilter.HEADER_NAME));
        String expectedId = (String) result.getRequest().getAttribute(EXPECTED_ID_ATTRIBUTE);
        if (expectedId != null) {
            assertEquals(expectedId, actual);
        } else {
            assertNotNull(actual);
            assertEquals(actual, UUID.fromString(actual).toString());
            assertEquals(4, UUID.fromString(actual).version());
            assertFalse(supplied.contains(actual));
        }
        assertEquals(actual, result.getRequest().getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE));
    }

    private ResultActions assertProblem(ResultActions result, int status, String title) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(response -> {
                    var body = context.getBean(ObjectMapper.class)
                            .readTree(response.getResponse().getContentAsByteArray());
                    if (body.get("type") != null) {
                        assertEquals("about:blank", body.get("type").asString());
                    }
                })
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.instance").value(PATH));
    }
}
