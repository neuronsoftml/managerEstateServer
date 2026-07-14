package core.telegram.model;

import model.CategoryLocation;
import model.City;

public class UserSession {
    private BotState state;
    private City selectedCity;
    private CategoryLocation selectedCategory;
    private int currentOffset = 0; // Для пагінації (0, 3, 6, 9...)

    // 🆕 Нові поля для додаткової фільтрації
    private Integer selectedRooms; // null - будь-яка кількість, або 1, 2, 3
    private Integer minPriceUsd;   // мінімальна ціна діапазону в $
    private Integer maxPriceUsd;   // максимальна ціна діапазону в $

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
}
