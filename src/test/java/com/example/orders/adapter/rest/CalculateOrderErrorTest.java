package com.example.orders.adapter.rest;

import com.example.orders.application.CalculateOrderUseCase;
import com.example.orders.domain.Order;
import com.example.orders.domain.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CalculateOrderErrorTest {
    private static final String PATH = "/api/orders/calculate";
    private static final String VALID = """
            {"currency":"USD","items":[{"price":"1.00","quantity":1}],"discountPercent":"0"}
            """;

    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(CalculationBodyLimitFilter.class)).build();
    }

    static Stream<String> invalidBodies() {
        Stream<String> structure = Stream.of("", "null", "{", "[]", "{}",
                VALID.replace("[{\"price\":\"1.00\",\"quantity\":1}]", "null"),
                VALID.replace("[{\"price\":\"1.00\",\"quantity\":1}]", "[]"),
                VALID.replace("[{\"price\":\"1.00\",\"quantity\":1}]", "[null]"),
                VALID.replace("[{\"price\":\"1.00\",\"quantity\":1}]", "{}"));
        Stream<String> missing = Stream.of("\"currency\":\"USD\",", "\"items\":[{\"price\":\"1.00\",\"quantity\":1}],",
                "\"price\":\"1.00\",", ",\"quantity\":1", ",\"discountPercent\":\"0\"")
                .map(field -> VALID.replace(field, ""));
        Stream<String> currency = Stream.of("null", "123", "true", "{}", "\"BAD\"", "\"XXX\"")
                .map(value -> VALID.replace("\"USD\"", value));
        Stream<String> prices = Stream.of("null", "1.00", "true", "{}", "[]", "\"\"", "\"abc\"", "\"-0.01\"")
                .map(value -> VALID.replace("\"1.00\"", value));
        Stream<String> quantities = Stream.of("null", "1.5", "1.0", "2147483648", "-2147483649",
                "\"1\"", "true", "{}", "[]", "0", "-1")
                .map(value -> VALID.replace("\"quantity\":1", "\"quantity\":" + value));
        Stream<String> discounts = Stream.of("null", "0", "true", "{}", "[]", "\"\"", "\"abc\"",
                "\"-0.01\"", "\"100.01\"")
                .map(value -> VALID.replace("\"discountPercent\":\"0\"", "\"discountPercent\":" + value));
        return Stream.of(structure, missing, currency, prices, quantities, discounts).flatMap(s -> s);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidInputReturnsBadRequestProblem(String body) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.instance").value(PATH));
    }

    @Test
    void preservesMethodNotAllowed() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void preservesNotAcceptable() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML).content(VALID))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.status").value(406));
    }

    @Test
    void preservesUnsupportedMediaType() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).content(VALID))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void unexpectedExceptionsAreInternalErrors() throws Exception {
        for (RuntimeException failure : new RuntimeException[]{
                new NullPointerException("internal secret"), new IllegalArgumentException("internal secret")}) {
            OrderService broken = new OrderService() {
                @Override
                public BigDecimal calculateTotal(Order order) {
                    throw failure;
                }
            };
            MockMvc isolated = MockMvcBuilders.standaloneSetup(
                            new CalculateOrderController(new CalculateOrderUseCase(broken),
                                    new OrderRestMapper(new OrderService())))
                    .setControllerAdvice(new RestExceptionHandler()).build();
            isolated.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
        }
    }

    @Test
    void unexpectedPayableFailureIsNotAnInvalidDiscount() throws Exception {
        OrderService broken = new OrderService() {
            @Override
            public BigDecimal calculatePayableAmount(Order order, BigDecimal discount) {
                throw new IllegalArgumentException("internal secret");
            }
        };
        MockMvc isolated = MockMvcBuilders.standaloneSetup(
                        new CalculateOrderController(new CalculateOrderUseCase(broken),
                                new OrderRestMapper(new OrderService())))
                .setControllerAdvice(new RestExceptionHandler()).build();
        isolated.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }
}
