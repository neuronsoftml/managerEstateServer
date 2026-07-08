package model;

import java.math.BigDecimal;

public enum Currency {
    USD ("$", "USD"),
    EUR ("€","EUR"),
    UAH ("грн", "UAH");

    private final String slug;
    private  final String label;

    Currency(String slug, String label){
        this.slug = slug;
        this.label = label;
    }


    public String getSlug() {return slug;}

    public String getLabel() {return label;}


    /**
     * Витягує валюту з рядка ціни.
     * Приклади: "450 €" → 450 / EUR
     *           "12 000 грн." → 12000 / UAH
     *           "Договірна" → null / ""
     */
    public static String parsePrice(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // Визначаємо валюту
        if (raw.contains(USD.getSlug()))    return USD.label;
        else if (raw.contains(EUR.getSlug())) return EUR.label;
        else if (raw.toLowerCase().contains(UAH.getSlug())) return UAH.label;
        return null;
    }
}
