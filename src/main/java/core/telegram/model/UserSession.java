package core.telegram.model;

import model.*;

import java.util.LinkedHashSet;
import java.util.Set;


/**
 * Клас-контейнер для збереження поточної сесії користувача в Telegram-боті.
 * <p>
 * Дозволяє боту зберігати стан діалогу (FSM), налаштовані фільтри пошуку нерухомості
 * (місто, категорія, ціновий діапазон, кількість кімнат), а також проміжний стан
 * заповнення інтерактивної анкети пошуку житла (Wizard-опитувальника).
 * </p>
 * <p>
 * Сесія є тимчасовою та зазвичай зберігається у потокобезпечній мапі (наприклад, {@code ConcurrentHashMap})
 * у пам'яті сервера, де ключем виступає унікальний Telegram {@code chatId} користувача.
 * </p>
 * * @author Mykola
 */
public class UserSession {

    /** Поточний стан діалогу користувача (скінченний автомат) */
    private BotState state;

    /** Обране місто для пошуку нерухомості */
    private City selectedCity;

    /** Обрана категорія оголошення на OLX */
    private AnnouncementCategory selectedCategory;

    /** Поточний зсув (індекс пагінації) для відображення списку оголошень порціями (наприклад, 0, 3, 6...) */
    private int currentOffset = 0;

    // ── Додаткові фільтри пошуку ─────────────────────────────────────────────

    /** Обрана кількість кімнат: {@code null} — будь-яка кількість, або точне значення (1, 2, 3) */
    private Integer selectedRooms;

    /** * Прапорець логіки вибору кімнат:
     * {@code true} — якщо вибраний критерій означає "від N і більше" (наприклад, для опції "3+ кімнати"),
     * {@code false} — для точного збігу за кількістю кімнат.
     */
    private boolean roomsIsMinimum = false;

    /** Мінімальна ціна бажаного діапазону в доларах ($) */
    private Integer minPriceUsd;

    /** Максимальна ціна бажаного діапазону в доларах ($) */
    private Integer maxPriceUsd;

    // ── Покращена навігація пошуку ───────────────────────────────────────────

    /** Обраний тип угоди (Оренда чи Купівля) */
    private DealType selectedDealType;

    /** Обраний тип об'єкта нерухомості (Квартира, Будинок, Комерція, Ділянка) */
    private PropertyType selectedPropertyType;

    // ── Секція Wizard-опитувальника "Анкета пошуку житла" ─────────────────────

    /** Чернетка анкети, яка крок за кроком наповнюється під час проходження опитування */
    private TenantApplicationForm profileDraft = new TenantApplicationForm();

    /** * Набір обраних районів міста (мультиселект).
     * Використовується {@link LinkedHashSet} для збереження порядку вибору без дублікатів.
     * Накопичується доти, доки користувач не натисне кнопку "Зберегти вибір".
     */
    private Set<String> selectedDistricts = new LinkedHashSet<>();

    /** ID останнього надісланого ботом повідомлення з інтерактивним екраном анкети (для inline-редагування) */
    private int wizardMessageId;

    /**
     * Статус очікування текстового уточнення "вік/кількість дітей".
     * Стає {@code true}, якщо користувач натиснув кнопку "Так" на кроці {@code PROFILE_WAITING_CHILDREN}.
     */
    private boolean awaitingChildrenDetails = false;

    /**
     * Статус очікування текстового уточнення деталей про тварин.
     * Стає {@code true}, якщо користувач натиснув кнопку "Так" на кроці {@code PROFILE_WAITING_PETS}.
     */
    private boolean awaitingPetsDetails = false;

    /**
     * Статус режиму швидкого редагування анкети.
     * {@code true} — якщо користувач повернувся до цього кроку з фінального екрану підтвердження даних.
     * Після відповіді на це питання бот одразу перенаправляє користувача назад на перевірку анкети.
     */
    private boolean profileEditMode = false;

    // ── Секція Wizard-майстра "Створити оголошення" ──────────────────────────

    /** Чернетка оголошення, що наповнюється крок за кроком через {@code CreatePostWizardController}. */
    private CreatePostDraft createPostDraft = new CreatePostDraft();

    /**
     * Статус режиму швидкого редагування анкети створення оголошення.
     * {@code true} — якщо користувач повернувся на конкретний крок з екрану резюме
     * (натиснувши "Редагувати"). Після відповіді бот одразу повертає користувача
     * назад на екран резюме, а не продовжує звичайну послідовність кроків.
     */
    private boolean createPostEditMode = false;

    /** ID останнього надісланого ботом повідомлення майстра створення оголошення (для inline-редагування). */
    private int createPostMessageId;


    private int lastTemporaryMessageId;

    private boolean isEditMenuOpen = false;

    /**
     * Конструктор для ініціалізації нової сесії з певним стартовим станом.
     *
     * @param state початковий стан сесії користувача
     */
    public UserSession(BotState state) {
        this.state = state;
    }

    // ── Геттери та Сеттери для базових полів ──────────────────────────────────

    public BotState getState() { return state; }
    public void setState(BotState state) { this.state = state; }

    public City getSelectedCity() { return selectedCity; }
    public void setSelectedCity(City selectedCity) { this.selectedCity = selectedCity; }

    public AnnouncementCategory getSelectedCategory() { return selectedCategory; }
    public void setSelectedCategory(AnnouncementCategory selectedCategory) { this.selectedCategory = selectedCategory; }

    public int getCurrentOffset() { return currentOffset; }
    public void setCurrentOffset(int currentOffset) { this.currentOffset = currentOffset; }

    // ── Геттери та Сеттери для додаткових фільтрів кімнат та цін ──────────────

    public Integer getSelectedRooms() { return selectedRooms; }
    public void setSelectedRooms(Integer selectedRooms) { this.selectedRooms = selectedRooms; }

    public boolean isRoomsIsMinimum() { return roomsIsMinimum; }
    public void setRoomsIsMinimum(boolean roomsIsMinimum) { this.roomsIsMinimum = roomsIsMinimum; }

    public DealType getSelectedDealType() { return selectedDealType; }
    public void setSelectedDealType(DealType selectedDealType) { this.selectedDealType = selectedDealType; }

    public PropertyType getSelectedPropertyType() { return selectedPropertyType; }
    public void setSelectedPropertyType(PropertyType selectedPropertyType) { this.selectedPropertyType = selectedPropertyType; }

    public Integer getMinPriceUsd() { return minPriceUsd; }
    public Integer getMaxPriceUsd() { return maxPriceUsd; }

    /**
     * Допоміжний метод для швидкого встановлення меж цінового діапазону в доларах одним викликом.
     *
     * @param minPriceUsd нижня границя бюджету
     * @param maxPriceUsd верхня границя бюджету
     */
    public void setPriceRangeUsd(Integer minPriceUsd, Integer maxPriceUsd) {
        this.minPriceUsd = minPriceUsd;
        this.maxPriceUsd = maxPriceUsd;
    }

    // ── Геттери та Сеттери для Wizard-опитувальника ──────────────────────────

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

    // ── Геттери та Сеттери для Wizard-майстра "Створити оголошення" ──────────

    public CreatePostDraft getCreatePostDraft() { return createPostDraft; }
    public void setCreatePostDraft(CreatePostDraft createPostDraft) { this.createPostDraft = createPostDraft; }

    public boolean isCreatePostEditMode() { return createPostEditMode; }
    public void setCreatePostEditMode(boolean createPostEditMode) { this.createPostEditMode = createPostEditMode; }

    public int getCreatePostMessageId() { return createPostMessageId; }
    public void setCreatePostMessageId(int createPostMessageId) { this.createPostMessageId = createPostMessageId; }
    public int getLastTemporaryMessageId() { return lastTemporaryMessageId; }
    public void setLastTemporaryMessageId(int lastTemporaryMessageId) { this.lastTemporaryMessageId = lastTemporaryMessageId; }

    public boolean isEditMenuOpen() {
        return isEditMenuOpen;
    }

    public void setEditMenuOpen(boolean editMenuOpen) {
        isEditMenuOpen = editMenuOpen;
    }
}