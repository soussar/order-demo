package com.example.orders.adapter.rest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import jakarta.servlet.Filter;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DuplicateJsonMemberRejectionTest {
    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc() {
        List<Filter> filters = new ArrayList<>(List.of(context.getBean(RequestIdFilter.class),
                context.getBean(CalculationBodyLimitFilter.class)));
        AnnotationAwareOrderComparator.sort(filters);
        return MockMvcBuilders.webAppContextSetup(context).addFilters(filters.toArray(Filter[]::new)).build();
    }

    static Stream<Arguments> duplicateMembers() {
        String prefix = "{\"currency\":\"USD\",\"items\":[{";
        String suffix = "}],\"discountPercent\":\"0\"}";
        return Stream.of(
                Arguments.of("quantity 0 then 1", prefix + "\"price\":\"2.50\",\"quantity\":0,\"quantity\":1" + suffix),
                Arguments.of("quantity 1 then 0", prefix + "\"price\":\"2.50\",\"quantity\":1,\"quantity\":0" + suffix),
                Arguments.of("identical quantities", prefix + "\"price\":\"2.50\",\"quantity\":1,\"quantity\":1" + suffix),
                Arguments.of("root currency", "{\"currency\":\"USD\",\"currency\":\"EUR\",\"items\":["
                        + "{\"price\":\"2.50\",\"quantity\":1}],\"discountPercent\":\"0\"}"),
                Arguments.of("root discount", prefix + "\"price\":\"2.50\",\"quantity\":1}],"
                        + "\"discountPercent\":\"0\",\"discountPercent\":\"10\"}"),
                Arguments.of("nested price", prefix + "\"price\":\"2.50\",\"price\":\"3.50\",\"quantity\":1" + suffix),
                Arguments.of("escaped quantity", prefix + "\"price\":\"2.50\",\"quantity\":1,\"\\u0071uantity\":2" + suffix));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("duplicateMembers")
    void rejectsDuplicateMembersWithSafeProblemAndRequestId(String scenario, String body) throws Exception {
        var result = mvc().perform(post("/api/orders/calculate").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON).header(RequestIdFilter.HEADER_NAME, "duplicate-json-test")
                        .content(body))
                .andReturn();

        assertEquals(List.of("duplicate-json-test"), List.copyOf(result.getResponse().getHeaders(RequestIdFilter.HEADER_NAME)));
        var response = result.getResponse();
        assertEquals(400, response.getStatus(), scenario);
        assertInstanceOf(HttpMessageNotReadableException.class, result.getResolvedException(), scenario);
        var problem = context.getBean(ObjectMapper.class).readTree(response.getContentAsByteArray());
        assertEquals("Bad Request", problem.get("title").asString());
        assertEquals(400, problem.get("status").intValue());
        assertEquals("/api/orders/calculate", problem.get("instance").asString());
        if (problem.get("type") != null) {
            assertEquals("about:blank", problem.get("type").asString());
        }
        String detail = problem.get("detail").asString();
        assertTrue(detail.equals("Request body must be valid JSON with the required field types")
                || detail.matches("(?:currency|discountPercent|items(?:\\[[0-9]+\\])?(?:\\.(?:price|quantity))?) must have the required JSON type"));
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, MediaType.parseMediaType(response.getContentType()));
        assertFalse(response.getContentAsString().contains(body));
        assertFalse(response.getContentAsString().contains("Duplicate field"));
        assertFalse(response.getContentAsString().contains("Exception"));
        assertFalse(response.getContentAsString().contains("tools.jackson"));
        assertNull(problem.get("trace"));
        assertNull(problem.get("exception"));
    }

    @Test
    void acceptsSameMemberNamesInSeparateItemObjects() throws Exception {
        mvc().perform(post("/api/orders/calculate").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON).content("""
                                {"currency":"USD","items":[{"price":"2.50","quantity":1},
                                {"price":"3.50","quantity":2}],
                                "discountPercent":"0"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"currency":"USD","total":"9.50","payableAmount":"9.50"}
                        """));
    }
}
