package com.example.orders.adapter.rest;

import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CalculateOrderRequest(
        @JsonDeserialize(using = StrictString.class) String currency,
        List<Item> items,
        @JsonDeserialize(using = StrictString.class) String discountPercent) {
    public record Item(@JsonDeserialize(using = StrictString.class) String price,
                       @JsonDeserialize(using = StrictInteger.class) Integer quantity) {
    }

    public static final class StrictString extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(String.class, "Expected a JSON string");
            }
            return parser.getString();
        }
    }

    public static final class StrictInteger extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class, "Expected a JSON integer");
            }
            return parser.getIntValue();
        }
    }
}
