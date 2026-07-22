package model;

/**
 * Перерахування (Enum) типу ринку нерухомості.
 * <p>
 * Уточнює {@link PropertyType}: чи об'єкт розташований у новобудові,
 * чи належить до вторинного ринку. Крок обирається користувачем одразу
 * після вибору типу нерухомості в майстрі {@code CreatePostWizardController},
 * незалежно від типу угоди ({@link DealType#BUY} чи {@link DealType#RENT}).
 * </p>
 *
 * @author Mykola
 */
public enum PropertyMarketType {

    /**
     * Новобудова (в тому числі на етапі будівництва).
     */
    NEW_BUILDING("Новобудова"),

    /**
     * Вторинний ринок (житло, що вже було у власності).
     */
    SECONDARY("Вторинний ринок");

    /**
     * Людська мітка типу ринку для кнопок і резюме анкети.
     */
    private final String label;

    PropertyMarketType(String label) {
        this.label = label;
    }

    /**
     * @return текстова назва типу ринку українською мовою
     */
    public String getLabel() {
        return label;
    }
}
