package model;

/**
 * Міста для пошуку на OLX.
 * Slug — це частина URL яку OLX використовує для міста.
 *
 * Як знайти slug: відкрити пошук по місту на olx.ua і скопіювати
 * сегмент URL після категорії, напр: .../prodazha-kvartir/chernovtsy/
 **/
public enum City {
    // Існуючі міста та райони
    CHERNIVTSI    ("chernovtsy",              "Чернівці"),
    GODILIV       ("godilov",                 "Годилів"),


    // ТОПОВІ ПЕРЕДМІСТЯ ЧЕРНІВЦІВ (ДЕ БАГАТО КВАРТИР/НОВОБУДОВ)
    KOROVIA       ("koroviya",                "Коровія"),
    CHAGOR        ("chagor",                  "Чагор");

    private final String slug;
    private final String label;

    City(String slug, String label) {
        this.slug  = slug;
        this.label = label;
    }

    public String getSlug()  { return slug; }
    public String getLabel() { return label; }

    // Статичний метод для пошуку міста в тексті
    public static City findByRawString(String rawCity) {
        if (rawCity == null) return null;

        for (City city : City.values()) {
            if (rawCity.contains(city.getLabel())) {
                return city;
            }
        }
        return null; // або якесь дефолтне значення, наприклад UNKNOWN
    }
}
