package model.telegram;

public enum BotState {
    START,
    WAITING_FOR_CITY,
    WAITING_FOR_CATEGORY,
    SHOWING_RESULTS,
    WAITING_FOR_ROOMS,  // Бот чекає, поки користувач обере 1, 2 або 3 кімнати
    WAITING_FOR_PRICE // Бот чекає на вибір цінового діапазону в $
}
