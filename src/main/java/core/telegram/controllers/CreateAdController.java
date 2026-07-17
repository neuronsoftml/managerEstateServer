package core.telegram.controllers;

import core.telegram.main.BotMessage;
import core.telegram.main.InlineKeyboardFactory;
import core.telegram.model.UserSession;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.PrintStream;
import java.util.LinkedHashMap;


/**
 * Контролер бізнес-процесу "Створити оголошення (здати квартиру)".
 * <p>
 * Наразі реалізує лише заглушку-повідомлення ("в розробці") з кнопкою повернення
 * в головне меню — так само, як це раніше робив приватний метод
 * {@code handlerFutureCreateAd} у {@code PrivateMainBot}. Коли з'явиться повний
 * сценарій публікації оголошення власником, його крокова логіка додається сюди
 * так само, як {@link SearchAdsController} реалізує воронку пошуку.
 * </p>
 */
public class CreateAdController implements BotController {

    private final TelegramSender sender;

    public CreateAdController(TelegramSender sender) {
        this.sender = sender;
    }

    @Override
    public void handle(Update update, UserSession session, PrintStream botOut) {
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        int messageId = update.getCallbackQuery().getMessage().getMessageId();

        EditMessageText message = BotMessage.crateFutureCreateAd(chatId, messageId);
        // Кнопка повернення назад в головне меню
        message.setReplyMarkup(InlineKeyboardFactory.createVertical(new LinkedHashMap<>(), "⬅️ Назад в меню", "BACK_TO_MENU"));

        try {
            sender.executeMethod(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}