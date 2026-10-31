import java.math.BigDecimal;

public class OrderServiceTest {

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

        System.out.println("ALL 20 TESTS PASSED");
    }

    private static void assertDiscount(OrderService service, String name,
            String amount, String discountPercent, String expected) {
        BigDecimal actual = service.applyDiscount(
                new BigDecimal(amount), new BigDecimal(discountPercent));
        if (actual.compareTo(new BigDecimal(expected)) != 0) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
        System.out.println("TEST PASSED: " + name);
    }

    // Explicit checks stay active even when java is run without -ea.
    private static void assertValidity(OrderService service, String name,
            BigDecimal total, boolean expected) {
        boolean actual = service.isValidOrder(total);
        if (actual != expected) {
            throw new AssertionError(name + ": expected " + expected + " but got " + actual);
        }
        System.out.println("TEST PASSED: " + name);
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
            return;
        }
        throw new AssertionError(name + ": expected " + expectedType.getName()
                + " but no exception was thrown");
    }
}
