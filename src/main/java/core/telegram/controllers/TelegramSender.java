package core.telegram.controllers;

import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.Serializable;


/**
 * Вузький інтерфейс для виконання Telegram API запитів, який контролери отримують
 * замість конкретного {@code TelegramLongPollingBot}.
 * <p>
 * Два перевантаження, а не одне, тому що в бібліотеці telegrambots не всі методи
 * наслідують {@link BotApiMethod}: {@link SendMediaGroup} повертає список повідомлень
 * (List&lt;Message&gt;) і має власну ієрархію ({@code BotApiMethodMessages}), тож
 * {@code DefaultAbsSender} у самій бібліотеці теж оголошує для нього окремий
 * перевантажений {@code execute(...)}. Тут — той самий підхід.
 * </p>
 * <p><b>ВАЖЛИВО (зміна порівняно з попередньою версією):</b> {@code executeMethod} тепер
 * <b>дженерик і повертає {@code T}</b> замість {@code void} — так само, як реальний
 * {@code AbsSender.execute(...)} у бібліотеці telegrambots. Це необхідно контролерам
 * (зокрема {@code CreatePostWizardController}), щоб отримати {@code message_id}
 * щойно надісланого повідомлення й зберегти його в {@code UserSession} для подальшого
 * редагування того самого повідомлення (динамічне управління UI, п.2 ТЗ).
 * </p>
 * <p><b>Дія, потрібна від вас:</b> у класі, що реалізує цей інтерфейс (той, що обгортає
 * {@code TelegramLongPollingBot}/{@code AbsSender}), змініть реалізацію з
 * {@code execute(method);} на {@code return execute(method);} — сама бібліотека
 * вже повертає {@code T} з коробки, тож зміна тривіальна.</p>
 */
public interface TelegramSender {

    /**
     * Виконує будь-який метод Telegram Bot API, що успадковує {@link BotApiMethod},
     * і повертає результат виклику (наприклад, {@link org.telegram.telegrambots.meta.api.objects.Message}
     * для {@code SendMessage}/{@code EditMessageText}).
     */
    <T extends Serializable> T executeMethod(BotApiMethod<T> method) throws TelegramApiException;

    /** Окреме перевантаження для SendMediaGroup — див. Javadoc інтерфейсу. */
    void executeMethod(SendMediaGroup method) throws TelegramApiException;

    void deleteMessage(long chatId, int messageId) throws TelegramApiException;

    /** Знімає inline-клавіатуру, залишаючи текст повідомлення як історію діалогу. */
    default void clearInlineKeyboard(long chatId, int messageId) throws TelegramApiException {
        if (messageId == 0) return;
        EditMessageReplyMarkup clear = new EditMessageReplyMarkup();
        clear.setChatId(String.valueOf(chatId));
        clear.setMessageId(messageId);
        clear.setReplyMarkup(null);
        executeMethod(clear);
    }
}
