package com.example.orders.adapter.rest;

import java.math.BigDecimal;
import java.util.regex.Pattern;

final class RestDecimalParser {
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");

    private RestDecimalParser() {
    }

    static BigDecimal parse(String value, String field) {
        required(value, field);
        if (value.length() > 64) {
            throw new InvalidCalculationRequestException(field + " must contain at most 64 characters");
        }
        if (!PLAIN_DECIMAL.matcher(value).matches()) {
            throw new InvalidCalculationRequestException(field + " must be a plain decimal without scientific notation");
        }
        int start = value.charAt(0) == '+' || value.charAt(0) == '-' ? 1 : 0;
        int dot = value.indexOf('.');
        int integerEnd = dot < 0 ? value.length() : dot;
        int scale = dot < 0 ? 0 : value.length() - dot - 1;
        if (scale > 6) {
            throw new InvalidCalculationRequestException(field + " scale must be between 0 and 6");
        }
        int firstIntegerDigit = start;
        while (firstIntegerDigit < integerEnd && value.charAt(firstIntegerDigit) == '0') {
            firstIntegerDigit++;
        }
        if (integerEnd - firstIntegerDigit > 12) {
            throw new InvalidCalculationRequestException(field + " absolute value must be less than 1000000000000");
        }
        int precision = 0;
        boolean significant = false;
        for (int index = start; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit != '.') {
                significant |= digit != '0';
                if (significant) {
                    precision++;
                }
            }
        }
        if (precision > 18) {
            throw new InvalidCalculationRequestException(field + " precision must be at most 18 significant digits");
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new InvalidCalculationRequestException(field + " must contain a decimal value");
        }
    }

    private static void required(Object value, String field) {
        if (value == null) {
            throw new InvalidCalculationRequestException(field + " is required");
        }
    }
}
