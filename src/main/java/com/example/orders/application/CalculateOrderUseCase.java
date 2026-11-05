package com.example.orders.application;

import com.example.orders.domain.Order;
import com.example.orders.domain.OrderService;

import java.math.BigDecimal;
import java.util.Objects;

/** Application entry point for order calculations. */
public final class CalculateOrderUseCase {
    private final OrderService orderService;

    public CalculateOrderUseCase(OrderService orderService) {
        this.orderService = Objects.requireNonNull(orderService, "orderService must not be null");
    }

    public BigDecimal calculateTotal(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        return orderService.calculateTotal(order);
    }

    public BigDecimal calculatePayableAmount(Order order, BigDecimal discountPercent) {
        Objects.requireNonNull(order, "order must not be null");
        Objects.requireNonNull(discountPercent, "discountPercent must not be null");
        return orderService.calculatePayableAmount(order, discountPercent);
    }
}
