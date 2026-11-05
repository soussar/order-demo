package com.example.orders.application;

import com.example.orders.domain.Order;
import com.example.orders.domain.OrderItem;
import com.example.orders.domain.OrderService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalculateOrderUseCaseTest {
    private final CalculateOrderUseCase useCase = new CalculateOrderUseCase(new OrderService());
    private final Order order = new Order(Currency.getInstance("USD"), List.of(
            new OrderItem(new BigDecimal("0.105"), 3),
            new OrderItem(new BigDecimal("0.005"), 1)));

    @Test
    void preservesExactAggregateScale() {
        assertEquals(new BigDecimal("0.320"), useCase.calculateTotal(order));
    }

    @Test
    void appliesFractionalDiscountAndPreservesCurrencyScale() {
        assertEquals(new BigDecimal("0.28"),
                useCase.calculatePayableAmount(order, new BigDecimal("12.50")));
    }

    @Test
    void rejectsDiscountsOutsideDomainRange() {
        assertThrows(IllegalArgumentException.class,
                () -> useCase.calculatePayableAmount(order, new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class,
                () -> useCase.calculatePayableAmount(order, new BigDecimal("100.01")));
    }

    @Test
    void preservesExactAggregateAndFinalCurrencyScale() {
        assertEquals(new BigDecimal("0.29"), useCase.calculatePayableAmount(order, BigDecimal.TEN));
        assertEquals(new BigDecimal("0.00"),
                useCase.calculatePayableAmount(order, new BigDecimal("100")));
    }

    @Test
    void acceptsFreeOrdersAndStillValidatesTheirDiscount() {
        Order free = new Order(Currency.getInstance("USD"),
                List.of(new OrderItem(BigDecimal.ZERO, 2)));

        assertEquals(BigDecimal.ZERO, useCase.calculateTotal(free));
        assertEquals(new BigDecimal("0.00"), useCase.calculatePayableAmount(free, BigDecimal.TEN));
        assertThrows(IllegalArgumentException.class,
                () -> useCase.calculatePayableAmount(free, new BigDecimal("101")));
        assertThrows(IllegalArgumentException.class,
                () -> useCase.calculatePayableAmount(free, new BigDecimal("-0.01")));
        assertThrows(NullPointerException.class,
                () -> useCase.calculatePayableAmount(free, null));
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(NullPointerException.class, () -> new CalculateOrderUseCase(null));

        assertThrows(NullPointerException.class, () -> useCase.calculateTotal(null));
        assertThrows(NullPointerException.class,
                () -> useCase.calculatePayableAmount(null, BigDecimal.ZERO));
        assertThrows(NullPointerException.class,
                () -> useCase.calculatePayableAmount(order, null));
    }
}
