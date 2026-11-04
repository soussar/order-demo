package com.example.orders.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Performs monetary calculations using exact decimal arithmetic.
 * Callers should construct amounts from decimal strings or BigDecimal.valueOf,
 * rather than the BigDecimal(double) constructor.
 */
public class OrderService {

    /** Sums exact line totals without currency rounding. */
    public BigDecimal calculateTotal(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : order.items()) {
            total = total.add(calculateTotal(item.price(), item.quantity()));
        }
        return total;
    }

    /** Applies the discount to the exact aggregate, then rounds once. */
    public BigDecimal calculatePayableAmount(Order order, BigDecimal discountPercent) {
        BigDecimal discounted = applyDiscount(calculateTotal(order), discountPercent);
        return MonetaryPolicy.round(discounted, order.currency());
    }

    /**
     * Multiplies without rounding: currency scale and rounding policy belong to
     * the caller, since this service does not assume a particular currency.
     * A zero price is allowed, but quantities must be strictly positive.
     *
     * @throws NullPointerException if price is null
     * @throws IllegalArgumentException if price is negative or quantity is not positive
     */
    public BigDecimal calculateTotal(
            BigDecimal price,
            int quantity) {

        Objects.requireNonNull(price, "price must not be null");
        if (price.signum() < 0) {
            throw new IllegalArgumentException("price must not be negative");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }

        return price.multiply(BigDecimal.valueOf(quantity));
    }

    /**
     * Applies a percentage discount without rounding.
     *
     * @throws NullPointerException if amount or discountPercent is null
     * @throws IllegalArgumentException if amount is negative or discountPercent
     *         is outside the inclusive range 0 to 100
     */
    public BigDecimal applyDiscount(
            BigDecimal amount,
            BigDecimal discountPercent) {

        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(discountPercent, "discountPercent must not be null");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (discountPercent.signum() < 0
                || discountPercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("discountPercent must be between 0 and 100");
        }

        return amount.subtract(amount.multiply(discountPercent).movePointLeft(2));
    }

    /**
     * Preserves the rule that only strictly positive totals are valid.
     * The sign check is independent of the amount's decimal scale.
     *
     * @throws NullPointerException if total is null
     */
    public boolean isValidOrder(
            BigDecimal total) {

        Objects.requireNonNull(total, "total must not be null");
        return total.signum() > 0;
    }
}
