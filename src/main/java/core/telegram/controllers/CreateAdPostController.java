package core.telegram.controllers;

import core.telegram.main.BotMessage;
import core.telegram.main.InlineKeyboardFactory;
import core.telegram.model.BotState;
import core.telegram.model.UserSession;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.PrintStream;
import java.util.LinkedHashMap;



/**
 * Контролер бізнес-процесу "Створити оголошення (продаж/оренда)".
 * <p>
 * Слугує точкою входу у воронку публікації оголошення власником: якщо сесія користувача
 * ще не перебуває в жодному з кроків майстра (наприклад, щойно натиснута кнопка
 * "Створити оголошення" в головному меню), контролер ініціалізує нову чернетку
 * та запускає {@link CreatePostWizardController}. Якщо ж сесія вже перебуває
 * в одному зі станів {@code CREATE_POST_*}, уся подальша обробка (текстові відповіді,
 * фото, натискання кнопок кроків) делегується напряму туди — так само, як
 * {@code SearchAdsController} реалізує воронку пошуку.
 * </p>
 */
public class CreateAdPostController implements BotController {

    private final CreatePostWizardController createPostWizardController;

    public CreateAdPostController(TelegramSender sender) {
        this.createPostWizardController = new CreatePostWizardController(sender);
    }

    @Override
    public void handle(Update update, UserSession session, PrintStream botOut) {
        try {
            long chatId;
            if (update.hasCallbackQuery()) {
                chatId = update.getCallbackQuery().getMessage().getChatId();
            } else if (update.hasMessage()) {
                chatId = update.getMessage().getChatId();
            } else {
                return;
            }

            if (session.getState() == null || !isCreatePostWizardState(session.getState())) {
                if (!update.hasCallbackQuery()) return;
                createPostWizardController.start(chatId, update.getCallbackQuery().getMessage().getMessageId(), session);
                return;
            }

            // Сесія вже всередині майстра — делегуємо подальшу обробку кроків туди
            createPostWizardController.handle(update, session, botOut);

        } catch (Exception e) {
            e.printStackTrace(botOut);
        }
    }

    /** Перевіряє, чи належить поточний стан сесії до кроків майстра створення оголошення. */
    public boolean isCreatePostWizardState(BotState state) {
        return state.name().startsWith("CREATE_POST_");
    }



}
