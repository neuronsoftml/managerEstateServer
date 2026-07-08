package model;


/**
 * Категорії пошуку на OLX.
 * Кожна категорія містить: сегмент URL + людська назва для логів.
 *
 * URL будується як:
 * https://www.olx.ua/uk/nedvizhimost/kvartiry/{urlSegment}/{citySlug}/
 */

public enum CategoryLocation {
    RENT_LONG   ("dolgosrochnaya-arenda-kvartir", "Оренда (довгострокова)"),
    RENT_SHORT  ("posutochno-pochasovo",           "Оренда (подобова)"),
    SALE        ("prodazha-kvartir",               "Продаж");

    private final String urlSegment;
    private final String label;

    CategoryLocation(String urlSegment, String label) {
        this.urlSegment = urlSegment;
        this.label      = label;
    }

    public String getUrlSegment() { return urlSegment; }
    public String getLabel()      { return label; }

    // Статичний метод для пошуку категорії в тексті
    public static CategoryLocation findByRawString(String rawCategory) {
        if (rawCategory == null) return null;

        for (CategoryLocation cat : CategoryLocation.values()) {
            if (rawCategory.contains(cat.getLabel())) {
                return cat;
            }
        }
        return null;
    }
}
