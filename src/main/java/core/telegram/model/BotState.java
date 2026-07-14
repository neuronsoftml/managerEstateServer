package core.telegram.model;

public enum BotState {
    START,
    WAITING_FOR_CITY,
    WAITING_FOR_CATEGORY,
    SHOWING_RESULTS,
    WAITING_FOR_ROOMS,  // Бот чекає, поки користувач обере 1, 2 або 3 кімнати
    WAITING_FOR_PRICE, // Бот чекає на вибір цінового діапазону в $
    MAIN_MENU,

    // ── Секція опитування анкети пошуку житла (Wizard) ─────────────────────
    PROFILE_WAITING_NAME,
    PROFILE_WAITING_PHONE,
    PROFILE_WAITING_BUDGET,
    PROFILE_WAITING_DEPOSIT,       // Фінансовий крок 1: Застава власнику
    PROFILE_WAITING_COMMISSION,    // Фінансовий крок 2: Комісія ріелтору
    PROFILE_WAITING_DISTRICTS,
    PROFILE_WAITING_ROOMS,
    PROFILE_WAITING_TERM,
    PROFILE_WAITING_TENANTS,
    PROFILE_WAITING_CHILDREN,
    PROFILE_WAITING_PETS,
    PROFILE_WAITING_EMPLOYMENT,
    PROFILE_WAITING_WORK_FORMAT,
    PROFILE_WAITING_SMOKING,
    PROFILE_WAITING_CAR,
    PROFILE_WAITING_CRITICAL,
    PROFILE_CONFIRMATION
}
