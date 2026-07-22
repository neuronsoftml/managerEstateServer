package model;

/**
 * Перерахування (Enum) типів продавця/орендодавця для оголошення,
 * яке користувач створює самостійно через {@code CreatePostWizardController}.
 *
 * @author Mykola
 */
public enum SellerType {

    /** Приватна особа (власник житла). */
    PRIVATE("Приватна особа"),

    /** Бізнес / рієлторська компанія / агентство нерухомості. */
    BUSINESS("Бізнес / Агентство");

    /** Людська мітка типу продавця для кнопок і відображення в резюме анкети. */
    private final String label;

    SellerType(String label) {
        this.label = label;
    }

    /**
     * @return текстова назва типу продавця українською мовою
     */
    public String getLabel() {
        return label;
    }
}
