package core.telegram.controllers;

import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

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
 */
public interface TelegramSender {
    void executeMethod(BotApiMethod<?> method) throws TelegramApiException;

    /** Окреме перевантаження для SendMediaGroup — див. Javadoc інтерфейсу. */
    void executeMethod(SendMediaGroup method) throws TelegramApiException;
}
