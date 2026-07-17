package core.telegram.controllers;

import core.telegram.main.InlineKeyboardFactory;
import core.telegram.model.BotState;
import core.telegram.model.UserSession;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongConsumer;


/**
 * Контролер загальної навігації бота: повернення в головне меню ("BACK_TO_MENU")
 * та повний вихід з поточного сценарію ("EXIT_BOT").
 * <p>
 * Свідомо не володіє жодною бізнес-логікою фільтрів чи анкет — лише малює
 * корінний екран і скидає стан сесії. Саме тому ці дві кнопки обробляються
 * централізовано тут, незалежно від того, з якого бізнес-процесу вони натиснуті.
 * </p>
 */
public class NavigationController implements BotController {

    private final TelegramSender sender;

    /**
     * Колбек для фізичного видалення сесії користувача з мапи бота при виході.
     * Переданий ззовні (з {@code PrivateMainBot}), оскільки мапа сесій належить
     * маршрутизатору, а не контролерам.
     */
    private final LongConsumer sessionRemover;

    public NavigationController(TelegramSender sender, LongConsumer sessionRemover) {
        this.sender = sender;
        this.sessionRemover = sessionRemover;
    }

    @Override
    public void handle(Update update, UserSession session, PrintStream botOut) {
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        int messageId = update.getCallbackQuery().getMessage().getMessageId();
        String data = update.getCallbackQuery().getData();

        try {
            if (data.equals("BACK_TO_MENU")) {
                session.setState(BotState.START);
                deleteMessage(chatId, messageId);
                sendMainMenu(chatId);
            } else if (data.equals("EXIT_BOT")) {
                // 1. Прибираємо inline-кнопки під останнім повідомленням
                clearMarkup(chatId, messageId);

                // 2. Повертаємо стан сесії на початковий рівень
                session.setState(BotState.START);

                // 3. Фізично видаляємо користувача з мапи активних сесій бота
                sessionRemover.accept(chatId);

                // 4. Інформуємо про завершення сеансу
                SendMessage exitMsg = new SendMessage();
                exitMsg.setChatId(String.valueOf(chatId));
                exitMsg.setText("🚪 Пошук завершено. Напишіть /start для нового запиту.");
                sender.executeMethod(exitMsg);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Надсилає користувачу головне меню вибору функцій бота.
     * Публічний, тому що маршрутизатор також викликає цей екран напряму при /start.
     */
    public void sendMainMenu(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setParseMode("HTML");
        message.setText("📋 <b>Головне меню</b>\n\nОберіть необхідну функцію для продовження:");

        Map<String, String> mainMenuButtons = new LinkedHashMap<>();
        mainMenuButtons.put("🔍 Пошук нерухомості", "MENU_SEARCH_ADS");
        mainMenuButtons.put("🏗 Створити оголошення \n (здати квартиру)", "MENU_CREATE_AD");
        mainMenuButtons.put("📝 Створити анкету \n (шукаю житло в оренду)", "MENU_CREATE_PROFILE");

        message.setReplyMarkup(InlineKeyboardFactory.createVertical(mainMenuButtons, null, null));

        try {
            sender.executeMethod(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void deleteMessage(long chatId, int messageId) {
        try {
            DeleteMessage deleteMessage = new DeleteMessage();
            deleteMessage.setChatId(String.valueOf(chatId));
            deleteMessage.setMessageId(messageId);
            sender.executeMethod(deleteMessage);
        } catch (Exception ignored) {
            // Іноді повідомлення не вдається видалити (наприклад, старше за 48 годин) — ігноруємо.
        }
    }

    private void clearMarkup(long chatId, int messageId) {
        try {
            EditMessageReplyMarkup clearMarkup = new EditMessageReplyMarkup();
            clearMarkup.setChatId(String.valueOf(chatId));
            clearMarkup.setMessageId(messageId);
            clearMarkup.setReplyMarkup(null);
            sender.executeMethod(clearMarkup);
        } catch (Exception ignored) {}
    }
}

