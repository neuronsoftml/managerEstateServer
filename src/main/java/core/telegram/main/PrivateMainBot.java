package core.telegram.main;

import core.telegram.controllers.*;
import core.telegram.model.BotState;
import core.telegram.model.Config;

import core.telegram.model.UserSession;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.PrintStream;
import java.util.*;


/**
 * Телеграм-бот для приватного покрокового пошуку нерухомості з фільтрами.
 * Реалізований за принципом кінцевого автомата (State Machine), де стан кожного
 * користувача зберігається в об'єкті UserSession всередині мапи userSessions.
 * <p>
 * Після рефакторингу клас є <b>тонким маршрутизатором</b>: він більше не малює
 * екрани і не містить бізнес-логіки жодного зі сценаріїв. Уся ця логіка винесена
 * у спеціалізовані контролери (пакет {@code core.telegram.controllers}), кожен з
 * яких відповідає за один бізнес-процес і нічого не знає про інші. Бот лише:
 * <ul>
 *     <li>приймає сирі оновлення від Telegram API;</li>
 *     <li>виконує наскрізні перевірки (підписка на групу — "gatekeeper");</li>
 *     <li>визначає, якому контролеру передати керування (Router).</li>
 * </ul>
 */
public class PrivateMainBot extends TelegramLongPollingBot implements TelegramSender {


    /** Мапа для збереження індивідуальних сесій користувачів (ChatID -> Сесія) */
    private final Map<Long, UserSession> userSessions = new HashMap<>();

    /** Контролер загальної навігації ("Назад в меню", "Вихід"). */
    private final NavigationController navigationController = new NavigationController(this, userSessions::remove);

    /** Контролер бізнес-процесу "Пошук нерухомості". */
    private final SearchAdsController searchAdsController = new SearchAdsController(this);

    /** Контролер бізнес-процесу "Створити оголошення". */
    private final CreateAdController createAdController = new CreateAdController(this);

    /** Контролер бізнес-процесу "Створити анкету" (інкапсулює wizard-опитувальник анкети). */
    private final CreateProfileController createProfileController = new CreateProfileController(this);


    /** Потік виведення для логування, налагодження або перенаправлення системних повідомлень бота. */
    private PrintStream botOut;

    /**
     * Конструктор бота з можливістю впровадження (ін'єкції) кастомного потоку виведення.
     * Використовується для гнучкого керування логами (наприклад, виведення в консоль, файл або кастомну панель).
     *
     * @param botOut потік для відправки технічних та налагоджувальних даних бота
     */
    public PrivateMainBot(PrintStream botOut) {
        this.botOut = botOut;
    }

    @Override
    public void executeMethod(BotApiMethod<?> method) throws TelegramApiException {
        execute(method);
    }

    @Override
    public void executeMethod(org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup method) throws TelegramApiException {
        execute(method);
    }

    @Override
    public String getBotUsername() { return Config.NAME.getKey(); }

    @Override
    public String getBotToken() { return Config.TOKEN.getKey(); }

    /**
     * Головна точка входу для всіх подій (оновлень), що надходять від Telegram.
     * Обробяє як текстові повідомлення/команди, так і натискання інлайн-кнопок.
     */
    @Override
    public void onUpdateReceived(Update update) {
        // === I. ПЕРЕХОПЛЕННЯ АВТОМАТИЧНОГО ФОРВАРДУ ДЛЯ КОМЕНТАРІВ ===
        // Перевіряємо повідомлення як у звичайних повідомленнях, так і в channel_posts
        org.telegram.telegrambots.meta.api.objects.Message msg = null;
        if (update.hasMessage()) {
            msg = update.getMessage();
        } else if (update.hasChannelPost()) {
            msg = update.getChannelPost();
        }

        if (msg != null) {
            // Перевіряємо автоматичний репост із каналу
            if (msg.getForwardFromChat() != null && msg.getForwardFromChat().isChannelChat()) {
                Integer channelMessageId = msg.getForwardFromMessageId();
                Integer discussionMessageId = msg.getMessageId();

                if (channelMessageId != null && discussionMessageId != null) {
                    ChannelDiscussionRegistry.registerMapping(channelMessageId, discussionMessageId);
                    botOut.println("✅ Зв'язок створено через БОТА: Пост " + channelMessageId + " -> Дискусія " + discussionMessageId);
                    return; // Зупиняємо подальшу логіку
                }
            }
        }

        // 1. Обробка текстових повідомлень від користувача
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            // ГОЛОВНИЙ ФІЛЬТР: Перевіряємо підписку користувача
            if (!isUserSubscribed(chatId)) {
                sendSubscriptionWarning(chatId);
                return; // Зупиняємо виконання, бот ігнорує будь-які команди, поки немає підписки
            }

            if (text.equals("/start")) {
                startWorkflow(chatId);
                return;
            }

            // Маршрутизація вільного тексту до wizard-опитувальника анкети
            UserSession textSession = userSessions.get(chatId);
            if (textSession != null && CreateProfileController.isWizardState(textSession.getState())) {
                try {
                    boolean consumed = createProfileController.handleText(chatId, text, textSession);
                    if (consumed) {
                        // Прибираємо повідомлення користувача, щоб чат лишався охайним
                        deleteMessage(chatId, update.getMessage().getMessageId());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        // 2. Обробка натискань на кнопки (CallbackQuery)
        else if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();
            long chatId = update.getCallbackQuery().getMessage().getChatId();
            int messageId = update.getCallbackQuery().getMessage().getMessageId();

            // Спеціальний випадок: обробка кнопки перевірки підписки
            if (callbackData.equals("CHECK_SUB")) {
                clearMarkup(chatId, messageId); // видаляємо старе попередження
                if (isUserSubscribed(chatId)) {
                    // Якщо тепер підписаний — вітаємо та запускаємо пошук спочатку
                    SendMessage successMsg = new SendMessage();
                    successMsg.setChatId(String.valueOf(chatId));
                    successMsg.setText("✅ Дякуємо за підписку! Доступ активовано.");
                    try { execute(successMsg); } catch (Exception ignored) {}

                    startWorkflow(chatId);
                } else {
                    // Якщо досі не підписався — знову б'ємо по руках
                    sendSubscriptionWarning(chatId);
                }
                return;
            }

            // ЗАХИСТ: Якщо користувач клацає на кнопки СТАРИХ результатів пошуку, але відписався
            if (!isUserSubscribed(chatId)) {
                clearMarkup(chatId, messageId);
                sendSubscriptionWarning(chatId);
                return;
            }

            // Отримуємо існуючу сесію або створюємо нову, якщо користувач пише вперше
            UserSession session = userSessions.computeIfAbsent(chatId, k -> new UserSession(BotState.START));

            handleCallback(update, chatId, messageId, callbackData, session);
        }
    }

    /**
     * Ініціалізує робочий процес для користувача: скидає/створює сесію
     * у стан очікування району та відправляє перше меню вибору міст.
     */
    private void startWorkflow(long chatId) {
        // Змінюємо початковий стан на роботу з Головним Меню
        UserSession session = new UserSession(BotState.MAIN_MENU);
        userSessions.put(chatId, session);
        navigationController.sendMainMenu(chatId);
    }

    // ====================================================
    // --- МАРШРУТИЗАЦІЯ CALLBACK-ЗАПИТІВ (ROUTER) ---
    // ====================================================

    /**
     * Головний диспетчер натискань кнопок. Аналізує callback-дані та передає
     * керування відповідному контролеру, більше не містячи власної UI-логіки.
     */
    private void handleCallback(Update update, long chatId, int messageId, String data, UserSession session) {
        try {
            // 1. Загальна навігація ("Назад в меню" / "Вихід") — не належить жодному
            // конкретному бізнес-процесу, тому обробляється централізовано.
            if (data.equals("BACK_TO_MENU") || data.equals("EXIT_BOT")) {
                navigationController.handle(update, session, botOut);
                return;
            }

            // 2. Вхід у сценарій анкети з головного меню АБО кнопки всередині її wizard'а
            if (data.equals("MENU_CREATE_PROFILE") || CreateProfileController.isWizardCallback(data)) {
                createProfileController.handle(update, session, botOut);
                return;
            }

            // 3. Інші пункти головного меню — визначають, який бізнес-процес запускається
            if (data.equals("MENU_SEARCH_ADS")) {
                searchAdsController.handle(update, session, botOut);
                return;
            }
            if (data.equals("MENU_CREATE_AD")) {
                createAdController.handle(update, session, botOut);
                return;
            }

            // 4. Все інше — кроки та кнопки "Назад" всередині воронки пошуку нерухомості
            searchAdsController.handle(update, session, botOut);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Перевіряє, чи підписаний користувач на обов'язкову групу.
     *
     * @param userId Унікальний Telegram ID користувача
     * @return true — якщо користувач є учасником, адміном або творцем групи.
     */
    private boolean isUserSubscribed(long userId) {
        String targetChatId = Config.ID_CHAT.getKey();

        GetChatMember getChatMember = new GetChatMember();
        getChatMember.setChatId(targetChatId);
        getChatMember.setUserId(userId);

        try {
            // Запитуємо статус користувача у Telegram API
            ChatMember member = execute(getChatMember);
            String status = member.getStatus();

            // Доступні статуси: "creator", "administrator", "member", "restricted", "left", "kicked"
            // Нам підходять перші три (або restricted, якщо йому просто вимкнули медіа, але він у групі)
            return status.equals("creator") || status.equals("administrator") || status.equals("member") || status.equals("restricted");

        } catch (TelegramApiException e) {
            // Якщо користувача взагалі немає в чаті, Telegram може кинути ексепшн "Bad Request: user not found"
            System.err.println("⚠ Помилка перевірки підписки (можливо юзер не в групі): " + e.getMessage());
            return false;
        }
    }

    /**
     * Надсилає повідомлення про вимогу підписатися на групу із посиланням.
     */
    private void sendSubscriptionWarning(long chatId) {
        // Створюємо розмітку кнопок
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        // Кнопка 1: Посилання на саму групу (замініть юзернейм групи на реальний, якщо він відрізняється)
        InlineKeyboardButton linkButton = new InlineKeyboardButton();
        linkButton.setText("📢 Перейти до групи");
        linkButton.setUrl("https://t.me/cv_home"); // Або пряме посилання на групу/чат

        // Кнопка 2: Перевірка підписки (відправляє звичайний callback)
        InlineKeyboardButton checkButton = new InlineKeyboardButton();
        checkButton.setText("🔄 Я підписався (Перевірити)");
        checkButton.setCallbackData("CHECK_SUB");

        keyboard.add(Collections.singletonList(linkButton));
        keyboard.add(Collections.singletonList(checkButton));

        markup.setKeyboard(keyboard);

        SendMessage sendMessage = BotMessage.createSubscriptionWarning(chatId);
        sendMessage.setReplyMarkup(markup);

        try {
            execute(sendMessage);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Допоміжний метод для очищення (видалення) inline-кнопок під повідомленням,
     * щоб користувач не міг натиснути застарілі кнопки з минулих кроків.
     */
    private void clearMarkup(long chatId, int messageId) {
        try {
            EditMessageReplyMarkup clearMarkup = new EditMessageReplyMarkup();
            clearMarkup.setChatId(String.valueOf(chatId));
            clearMarkup.setMessageId(messageId);
            clearMarkup.setReplyMarkup(null); // прибираємо розмітку кнопок
            execute(clearMarkup);
        } catch (Exception ignored) {}
    }

    /**
     * Допоміжний метод для повного видалення повідомлення з чату.
     */
    private void deleteMessage(long chatId, int messageId) {
        try {
            org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage deleteMessage =
                    new org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage();
            deleteMessage.setChatId(String.valueOf(chatId));
            deleteMessage.setMessageId(messageId);
            execute(deleteMessage);
        } catch (Exception e) {
            // Іноді повідомлення не вдається видалити (наприклад, якщо воно старше за 48 годин),
            // тому просто ігноруємо помилку, щоб бот не падав.
            botOut.println("Не вдалося видалити повідомлення: " + e.getMessage());
        }
    }
}