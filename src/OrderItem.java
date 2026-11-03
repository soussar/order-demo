import java.math.BigDecimal;
import java.util.Objects;

/** An immutable line item with an exact, non-negative unit price. */
public record OrderItem(BigDecimal price, int quantity) {
    public OrderItem {
        Objects.requireNonNull(price, "price must not be null");
        if (price.signum() < 0) {
            throw new IllegalArgumentException("price must not be negative");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
