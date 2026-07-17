package core.tools.parser.olx;

import java.math.BigDecimal;

/**
 * Об'єкт перенесення даних (DTO) для одночасного повернення обчисленої ціни та коду валюти.
 * <p>
 * Є незмінним (immutable) контейнером.
 * </p>
 */
public class PriceResult {

    /** Числове значення ціни з високою точністю (або {@code null}, якщо ціна договірна) */
    private final BigDecimal value;

    /** Текстовий ISO-код валюти (наприклад, "UAH", "USD", "EUR" або порожній рядок) */
    private final String currency;

    /**
     * Конструктор для створення результату парсингу ціни.
     *
     * @param value    обчислене числове значення ціни
     * @param currency визначена валюта
     */
    public PriceResult(BigDecimal value, String currency) {
        this.value = value;
        this.currency = currency;
    }

    /**
     * Повертає числове значення ціни.
     *
     * @return об'єкт {@link BigDecimal}, або {@code null}
     */
    public BigDecimal getValue() {
        return value;
    }

    /**
     * Повертає код валюти ціни.
     *
     * @return системний код валюти (наприклад, "UAH")
     */
    public String getCurrency() {
        return currency;
    }
}
