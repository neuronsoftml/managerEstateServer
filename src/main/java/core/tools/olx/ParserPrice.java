package core.tools.olx;

import model.Currency;

import java.math.BigDecimal;

public class ParserPrice {
    public class PriceParser {

        public static PriceResult parse(String rawPrice) {
            if (rawPrice == null || rawPrice.isBlank()) {
                return new PriceResult(null, "");
            }

            String currency = Currency.parsePrice(rawPrice);
            BigDecimal value = null;

            String digits = rawPrice.replaceAll("[^\\d,.]", "")
                    .replaceAll("\\s", "")
                    .replace(",", ".");

            int firstDot = digits.indexOf('.');
            if (firstDot != -1) {
                digits = digits.substring(0, firstDot + 1)
                        + digits.substring(firstDot + 1).replace(".", "");
            }

            try {
                if (!digits.isEmpty()) {
                    value = new BigDecimal(digits);
                }
            } catch (NumberFormatException ignored) {
                // "Договірна" або некоректний формат
            }

            return new PriceResult(value, currency);
        }

        // Зручний DTO-клас для повернення двох значень одночасно
        public static class PriceResult {
            private final BigDecimal value;
            private final String currency;

            public PriceResult(BigDecimal value, String currency) {
                this.value = value;
                this.currency = currency;
            }

            public BigDecimal getValue() { return value; }
            public String getCurrency() { return currency; }
        }
    }
}
