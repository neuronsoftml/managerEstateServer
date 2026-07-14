package core.telegram.main;

import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;

public class BotMessage {

    /**
     * Формує повідомлення з попередженням про обов'язкову підписку на Telegram-групу.
     * Використовується для блокування інтерфейсу користувача до моменту підписки.
     *
     * @param chatId унікальний ідентифікатор чату або користувача в Telegram
     * @return об'єкт {@link SendMessage} з HTML-текстом попередження (без кнопок)
     */
    public static SendMessage createSubscriptionWarning(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setParseMode("HTML");
        message.setText("❌ <b>Доступ обмежено!</b>\n\n" +
                "Щоб користуватися цим ботом для пошуку нерухомості, ви повинні бути підписані на нашу групу. @cv_home \n\n" +
                "👉 Будь ласка, підпишіться та натисніть кнопку нижче, щоб продовжити.");
        return message;
    }

    /**
     * Формує інформаційне повідомлення-заглушку для режиму створення анкет пошуку житла.
     * Використовується як тимчасовий екран для модуля, що перебуває в розробці.
     *
     * @param chatId унікальний ідентифікатор чату або користувача в Telegram
     * @return об'єкт {@link SendMessage} з HTML-текстом про майбутній функціонал анкет
     */
    public static EditMessageText createFutureCreateProfile(long chatId, int messageId) {
        EditMessageText message = new EditMessageText();
        message.setChatId(String.valueOf(chatId));
        message.setMessageId(messageId); // Редагуємо саме це повідомлення
        message.setParseMode("HTML");
        message.setText("📝 <b>Режим створення анкет пошуку</b>\n\n" +
                "Цей модуль зараз перебуває в розробці.\n" +
                "У майбутньому ви зможете залишити заявку на пошук житла, щоб ріелтори або власники могли автоматично пропонувати вам варіанти.");
        return message;
    }

    /**
     * Формує інформаційне повідомлення-заглушку для режиму створення оголошень нерухомості.
     * Повертає об'єкт {@link EditMessageText} для плавного редагування поточного вікна чату,
     * сповіщаючи користувача, що даний інтерактивний модуль перебуває в процесі розробки.
     *
     * @param chatId    унікальний ідентифікатор чату або користувача в Telegram
     * @param messageId унікальний ідентифікатор повідомлення, яке необхідно відредагувати
     * @return об'єкт {@link EditMessageText} з HTML-форматованим текстом про майбутній функціонал форми
     */
    public static EditMessageText crateFutureCreateAd(long chatId, int messageId) {
        EditMessageText message = new EditMessageText();
        message.setChatId(String.valueOf(chatId));
        message.setMessageId(messageId); // Редагуємо саме це повідомлення
        message.setParseMode("HTML");
        message.setText("🏗 <b>Режим створення оголошення</b>\n\n" +
                "Цей модуль зараз перебуває в розробці.\n" +
                "Незабаром тут буде покрокова форма для внесення даних вашої квартири (фото, ціна, опис) та збереження її в базу даних.");

        return message;
    }
}
