package com.example.orders.adapter.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CalculateOrderControllerTest {
    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(CalculationBodyLimitFilter.class)).build();
    }

    @Test
    void calculatesExactAggregateThenDiscountAndCurrencyRounding() throws Exception {
        assertCalculation("""
                {"currency":"USD","items":[{"price":"0.105","quantity":3},
                {"price":"0.005","quantity":1}],"discountPercent":"10"}
                """, "USD", "0.320", "0.29");
    }

    @Test
    void acceptsFreeOrdersAndPreservesDecimalScale() throws Exception {
        assertCalculation("""
                {"currency":"USD","items":[{"price":"0.00","quantity":2}],
                "discountPercent":"12.5"}
                """, "USD", "0.00", "0.00");
    }

    @Test
    void roundsOnlyAfterAggregation() throws Exception {
        assertCalculation("""
                {"currency":"USD","items":[{"price":"0.005","quantity":1},
                {"price":"0.005","quantity":1}],"discountPercent":"0"}
                """, "USD", "0.010", "0.01");
    }

    @Test
    void supportsZeroDigitCurrency() throws Exception {
        assertCalculation("""
                {"currency":"JPY","items":[{"price":"12.5","quantity":1}],
                "discountPercent":"0"}
                """, "JPY", "12.5", "13");
    }

    @Test
    void supportsThreeDigitCurrency() throws Exception {
        assertCalculation("""
                {"currency":"KWD","items":[{"price":"1.2345","quantity":1}],
                "discountPercent":"0"}
                """, "KWD", "1.2345", "1.235");
    }

    @Test
    void formatsPlainDecimalWithoutLosingTrailingZeros() throws Exception {
        assertCalculation("""
                {"currency":"USD","items":[{"price":"1000.000","quantity":1}],
                "discountPercent":"100"}
                """, "USD", "1000.000", "0.00");
    }

    private void assertCalculation(String request, String currency, String total, String payable)
            throws Exception {
        mvc.perform(post("/api/orders/calculate").contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.currency").value(currency))
                .andExpect(jsonPath("$.total").value(total))
                .andExpect(jsonPath("$.payableAmount").value(payable));
    }
}
