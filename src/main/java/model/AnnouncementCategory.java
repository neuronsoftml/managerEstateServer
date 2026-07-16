package model;
/**
 * Категорії пошуку на OLX.
 * Кожна категорія містить: сегмент URL + людська назва для логів.
 *
 * URL будується як:
 * https://www.olx.ua/uk/nedvizhimost/kvartiry/{urlSegment}/{citySlug}/
 */

public enum AnnouncementCategory {
    RENT_LONG        ("nedvizhimost/kvartiry/dolgosrochnaya-arenda-kvartir",                    "Оренда (довгострокова)"),
    RENT_SHORT       ("zhytlo-podobovo/podobovo-pohodynno-kvartyry",                             "Оренда (подобова)"),
    RENT_HOUSE       ("nedvizhimost/doma/arenda-domov",                                          "Оренда (Будинку)"),
    RENT_COMMERCIAL  ("nedvizhimost/kommercheskaya-nedvizhimost/arenda-kommercheskoy-nedvizhimosti", "Оренда (Комерційної нерухомості)"),
    SALE_APARTMENTS  ("nedvizhimost/kvartiry/prodazha-kvartir",                                  "Продаж (квартир)"),
    SALE_HOUSE       ("nedvizhimost/doma/prodazha-domov",                                        "Продаж (Будинку)"),
    SALE_COMMERCIAL  ("nedvizhimost/kommercheskaya-nedvizhimost/prodazha-kommercheskoy-nedvizhimosti", "Продаж (Комерційної нерухомості)"),
    SALE_LAND_PARCEL ("nedvizhimost/zemlya/prodazha-zemli",                                      "Продаж (Земельної ділянки)");


    private final String urlSegment;
    private final String label;

    AnnouncementCategory(String urlSegment, String label) {
        this.urlSegment = urlSegment;
        this.label      = label;
    }

    public String getUrlSegment() { return urlSegment; }
    public String getLabel()      { return label; }

    // Статичний метод для пошуку категорії в тексті
    public static AnnouncementCategory findByRawString(String rawCategory) {
        if (rawCategory == null) return null;

        for (AnnouncementCategory cat : AnnouncementCategory.values()) {
            if (rawCategory.contains(cat.getLabel())) {
                return cat;
            }
        }
        return null;
    }
}