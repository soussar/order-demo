package com.example.orders.adapter.rest;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CalculationTransportLimitsTest {
    private static final String PATH = "/api/orders/calculate";
    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(CalculationBodyLimitFilter.class)).build();
    }

    private static String request(String price, int quantity, int count, String discount) {
        String item = "{\"price\":\"" + price + "\",\"quantity\":" + quantity + "}";
        return "{\"currency\":\"USD\",\"items\":["
                + String.join(",", Collections.nCopies(count, item))
                + "],\"discountPercent\":\"" + discount + "\"}";
    }

    @Test
    void acceptsAllUpperBoundariesAndKeepsAggregateExact() throws Exception {
        String price = "0".repeat(45) + "999999999999.999999";
        assertEquals(64, price.length());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(request(price, 10000, 100, "0.000000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("999999999999999999.000000"))
                .andExpect(jsonPath("$.payableAmount").value("999999999999999999.00"));
    }

    @Test
    void acceptsSmallestFractionAndZeroWithLeadingZeros() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(request("0.000001", 1, 1, "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("0.000001"))
                .andExpect(jsonPath("$.payableAmount").value("0.00"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(request("0".repeat(64), 1, 1, "100.000000")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value("0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E+1000000000", "1e-2147483647", "0E+2147483647", "NaN", "Infinity",
            "1000000000000", "-1000000000000", "9999999999999.999999", "0.0000001", "1.0000000",
            "999999999999.9999999", " ", "+", ".", "1_000", "１２", "1,000"})
    void rejectsUnsafeDecimalTextAtIndexedPrice(String price) throws Exception {
        assertBadRequest(request(price, 1, 1, "0"), "items[0].price");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E+1000000000", "1e-2147483647", "1000000000000", "0.0000001", "NaN"})
    void appliesSameTransportLimitsToDiscount(String discount) throws Exception {
        assertBadRequest(request("1", 1, 1, discount), "discountPercent");
    }

    @Test
    void rejectsTooLongDecimalEvenWhenValueIsZero() throws Exception {
        assertBadRequest(request("0".repeat(65), 1, 1, "0"), "items[0].price");
        assertBadRequest(request("1", 1, 1, "0".repeat(65)), "discountPercent");
    }

    @Test
    void rejectsTooManyItemsAndQuantityOutsideTransportRange() throws Exception {
        assertBadRequest(request("0", 1, 101, "0"), "items");
        assertBadRequest(request("1", 10001, 1, "0"), "items[0].quantity");
        assertBadRequest(request("1", 0, 1, "0"), "items[0].quantity");
        assertBadRequest(request("-0.01", 1, 1, "0"), "items[0].price");
    }

    @Test
    void reportsSecondItemPathsForMissingAndWrongTypes() throws Exception {
        String prefix = "{\"currency\":\"USD\",\"items\":[{\"price\":\"1\",\"quantity\":1},";
        for (String item : new String[]{"{\"quantity\":1}", "{\"price\":2,\"quantity\":1}"}) {
            assertBadRequest(prefix + item + "],\"discountPercent\":\"0\"}", "items[1].price");
        }
        assertBadRequest(prefix + "{\"price\":\"1\",\"quantity\":1.5}],\"discountPercent\":\"0\"}",
                "items[1].quantity");
        assertBadRequest(prefix + "null],\"discountPercent\":\"0\"}", "items[1]");
    }

    @Test
    void enforcesBodyLimitAtExactByteBoundary() throws Exception {
        String valid = request("1", 1, 1, "0");
        String atLimit = valid + " ".repeat(CalculationBodyLimitFilter.MAX_BODY_BYTES - valid.length());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(atLimit))
                .andExpect(status().isOk());
        assertBadRequest(atLimit + " ", "32768 bytes");
        String multibyte = valid + " ".repeat(CalculationBodyLimitFilter.MAX_BODY_BYTES / 2) + "é".repeat(9000);
        assertBadRequest(multibyte, "32768 bytes");
    }

    @Test
    void boundsActualReadWhenLengthIsUnknownOrUnderreported() throws Exception {
        for (int reported : new int[]{-1, 1}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH) {
                @Override
                public long getContentLengthLong() {
                    return reported;
                }
            };
            request.setContent(new byte[CalculationBodyLimitFilter.MAX_BODY_BYTES + 1]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            context.getBean(CalculationBodyLimitFilter.class).doFilter(request, response,
                    (req, res) -> fail("Oversized body must not reach JSON parsing"));
            assertEquals(400, response.getStatus());
            assertTrue(response.getContentAsString().contains("32768 bytes"));
            assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, response.getContentType());
        }
    }

    @Test
    void readsAtMostLimitPlusOneByteWithoutTrustingPathOrDeclaredLength() throws Exception {
        AtomicInteger bytesRead = new AtomicInteger();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH + ";parameter=x") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override
                    public int read() {
                        bytesRead.incrementAndGet();
                        return ' ';
                    }

                    @Override
                    public boolean isFinished() {
                        return false;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        context.getBean(CalculationBodyLimitFilter.class).doFilter(request, response,
                (req, res) -> fail("Oversized stream must not reach parsing"));
        assertEquals(CalculationBodyLimitFilter.MAX_BODY_BYTES + 1, bytesRead.get());
        assertEquals(400, response.getStatus());
    }

    private void assertBadRequest(String body, String detailFragment) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(detailFragment)))
                .andExpect(jsonPath("$.instance").value(PATH));
    }
}
