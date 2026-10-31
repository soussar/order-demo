import java.math.BigDecimal;
import java.util.Objects;

/**
 * Performs monetary calculations using exact decimal arithmetic.
 * Callers should construct amounts from decimal strings or BigDecimal.valueOf,
 * rather than the BigDecimal(double) constructor.
 */
public class OrderService {

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
