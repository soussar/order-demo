package com.example.orders.adapter.rest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

class RestDecimalParserTest {
    @ParameterizedTest
    @CsvSource({
            "123, 123, 3, 0",
            "+123, 123, 3, 0",
            "-123, -123, 3, 0",
            "0, 0, 1, 0",
            "-0, 0, 1, 0",
            "-0.000000, 0.000000, 1, 6",
            "+0.00, 0.00, 1, 2",
            "000123.4500, 123.4500, 7, 4",
            "000.001230, 0.001230, 4, 6",
            "0000, 0, 1, 0",
            ".5, 0.5, 1, 1",
            "-.5, -0.5, 1, 1",
            "+.5, 0.5, 1, 1",
            "1., 1, 1, 0",
            "0.000001, 0.000001, 1, 6",
            "999999999999.999999, 999999999999.999999, 18, 6",
            "-999999999999.999999, -999999999999.999999, 18, 6"
    })
    void preservesExactValuePrecisionAndScale(String input, String expected, int precision, int scale) {
        BigDecimal result = RestDecimalParser.parse(input, "items[2].price");

        assertEquals(new BigDecimal(expected), result);
        assertEquals(expected, result.toPlainString());
        assertEquals(precision, result.precision());
        assertEquals(scale, result.scale());
    }

    @Test
    void acceptsExactly64CharactersWithoutCountingLeadingZerosAsPrecision() {
        String largest = "0".repeat(45) + "999999999999.999999";
        assertEquals(64, largest.length());
        BigDecimal result = RestDecimalParser.parse(largest, "price");
        assertEquals(new BigDecimal("999999999999.999999"), result);
        assertEquals(18, result.precision());
        assertEquals(6, result.scale());

        BigDecimal zero = RestDecimalParser.parse("0".repeat(64), "discountPercent");
        assertEquals(BigDecimal.ZERO, zero);
        assertEquals(1, zero.precision());
        assertEquals(0, zero.scale());
    }

    static Stream<Arguments> invalidValues() {
        String syntax = " must be a plain decimal without scientific notation";
        Stream<Arguments> malformed = Stream.of("", " ", " 1", "1 ", "+", "-", ".", "+.",
                        "1..0", "1,000", "1_000", "1e3", "1E+1000000000", "1e-2147483647",
                        "NaN", "Infinity", "1\n", "1\t", "\uFF11\uFF12")
                .map(value -> Arguments.of(value, syntax));
        Stream<Arguments> boundaries = Stream.of(
                Arguments.of(null, " is required"),
                Arguments.of("0".repeat(65), " must contain at most 64 characters"),
                Arguments.of("0.0000001", " scale must be between 0 and 6"),
                Arguments.of("1.0000000", " scale must be between 0 and 6"),
                Arguments.of("-0.0000000", " scale must be between 0 and 6"),
                Arguments.of("1000000000000", " absolute value must be less than 1000000000000"),
                Arguments.of("-1000000000000", " absolute value must be less than 1000000000000"),
                Arguments.of("+0001000000000000.000000", " absolute value must be less than 1000000000000"));
        return Stream.concat(malformed, boundaries);
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void rejectsInvalidValuesWithExactTypeAndIndexedMessage(String input, String suffix) {
        InvalidCalculationRequestException failure = assertThrowsExactly(InvalidCalculationRequestException.class,
                () -> RestDecimalParser.parse(input, "items[7].price"));

        assertEquals("items[7].price" + suffix, failure.getMessage());
    }

    @Test
    void preservesDiscountFieldPathInErrors() {
        InvalidCalculationRequestException failure = assertThrowsExactly(InvalidCalculationRequestException.class,
                () -> RestDecimalParser.parse("1e2", "discountPercent"));

        assertEquals("discountPercent must be a plain decimal without scientific notation", failure.getMessage());
    }

    static Stream<Arguments> precedenceCases() {
        return Stream.of(
                // Length wins over malformed syntax and oversized magnitude.
                Arguments.of("x".repeat(65), " must contain at most 64 characters"),
                Arguments.of("9".repeat(65), " must contain at most 64 characters"),
                // Syntax wins over apparent scale/magnitude violations.
                Arguments.of("1000000000000.0000000e0", " must be a plain decimal without scientific notation"),
                Arguments.of("1000000000000.0000000 ", " must be a plain decimal without scientific notation"),
                // Scale wins over magnitude and excess precision.
                Arguments.of("1000000000000.0000000", " scale must be between 0 and 6"),
                // Magnitude wins over excess precision.
                Arguments.of("1000000000000.000000", " absolute value must be less than 1000000000000"));
    }

    @ParameterizedTest
    @MethodSource("precedenceCases")
    void preservesFirstFailureWhenSeveralConstraintsAreViolated(String input, String suffix) {
        InvalidCalculationRequestException failure = assertThrowsExactly(InvalidCalculationRequestException.class,
                () -> RestDecimalParser.parse(input, "items[0].price"));

        assertEquals("items[0].price" + suffix, failure.getMessage());
    }
}
