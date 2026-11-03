import java.util.Currency;
import java.util.List;
import java.util.Objects;

/** An immutable order whose items all use the order's currency. */
public record Order(Currency currency, List<OrderItem> items) {
    public Order {
        MonetaryPolicy.validateCurrency(currency);
        Objects.requireNonNull(items, "items must not be null");
        items = List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
    }
}
