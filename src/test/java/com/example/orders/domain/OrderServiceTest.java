package com.example.orders.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

public class OrderServiceTest {
    private static int passed;

    public static void main(String[] args) {

        OrderService service = new OrderService();

        BigDecimal result = service.calculateTotal(
                new BigDecimal("0.10"),
                3
        );

        if (result.compareTo(new BigDecimal("0.30")) != 0) {
            throw new AssertionError(
                    "Expected 0.30 but got " + result
            );
        }

        System.out.println("TEST PASSED: monetary calculation");
        passed++;

        assertThrows("negative price", IllegalArgumentException.class,
                () -> service.calculateTotal(new BigDecimal("-0.01"), 1));
        assertThrows("zero quantity", IllegalArgumentException.class,
                () -> service.calculateTotal(BigDecimal.ONE, 0));
        assertThrows("negative quantity", IllegalArgumentException.class,
                () -> service.calculateTotal(BigDecimal.ONE, -1));
        assertThrows("null price", NullPointerException.class,
                () -> service.calculateTotal(null, 1));

        assertValidity(service, "positive total", new BigDecimal("0.01"), true);
        assertValidity(service, "zero total", new BigDecimal("0.00"), false);
        assertValidity(service, "negative total", new BigDecimal("-0.01"), false);
        assertThrows("null total", NullPointerException.class,
                () -> service.isValidOrder(null));

        assertDiscount(service, "10 percent discount", "100.00", "10", "90");
        assertDiscount(service, "25 percent discount", "80.00", "25", "60");
        assertDiscount(service, "fractional discount", "19.99", "12.5", "17.49125");
        assertDiscount(service, "zero discount", "50.00", "0", "50");
        assertDiscount(service, "full discount", "100.00", "100", "0");
        assertDiscount(service, "zero amount", "0.00", "12.5", "0");
        assertThrows("negative amount", IllegalArgumentException.class,
                () -> service.applyDiscount(new BigDecimal("-0.01"), BigDecimal.TEN));
        assertThrows("negative discount", IllegalArgumentException.class,
                () -> service.applyDiscount(BigDecimal.TEN, new BigDecimal("-0.01")));
        assertThrows("discount over 100", IllegalArgumentException.class,
                () -> service.applyDiscount(BigDecimal.TEN, new BigDecimal("100.01")));
        assertThrows("null amount", NullPointerException.class,
                () -> service.applyDiscount(null, BigDecimal.TEN));
        assertThrows("null discount", NullPointerException.class,
                () -> service.applyDiscount(BigDecimal.TEN, null));

        testOrders(service);
        System.out.println("ALL " + passed + " TESTS PASSED");
    }

    private static void testOrders(OrderService service) {
        Currency usd = Currency.getInstance("USD");
        Currency unsupported = Currency.getInstance("XXX");
        assertThrows("null item price", NullPointerException.class,
                () -> new OrderItem(null, 1));
        assertThrows("negative item price", IllegalArgumentException.class,
                () -> new OrderItem(new BigDecimal("-0.01"), 1));
        assertThrows("zero item quantity", IllegalArgumentException.class,
                () -> new OrderItem(BigDecimal.ONE, 0));
        assertThrows("negative item quantity", IllegalArgumentException.class,
                () -> new OrderItem(BigDecimal.ONE, -1));
        assertThrows("null currency", NullPointerException.class,
                () -> new Order(null, List.of(item("1", 1))));
        assertThrows("unsupported currency", IllegalArgumentException.class,
                () -> new Order(unsupported, List.of(item("1", 1))));
        assertThrows("null items", NullPointerException.class, () -> new Order(usd, null));
        assertThrows("empty items", IllegalArgumentException.class,
                () -> new Order(usd, List.of()));
        List<OrderItem> source = new ArrayList<>();
        source.add(null);
        assertThrows("null item", NullPointerException.class, () -> new Order(usd, source));
        source.set(0, item("0.10", 3));
        Order copied = new Order(usd, source);
        source.set(0, item("99", 1));
        assertAmount("defensive copy", "0.30", service.calculateTotal(copied));
        assertThrows("unmodifiable items", UnsupportedOperationException.class,
                () -> copied.items().add(item("1", 1)));
        assertThrows("null order total", NullPointerException.class,
                () -> service.calculateTotal((Order) null));
        assertThrows("null order payable", NullPointerException.class,
                () -> service.calculatePayableAmount(null, BigDecimal.ZERO));

        Order exact = new Order(usd, List.of(item("0.105", 3), item("0.005", 1)));
        assertAmount("exact multi-item total", "0.320", service.calculateTotal(exact));
        assertPayable(service, "aggregate before rounding", exact, "0", "0.32");
        Order halfCentItems = new Order(usd, List.of(item("0.005", 1), item("0.005", 1)));
        assertPayable(service, "two half-cent items round only after aggregation",
                halfCentItems, "0", "0.01");
        Order discounted = new Order(usd, List.of(item("0.05", 1), item("0.05", 1)));
        assertPayable(service, "discount after aggregation", discounted, "10", "0.09");
        Order boundary = new Order(usd, List.of(item("0.005", 1)));
        assertPayable(service, "HALF_UP tie", boundary, "0", "0.01");
        assertPayable(service, "no rounding before discount", boundary, "50", "0.00");
        assertPayable(service, "fractional percentage", new Order(usd,
                List.of(item("19.99", 1))), "12.5", "17.49");
        assertPayable(service, "full order discount", exact, "100", "0.00");
        assertPayable(service, "zero-digit currency", new Order(Currency.getInstance("JPY"),
                List.of(item("12.5", 1))), "0", "13");
        assertPayable(service, "three-digit currency", new Order(Currency.getInstance("KWD"),
                List.of(item("1.2345", 1))), "0", "1.235");
        Order free = new Order(usd, List.of(item("0", 2), item("0.00", 1)));
        assertAmount("free total", "0", service.calculateTotal(free));
        assertPayable(service, "free payable", free, "12.5", "0.00");
        for (Order order : List.of(free, exact)) {
            String kind = order == free ? "free" : "paid";
            assertThrows(kind + " null discount", NullPointerException.class,
                    () -> service.calculatePayableAmount(order, null));
            assertThrows(kind + " negative discount", IllegalArgumentException.class,
                    () -> service.calculatePayableAmount(order, new BigDecimal("-0.01")));
            assertThrows(kind + " excessive discount", IllegalArgumentException.class,
                    () -> service.calculatePayableAmount(order, new BigDecimal("100.01")));
        }
        assertAmount("legacy zero price", "0", service.calculateTotal(BigDecimal.ZERO, 2));
        assertAmount("legacy exact multiplication", "0.015",
                service.calculateTotal(new BigDecimal("0.005"), 3));
        assertValidity(service, "legacy positive tiny total", new BigDecimal("0.0001"), true);
        assertThrows("policy null amount", NullPointerException.class,
                () -> MonetaryPolicy.round(null, usd));
        assertThrows("policy negative amount", IllegalArgumentException.class,
                () -> MonetaryPolicy.round(new BigDecimal("-1"), usd));
        assertThrows("policy null currency", NullPointerException.class,
                () -> MonetaryPolicy.round(BigDecimal.ZERO, null));
        assertThrows("policy unsupported currency", IllegalArgumentException.class,
                () -> MonetaryPolicy.round(BigDecimal.ZERO, unsupported));
    }

    private static OrderItem item(String price, int quantity) {
        return new OrderItem(new BigDecimal(price), quantity);
    }

    private static void assertPayable(OrderService service, String name, Order order,
            String discount, String expected) {
        BigDecimal actual = service.calculatePayableAmount(order, new BigDecimal(discount));
        // equals checks both the value and the final currency scale.
        if (!actual.equals(new BigDecimal(expected))) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
        passed++;
        System.out.println("TEST PASSED: " + name);
    }

    private static void assertAmount(String name, String expected, BigDecimal actual) {
        if (actual.compareTo(new BigDecimal(expected)) != 0) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
        passed++;
        System.out.println("TEST PASSED: " + name);
    }

    private static void assertDiscount(OrderService service, String name,
            String amount, String discountPercent, String expected) {
        BigDecimal actual = service.applyDiscount(
                new BigDecimal(amount), new BigDecimal(discountPercent));
        if (actual.compareTo(new BigDecimal(expected)) != 0) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
        System.out.println("TEST PASSED: " + name);
        passed++;
    }

    // Explicit checks stay active even when java is run without -ea.
    private static void assertValidity(OrderService service, String name,
            BigDecimal total, boolean expected) {
        boolean actual = service.isValidOrder(total);
        if (actual != expected) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
        System.out.println("TEST PASSED: " + name);
        passed++;
    }

    private static void assertThrows(String name,
            Class<? extends RuntimeException> expectedType, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException actual) {
            if (actual.getClass() != expectedType) {
                throw new AssertionError(name + ": expected " + expectedType.getName()
                        + " but got " + actual.getClass().getName(), actual);
            }
            System.out.println("TEST PASSED: " + name);
            passed++;
            return;
        }
        throw new AssertionError(name + ": expected " + expectedType.getName()
                + " but no exception was thrown");
    }
}
