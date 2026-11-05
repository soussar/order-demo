package com.example.orders.adapter.rest;

import com.example.orders.application.CalculateOrderUseCase;
import com.example.orders.domain.Order;
import com.example.orders.domain.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class HttpErrorContractTest {
    private static final String PATH = "/api/orders/calculate";
    private static final String VALID = """
            {"currency":"USD","items":[{"price":"1.00","quantity":1}],"discountPercent":"0"}
            """;
    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;
    private MockMvc failing;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(CalculationBodyLimitFilter.class)).build();
        OrderService broken = new OrderService() {
            @Override
            public BigDecimal calculateTotal(Order order) {
                throw new IllegalStateException("private-internal-marker");
            }
        };
        failing = MockMvcBuilders.standaloneSetup(new CalculateOrderController(
                        new CalculateOrderUseCase(broken), new OrderRestMapper(new OrderService())))
                .setControllerAdvice(new RestExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*/*", "application/json", "application/problem+json", "text/plain"})
    void successAndValidationRespectEndpointProduces(String accept) throws Exception {
        boolean supportsSuccess = accept.equals("*/*") || accept.equals("application/json");
        ResultActions success = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .accept(accept).content(VALID));
        if (supportsSuccess) {
            success.andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        } else {
            assertProblem(success, 406, "Not Acceptable");
        }
        for (String body : new String[]{"{", VALID.replace("\"1.00\"", "\"1E+1000000000\""),
                VALID.replace("\"quantity\":1", "\"quantity\":10001")}) {
            assertProblem(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .accept(accept).content(body)),
                    supportsSuccess ? 400 : 406, supportsSuccess ? "Bad Request" : "Not Acceptable");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"*/*", "application/json", "application/problem+json", "text/plain"})
    void frameworkErrorsKeepStatusHeadersAndProblemBody(String accept) throws Exception {
        assertProblem(mvc.perform(get(PATH).accept(accept)), 405, "Method Not Allowed")
                .andExpect(header().string("Allow", "POST"));
        assertProblem(mvc.perform(post(PATH).accept(accept).contentType(MediaType.TEXT_PLAIN)
                .content(VALID)), 415, "Unsupported Media Type");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*/*", "application/json", "application/problem+json", "text/plain"})
    void unexpectedFailureIsSanitizedWhenEndpointCanBeSelected(String accept) throws Exception {
        boolean supportsSuccess = accept.equals("*/*") || accept.equals("application/json");
        assertProblem(failing.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .accept(accept).content(VALID)),
                supportsSuccess ? 500 : 406, supportsSuccess ? "Internal Server Error" : "Not Acceptable")
                .andExpect(content().string(not(containsString("private-internal-marker"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("com.example"))))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
        if (supportsSuccess) {
            failing.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).accept(accept).content(VALID))
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"*/*", "application/json", "application/problem+json", "text/plain"})
    void oversizedBodyHasSafeProblemContractBeforeHandlerSelection(String accept) throws Exception {
        assertProblem(mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).accept(accept)
                        .content(" ".repeat(CalculationBodyLimitFilter.MAX_BODY_BYTES + 1))), 400, "Bad Request")
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
            context.getBean(CalculationBodyLimitFilter.class).doFilter(request, response,
                    (req, res) -> fail("Rejected body must not reach MVC"));
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
