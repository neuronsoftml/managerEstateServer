package core.telegram.controllers;

import core.telegram.model.BotState;
import core.telegram.model.UserSession;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.PrintStream;

/**
 * Контролер бізнес-процесу "Створити анкету (шукаю житло)".
 * <p>
 * Інкапсулює {@link ProfileWizardController} — покроковий wizard-опитувальник анкети
 * орендаря. Сам {@code ProfileWizardController} лишається окремим класом (не зливається
 * в цей файл), бо це самодостатній, вже добре ізольований FSM зі своєю великою кроковою
 * логікою; {@code CreateProfileController} є для нього лише "вхідними дверима" в
 * загальній маршрутизації — так само, як {@link SearchAdsController} є дверима для
 * воронки пошуку.
 * </p>
 * <p>
 * Обидва контролери тепер отримують лише вузький інтерфейс {@link TelegramSender},
 * тому {@code PrivateMainBot} (маршрутизатор) може створювати їх однаково і не
 * зобов'язаний передавати їм себе як конкретний {@code TelegramLongPollingBot}.
 * </p>
 */
public class CreateProfileController implements BotController {

    private final TelegramSender sender;

    private final ProfileWizardController profileWizard;

    public CreateProfileController(TelegramSender sender) {
        this.sender = sender;
        this.profileWizard = new ProfileWizardController(sender);
    }

    @Override
    public void handle(Update update, UserSession session, PrintStream botOut) {
        try {
            long chatId = update.getCallbackQuery().getMessage().getChatId();
            int messageId = update.getCallbackQuery().getMessage().getMessageId();
            String data = update.getCallbackQuery().getData();

            if (data.equals("MENU_CREATE_PROFILE")) {
                profileWizard.startWizard(chatId, messageId, session);
                return;
            }

            if (isWizardCallback(data)) {
                profileWizard.handleCallback(chatId, messageId, data, session);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Обробляє вільний текст, введений користувачем під час проходження wizard-опитувальника
     * (наприклад, ім'я, коментар тощо). Викликається маршрутизатором окремо від
     * {@link #handle}, бо текстові повідомлення приходять іншою гілкою {@code onUpdateReceived},
     * а не через {@code CallbackQuery}.
     *
     * @return true, якщо повідомлення користувача було "спожите" wizard'ом
     * (і його можна безпечно видалити з чату для охайності)
     */
    public boolean handleMessage(Update update, UserSession session) throws Exception {
        long chatId = update.getMessage().getChatId();
        if (update.getMessage().hasContact()) {
            return profileWizard.handleContact(chatId, update.getMessage().getContact().getPhoneNumber(), session);
        }
        return update.getMessage().hasText() && profileWizard.handleText(chatId, update.getMessage().getText(), session);
    }

    /** Чи перебуває сесія користувача всередині кроків wizard-опитувальника анкети. */
    public static boolean isWizardState(BotState state) {
        return ProfileWizardController.isWizardState(state);
    }

    /** Чи стосуються ці callback-дані wizard-опитувальника анкети. */
    public static boolean isWizardCallback(String data) {
        return ProfileWizardController.isWizardCallback(data);
    }
}
