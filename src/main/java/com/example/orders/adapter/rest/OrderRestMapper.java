package com.example.orders.adapter.rest;

import com.example.orders.domain.Order;
import com.example.orders.domain.OrderItem;
import com.example.orders.domain.OrderService;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.ArrayList;
import java.util.regex.Pattern;

public final class OrderRestMapper {
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
    private final OrderService orderService;

    public OrderRestMapper(OrderService orderService) {
        this.orderService = Objects.requireNonNull(orderService, "orderService must not be null");
    }

    Order toOrder(CalculateOrderRequest request) {
        required(request, "request");
        required(request.currency(), "currency");
        required(request.items(), "items");
        if (request.items().size() > 100) {
            throw new InvalidCalculationRequestException("items must contain at most 100 entries");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(request.currency());
        } catch (IllegalArgumentException exception) {
            throw new InvalidCalculationRequestException("currency must be a recognized currency code");
        }
        var items = new ArrayList<OrderItem>(request.items().size());
        for (int index = 0; index < request.items().size(); index++) {
            var item = request.items().get(index);
            String path = "items[" + index + "]";
            required(item, path);
            required(item.quantity(), path + ".quantity");
            if (item.quantity() < 1 || item.quantity() > 10000) {
                throw new InvalidCalculationRequestException(path + ".quantity must be between 1 and 10000");
            }
            BigDecimal price = decimal(item.price(), path + ".price");
            try {
                items.add(new OrderItem(price, item.quantity()));
            } catch (IllegalArgumentException exception) {
                throw new InvalidCalculationRequestException(path + ".price must not be negative");
            }
        }
        try {
            return new Order(currency, items);
        } catch (IllegalArgumentException exception) {
            throw new InvalidCalculationRequestException("items must be nonempty and currency must support fraction digits");
        }
    }

    BigDecimal toDiscount(CalculateOrderRequest request) {
        required(request, "request");
        BigDecimal discount = decimal(request.discountPercent(), "discountPercent");
        // Ask the domain to validate the percentage without repeating its range rules.
        try {
            orderService.applyDiscount(BigDecimal.ZERO, discount);
        } catch (IllegalArgumentException exception) {
            throw new InvalidCalculationRequestException("discountPercent must be between 0 and 100");
        }
        return discount;
    }

    CalculateOrderResponse toResponse(Order order, BigDecimal total, BigDecimal payableAmount) {
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(total, "total must not be null");
        Objects.requireNonNull(payableAmount, "payableAmount must not be null");
        return new CalculateOrderResponse(order.currency().getCurrencyCode(),
                total.toPlainString(), payableAmount.toPlainString());
    }

    private BigDecimal decimal(String value, String field) {
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

    private void required(Object value, String field) {
        if (value == null) {
            throw new InvalidCalculationRequestException(field + " is required");
        }
    }
}
