package core.telegram.model;

import model.CategoryLocation;
import model.City;

import java.util.LinkedHashSet;
import java.util.Set;

public class UserSession {
    private BotState state;
    private City selectedCity;
    private CategoryLocation selectedCategory;
    private int currentOffset = 0; // Для пагінації (0, 3, 6, 9...)

    // 🆕 Нові поля для додаткової фільтрації
    private Integer selectedRooms; // null - будь-яка кількість, або 1, 2, 3
    private Integer minPriceUsd;   // мінімальна ціна діапазону в $
    private Integer maxPriceUsd;   // максимальна ціна діапазону в $

    // ── 🆕 Поля для Wizard-опитувальника "Анкета пошуку житла" ────────────────

    /** Чернетка анкети, яка поступово заповнюється протягом усього wizard'а. */
    private TenantApplicationForm profileDraft = new TenantApplicationForm();

    /** Обрані райони (мультиселект) — накопичуються до натискання "Зберегти вибір". */
    private Set<String> selectedDistricts = new LinkedHashSet<>();

    /** ID останнього повідомлення бота з wizard-екраном (щоб знати, що редагувати). */
    private int wizardMessageId;

    /**
     * true — якщо зараз бот очікує текст-уточнення "вік/кількість дітей"
     * після відповіді "Так" на кроці PROFILE_WAITING_CHILDREN.
     */
    private boolean awaitingChildrenDetails = false;

    /**
     * true — якщо зараз бот очікує текст-уточнення про тварину
     * після відповіді "Так" на кроці PROFILE_WAITING_PETS.
     */
    private boolean awaitingPetsDetails = false;

    /**
     * true — якщо користувач потрапив на цей крок через "✏️ Редагувати дані"
     * з екрану підтвердження. У такому разі після відповіді на крок
     * потрібно повернутись одразу на екран підтвердження, а не йти далі по wizard'у.
     */
    private boolean profileEditMode = false;

    public UserSession(BotState state) {
        this.state = state;
    }

    // ── Геттери та Сеттери для базових полів ──────────────────────────────────

    public BotState getState() { return state; }
    public void setState(BotState state) { this.state = state; }

    public City getSelectedCity() { return selectedCity; }
    public void setSelectedCity(City selectedCity) { this.selectedCity = selectedCity; }

    public CategoryLocation getSelectedCategory() { return selectedCategory; }
    public void setSelectedCategory(CategoryLocation selectedCategory) { this.selectedCategory = selectedCategory; }

    public int getCurrentOffset() { return currentOffset; }
    public void setCurrentOffset(int currentOffset) { this.currentOffset = currentOffset; }

    // ── 🆕 Геттери та Сеттери для нових фільтрів кімнат та цін ───────────────

    public Integer getSelectedRooms() {
        return selectedRooms;
    }

    public void setSelectedRooms(Integer selectedRooms) {
        this.selectedRooms = selectedRooms;
    }

    public Integer getMinPriceUsd() {
        return minPriceUsd;
    }

    public Integer getMaxPriceUsd() {
        return maxPriceUsd;
    }

    /**
     * Зручний метод для встановлення меж доларового бюджету одним викликом.
     */
    public void setPriceRangeUsd(Integer minPriceUsd, Integer maxPriceUsd) {
        this.minPriceUsd = minPriceUsd;
        this.maxPriceUsd = maxPriceUsd;
    }

    // ── 🆕 Геттери та Сеттери для Wizard-опитувальника ────────────────────────

    public TenantApplicationForm getProfileDraft() { return profileDraft; }
    public void setProfileDraft(TenantApplicationForm profileDraft) { this.profileDraft = profileDraft; }

    public Set<String> getSelectedDistricts() { return selectedDistricts; }
    public void setSelectedDistricts(Set<String> selectedDistricts) { this.selectedDistricts = selectedDistricts; }

    public int getWizardMessageId() { return wizardMessageId; }
    public void setWizardMessageId(int wizardMessageId) { this.wizardMessageId = wizardMessageId; }

    public boolean isAwaitingChildrenDetails() { return awaitingChildrenDetails; }
    public void setAwaitingChildrenDetails(boolean awaitingChildrenDetails) { this.awaitingChildrenDetails = awaitingChildrenDetails; }

    public boolean isAwaitingPetsDetails() { return awaitingPetsDetails; }
    public void setAwaitingPetsDetails(boolean awaitingPetsDetails) { this.awaitingPetsDetails = awaitingPetsDetails; }

    public boolean isProfileEditMode() { return profileEditMode; }
    public void setProfileEditMode(boolean profileEditMode) { this.profileEditMode = profileEditMode; }
}
