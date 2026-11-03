import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/** Currency support and rounding at the final payment boundary. */
public final class MonetaryPolicy {
    private MonetaryPolicy() {
    }

    /** Supports currencies with a defined, non-negative fraction-digit count. */
    public static void validateCurrency(Currency currency) {
        Objects.requireNonNull(currency, "currency must not be null");
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("currency must define fraction digits");
        }
    }

    public static BigDecimal round(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        validateCurrency(currency);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        return amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
    }
}
