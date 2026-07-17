package core.tools.parser.olx;

import model.Currency;

import java.math.BigDecimal;

/**
 * Парсер текстових значень цін у структурований формат.
 */
public class PriceParser {

    /**
     * Вилучає числове значення ціни та визначає її валюту із сирого рядка оголошення.
     * <p>
     * Логіка обробки вхідного рядка:
     * <ol>
     * <li>Визначає валюту за допомогою символьних мапінгів у {@link Currency#parsePrice(String)}.</li>
     * <li>Видаляє всі символи, крім цифр, ком та крапок (наприклад, "4 500.50 грн" &rarr; "4500,50").</li>
     * <li>Усуває пробіли та стандартизує десятковий роздільник, замінюючи кому на крапку.</li>
     * <li>Нормалізує рядок на випадок дублювання крапок (залишає лише першу крапку як роздільник копійок/центів, інші видаляє).</li>
     * <li>Безпечно конвертує фінальний рядок у {@link BigDecimal}.</li>
     * </ol>
     * </p>
     *
     * @param rawPrice сирий рядок ціни з картки оголошення (наприклад, " 1 250,50 $ ")
     * @return об'єкт {@link PriceResult}, що містить обчислене числове значення та рядок валюти (може бути {@code null})
     */
    public static PriceResult parse(String rawPrice) {
        // Перевірка на порожній вхідний рядок
        if (rawPrice == null || rawPrice.isBlank()) {
            return new PriceResult(null, "");
        }

        // 1. Визначаємо валюту оголошення
        String currency = Currency.parsePrice(rawPrice);
        BigDecimal value = null;

        // 2. Очищення: залишаємо лише цифри, коми та крапки. Кому міняємо на крапку.
        String digits = rawPrice.replaceAll("[^\\d,.]", "")
                .replaceAll("\\s", "")
                .replace(",", ".");

        // 3. Нормалізація десяткових крапок (запобігає помилкам, якщо в ціні є декілька крапок, наприклад "1.200.00")
        int firstDot = digits.indexOf('.');
        if (firstDot != -1) {
            // Залишаємо першу крапку, а всі наступні за нею крапки просто видаляємо
            digits = digits.substring(0, firstDot + 1)
                    + digits.substring(firstDot + 1).replace(".", "");
        }

        // 4. Спроба безпечного перетворення рядка в точний BigDecimal
        try {
            if (!digits.isEmpty()) {
                value = new BigDecimal(digits);
            }
        } catch (NumberFormatException ignored) {
            // Виникає при нечислових значеннях (наприклад, "Договірна") або некоректному форматі.
            // Залишаємо value = null.
        }

        return new PriceResult(value, currency);
    }
}
