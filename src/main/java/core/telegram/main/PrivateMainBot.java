package core.telegram.main;

import controllers.telegram.ProfileWizardController;
import model.*;
import core.telegram.model.BotState;
import core.telegram.model.Config;

import core.telegram.model.UserSession;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import sqlite.ProjectDatabaseService;

import java.io.PrintStream;
import java.util.*;


/**
 * Телеграм-бот для приватного покрокового пошуку нерухомості з фільтрами.
 * Реалізований за принципом кінцевого автомата (State Machine), де стан кожного
 * користувача зберігається в об'єкті UserSession всередині мапи userSessions.
 */
public class PrivateMainBot extends TelegramLongPollingBot {

    /** Мапа для збереження індивідуальних сесій користувачів (ChatID -> Сесія) */
    private final Map<Long, UserSession> userSessions = new HashMap<>();

    /** Контролер Wizard-опитувальника анкети пошуку житла. */
    private final ProfileWizardController profileWizard = new ProfileWizardController(this);


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
            if (textSession != null && ProfileWizardController.isWizardState(textSession.getState())) {
                try {
                    boolean consumed = profileWizard.handleText(chatId, text, textSession);
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

            // Отримуємо існуючу сесію або створюємо нову, якщо користувач пише вперше (з урахуванням ConcurrentHashMap)
            UserSession session = userSessions.computeIfAbsent(chatId, k -> new UserSession(BotState.START));
            handleCallback(chatId, messageId, callbackData, session);
        }
    }

    /**
     * Ініціалізує робочий процес для користувача: скидає/створює сесію
     * у стан очікування району та відправляє перше меню вибору міст.
     */
    private void startWorkflow(long chatId) {
        // Змінюємо початковий стан на роботу з Головним Меню
        UserSession session = new UserSession(BotState.MAIN_MENU); // або BotState.MAIN_MENU залежно від вашого Enum
        userSessions.put(chatId, session);
        sendMainMenu(chatId);
    }



    // ==========================================
    // --- МЕТОДИ ВІДОБРАЖЕННЯ ЕКРАНІВ (UI) ---
    // ==========================================

    /**
     * КРОК 1: Надсилає користувачу головне меню вибору функцій бота.
     */
    private void sendMainMenu(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setParseMode("HTML");
        message.setText("📋 <b>Головне меню</b>\n\nОберіть необхідну функцію для продовження:");

        // Формуємо список кнопок для головного меню
        Map<String, String> mainMenuButtons = new LinkedHashMap<>();
        mainMenuButtons.put("🔍 Пошук нерухомості", "MENU_SEARCH_ADS");
        mainMenuButtons.put("🏗 Створити оголошення \n (здати квартиру)", "MENU_CREATE_AD");
        mainMenuButtons.put("📝 Створити анкету \n (шукаю житло в оренду)", "MENU_CREATE_PROFILE");

        // Генеруємо вертикальну клавіатуру без кнопки "Назад" (бо це корінь бота)
        message.setReplyMarkup(InlineKeyboardFactory.createVertical(mainMenuButtons, null, null));

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * КРОК 1: Тип угоди — Купівля чи Оренда.
     */
    private void editToDealTypeSelection(long chatId, int messageId) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText("🔍 Що вас цікавить?");

        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        keyboard.add(InlineKeyboardFactory.createRow(new String[][]{
                {"🛒 " + DealType.BUY.getLabel(), "DEAL_BUY"}, {"🏠 " + DealType.RENT.getLabel(), "DEAL_RENT"}
        }));
        keyboard.add(InlineKeyboardFactory.createRow(new String[][]{{"⬅️ Назад в меню", "BACK_TO_MENU"}}));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(keyboard);
        edit.setReplyMarkup(markup);

        try { execute(edit); } catch (Exception e) { e.printStackTrace(); }
    }

    /**
     * КРОК 2: Тип нерухомості.
     * Земельна ділянка доступна лише при купівлі — OLX не має окремої категорії
     * "оренда земельної ділянки", тому такий варіант просто не пропонуємо.
     */
    private void editToPropertyTypeSelection(long chatId, int messageId, DealType dealType) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText("🏘 Оберіть тип нерухомості:");

        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put(PropertyType.APARTMENT.getLabel(),  "PTYPE_APARTMENT");
        buttons.put(PropertyType.HOUSE.getLabel(),      "PTYPE_HOUSE");
        buttons.put(PropertyType.COMMERCIAL.getLabel(), "PTYPE_COMMERCIAL");
        if (dealType == DealType.BUY) {
            buttons.put(PropertyType.LAND.getLabel(), "PTYPE_LAND");
        }

        edit.setReplyMarkup(InlineKeyboardFactory.createVertical(buttons, "⬅️ Назад", "BACK_TO_DEAL_TYPE"));

        try { execute(edit); } catch (Exception e) { e.printStackTrace(); }
    }

    /**
     * КРОК 3: Надсилає користувачу нове повідомлення зі списком доступних районів (міст).
     */
    private void editToCitySelection(long chatId, int messageId) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText("👋 Чудово! Оберіть район для пошуку нерухомості:");

        Map<String, String> cityButtons = new LinkedHashMap<>();
        for (City city : City.values()) {
            cityButtons.put(city.getLabel(), "CITY_" + city.name());
        }

        // "Назад" тепер веде до вибору типу нерухомості, а не одразу в меню
        edit.setReplyMarkup(InlineKeyboardFactory.createVertical(cityButtons, "⬅️ Назад", "BACK_TO_PROPERTY_TYPE"));

        try {
            execute(edit);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * КРОК 4 (лише для квартир): Редагує повідомлення для вибору кількості кімнат.
     */
    private void editToRoomsSelection(long chatId, int messageId) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText("📊 Скільки кімнат вас цікавить?");

        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        keyboard.add(InlineKeyboardFactory.createRow(new String[][]{
                {"1-кімнатна", "ROOMS_1"}, {"2-кімнатна", "ROOMS_2"}
        }));
        keyboard.add(InlineKeyboardFactory.createRow(new String[][]{
                {"3-кімнатна", "ROOMS_3"}, {"3+ кімнат", "ROOMS_3PLUS"}
        }));
        keyboard.add(InlineKeyboardFactory.createRow(new String[][]{
                {"⬅️ Назад до районів", "BACK_TO_CITY"}
        }));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(keyboard);
        edit.setReplyMarkup(markup);

        try { execute(edit); } catch (Exception e) { e.printStackTrace(); }
    }

    /**
     * КРОК 5: Редагує повідомлення для вибору цінового діапазону.
     * Діапазони підлаштовуються під тип угоди (DealType), а не під OLX-категорію.
     * Кнопка "Назад" веде або до кімнат (квартири), або одразу до міст (решта типів).
     */
    private void editToPriceSelection(long chatId, int messageId, UserSession session) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText("💰 Оберіть бажаний бюджет (у доларах $):");

        Map<String, String> priceButtons = new LinkedHashMap<>();
        if (session.getSelectedDealType() == DealType.RENT) {
            priceButtons.put("до 200$",  "PRICE_0_200");
            priceButtons.put("до 300$",  "PRICE_0_300");
            priceButtons.put("до 400$",  "PRICE_0_400");
            priceButtons.put("до 500$",  "PRICE_0_500");
            priceButtons.put("500$+",    "PRICE_500_999999");
        } else {
            priceButtons.put("до 30 000$",   "PRICE_0_30000");
            priceButtons.put("до 50 000$",   "PRICE_0_50000");
            priceButtons.put("до 70 000$",   "PRICE_0_70000");
            priceButtons.put("до 100 000$",  "PRICE_0_100000");
            priceButtons.put("100 000$+",    "PRICE_100000_200000");
            priceButtons.put("200 000$+",    "PRICE_200000_999999999");
        }
        priceButtons.put("Будь-яка ціна", "PRICE_ANY");

        String backCallback = session.getSelectedPropertyType() == PropertyType.APARTMENT ? "BACK_TO_ROOMS" : "BACK_TO_CITY";
        String backText = session.getSelectedPropertyType() == PropertyType.APARTMENT ? "⬅️ Назад до кімнат" : "⬅️ Назад до районів";
        edit.setReplyMarkup(InlineKeyboardFactory.createVertical(priceButtons, backText, backCallback));

        try { execute(edit); } catch (Exception e) { e.printStackTrace(); }
    }

    // ====================================================
    // --- ОБРОБКА CALLBACK-ЗАПИТІВ (ЛОГІКА АВТОМАТА) ---
    // ====================================================

    /**
     * Головний диспетчер натискань кнопок. Перевіряє поточну команду
     * та перемикає стани сесії користувача (State Routing).
     */
    private void handleCallback(long chatId, int messageId, String data, UserSession session) {
        try {
            // --- СЕКЦІЯ СИСТЕМНИХ НАВІГАЦІЙНИХ КНОПОК ("НАЗАД") ---
            switch (data) {
                case "BACK_TO_MENU" -> {
                    session.setState(BotState.START);
                    deleteMessage(chatId, messageId);
                    sendMainMenu(chatId);
                    return;
                }
                case "BACK_TO_DEAL_TYPE" -> {
                    session.setState(BotState.WAITING_FOR_DEAL_TYPE);
                    session.setSelectedDealType(null);
                    editToDealTypeSelection(chatId, messageId);
                    return;
                }
                case "BACK_TO_PROPERTY_TYPE" -> {
                    session.setState(BotState.WAITING_FOR_PROPERTY_TYPE);
                    session.setSelectedPropertyType(null);
                    editToPropertyTypeSelection(chatId, messageId, session.getSelectedDealType());
                    return;
                }
                case "BACK_TO_CITY" -> {
                    session.setState(BotState.WAITING_FOR_CITY);
                    session.setSelectedCity(null);
                    editToCitySelection(chatId, messageId);
                    return;
                }
                case "BACK_TO_ROOMS" -> {
                    session.setState(BotState.WAITING_FOR_ROOMS);
                    session.setSelectedRooms(null);
                    session.setRoomsIsMinimum(false);
                    editToRoomsSelection(chatId, messageId);
                    return;
                }
            }

            // Якщо кнопка стосується wizard-опитувальника анкети — делегуємо туди
            if (ProfileWizardController.isWizardCallback(data)) {
                profileWizard.handleCallback(chatId, messageId, data, session);
                return;
            }

            // Якщо кнопка була з Головного Меню, зупиняємо виконання
            if (handlerButtonMenu(data, session, chatId, messageId)) {
                return;
            }

            // Якщо кнопка була з фільтрації, обробляємо її
            handlerFilterButtonController(data, session, chatId, messageId);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * Обробляє сценарій натискання кнопок головного меню.
     *
     * @param data      Сирі дані з натиснутої інлайн-кнопки (наприклад, "PRICE_150_300" або "PRICE_ANY")
     * @param session   Поточна сесія користувача
     * @param chatId    ID чату з користувачем
     * @param messageId ID повідомлення, під яким натиснули кнопку
     * @return
     * @throws TelegramApiException Якщо виникає помилка при взаємодії з Telegram API
     */
    private boolean handlerButtonMenu(String data, UserSession session, long chatId, int messageId) {
        if (data.equals("MENU_SEARCH_ADS")) {
            session.setState(BotState.WAITING_FOR_DEAL_TYPE);
            editToDealTypeSelection(chatId, messageId);
            return true;
        }
        else if (data.equals("MENU_CREATE_AD")) {
            handlerFutureCreateAd(chatId, messageId);
            return true;
        }
        else if (data.equals("MENU_CREATE_PROFILE")) {
            try {
                profileWizard.startWizard(chatId, messageId, session);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return true;
        }
        return false;
    }


    /**
     * Обробляє кроки фільтрації та кнопок керування.
     *
     * @param data      Сирі дані з натиснутої інлайн-кнопки (наприклад, "PRICE_150_300" або "PRICE_ANY")
     * @param session   Поточна сесія користувача
     * @param chatId    ID чату з користувачем
     * @param messageId ID повідомлення, під яким натиснули кнопку
     * @throws TelegramApiException Якщо виникає помилка при взаємодії з Telegram API
     */
    private void handlerFilterButtonController(String data, UserSession session, long chatId, int messageId) throws Exception {

        // Крок 1: Обрання типу угоди (Купівля / Оренда)
        if (data.startsWith("DEAL_") && session.getState() == BotState.WAITING_FOR_DEAL_TYPE) {
            handlerSelectDealType(data, session, chatId, messageId);
        }
        // Крок 2: Обрання типу нерухомості
        else if (data.startsWith("PTYPE_") && session.getState() == BotState.WAITING_FOR_PROPERTY_TYPE) {
            handlerSelectPropertyType(data, session, chatId, messageId);
        }
        // Крок 3: Обрання міста
        else if (data.startsWith("CITY_") && session.getState() == BotState.WAITING_FOR_CITY) {
            handlerSelectCity(data, session, chatId, messageId);
        }
        // Крок 4 (лише квартири): Обрання кількості кімнат
        else if (data.startsWith("ROOMS_") && session.getState() == BotState.WAITING_FOR_ROOMS) {
            handlerRoomsSelection(data, session, chatId, messageId);
        }
        // Крок 5: Обрання ціни
        else if (data.startsWith("PRICE_") && session.getState() == BotState.WAITING_FOR_PRICE) {
            handlerPriceSelection(data, session, chatId, messageId);
        }
        // Кнопка пагінації "Наступні 3"
        else if (data.equals("NEXT_3") && session.getState() == BotState.SHOWING_RESULTS) {
            handlerNextPageButton(session, chatId, messageId);
        }
        // Кнопка "Змінити фільтр" під результатами пошуку
        else if (data.equals("BACK_TO_FILTERS")) {
            handlerBackFiltersButton(session, chatId, messageId);
        }
        // Кнопка "Вихід" під результатами пошуку
        else if (data.equals("EXIT_BOT")) {
            handleExitButton(session, chatId, messageId);
        }
    }

    /**
     * Обробляє сценарій натискання на кнопку "Вихід".
     * Очищує inline-кнопки, скидає стан та повністю видаляє сесію користувача з пам'яті.
     *
     * @param userSession Поточна сесія користувача
     * @param chatId      ID чату з користувачем
     * @param messageId   ID повідомлення, під яким натиснули кнопку
     * @throws TelegramApiException Якщо виникає помилка при взаємодії з Telegram API
     */
    private void handleExitButton(UserSession userSession, long chatId, int messageId) throws TelegramApiException {
        // 1. Видаляємо inline-кнопки під останнім повідомленням, щоб унеможливити повторні натискання
        clearMarkup(chatId, messageId);

        // 2. Повертаємо стан сесії на початковий рівень
        userSession.setState(BotState.START);

        // 3. Фізично видаляємо користувача з мапи активних сесій бота
        userSessions.remove(chatId);

        // 4. Формуємо та надсилаємо фінальне повідомлення для інформування про завершення сеансу
        SendMessage exitMsg = new SendMessage();
        exitMsg.setChatId(String.valueOf(chatId));
        exitMsg.setText("🚪 Пошук завершено. Напишіть /start для нового запиту.");
        execute(exitMsg);
    }

    /**
     * Обробляє сценарій натискання на кнопку "Змінити фільтр" (повернення назад).
     * Враховує скорочену логіку кроків для подобової оренди нерухомості.
     *
     * @param userSession Поточна сесія користувача
     * @param chatId      ID чату з користувачем
     * @param messageId   ID повідомлення, під яким натиснули кнопку
     * @throws TelegramApiException Якщо виникає помилка при взаємодії з Telegram API
     */
    private void handlerBackFiltersButton(UserSession userSession, long chatId, int messageId) throws TelegramApiException {
        // 1. Прибираємо кнопки пагінації та фільтрів з попереднього екрана результатів
        clearMarkup(chatId, messageId);

        // 2. Повністю скидаємо всі фільтри та повертаємось на самий початок воронки
        userSession.setState(BotState.WAITING_FOR_DEAL_TYPE);
        userSession.setSelectedDealType(null);
        userSession.setSelectedPropertyType(null);
        userSession.setSelectedCity(null);
        userSession.setSelectedCategory(null);
        userSession.setSelectedRooms(null);
        userSession.setRoomsIsMinimum(false);
        userSession.setPriceRangeUsd(null, null);

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText("🔄 Скидання фільтрів...");
        int dummyId = execute(msg).getMessageId();
        editToDealTypeSelection(chatId, dummyId);
    }

    /**
     * Обробляє сценарій натискання на кнопку пагінації "Наступні 3".
     * Обчислює та встановлює новий зсув (offset), після чого виводить наступну сторінку оголошень.
     *
     * @param userSession Поточна сесія користувача
     * @param chatId      ID чату з користувачем
     * @param messageId   ID повідомлення, під яким натиснули кнопку
     * @throws Exception Якщо виникає помилка при отриманні оголошень або відправці медіа
     */
    private void handlerNextPageButton(UserSession userSession, long chatId, int messageId) throws Exception {
        // 1. Прибираємо старі навігаційні кнопки, щоб користувач не міг спамити пагінацією
        clearMarkup(chatId, messageId);

        // 2. Обчислюємо новий зсув: поточний offset + 3 нових оголошення
        int newOffset = userSession.getCurrentOffset() + 3;

        // 3. Записуємо нове значення зсуву в сесію користувача
        userSession.setCurrentOffset(newOffset);

        // 4. Передаємо керування методу відправки нової порції оголошень з БД
        sendAdsBatch(chatId, userSession);
    }

    /**
     * Обробляє вибір цінового діапазону користувачем (Фінальний крок фільтрації).
     * Парсить callback-дані, фіксує рамки бюджету та запускає першу видачу результатів.
     *
     * @param data        Сирі дані з натиснутої інлайн-кнопки (наприклад, "PRICE_150_300" або "PRICE_ANY")
     * @param userSession Поточна сесія користувача
     * @param chatId      ID чату з користувачем
     * @param messageId   ID повідомлення, під яким натиснули кнопку
     * @throws Exception Якщо виникає помилка при роботі з БД чи відправці даних в Telegram
     */
    private void handlerPriceSelection(String data, UserSession userSession, long chatId, int messageId) throws Exception {
        // 1. Відсікаємо технічний префікс, залишаючи лише значення цін
        String priceVal = data.replace("PRICE_", "");

        // 2. Аналізуємо обраний діапазон
        if (priceVal.equals("ANY")) {
            // Якщо обрано "Будь-яка ціна" — передаємо значення null у фільтр бази даних
            userSession.setPriceRangeUsd(null, null);
        } else {
            // Сплітуємо рядок виду "MIN_MAX" за символом підкреслення
            String[] parts = priceVal.split("_");
            // Записуємо мінімальну та максимальну межу ціни в доларах у сесію
            userSession.setPriceRangeUsd(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }

        // 3. Готуємо сесію до виведення результатів
        userSession.setCurrentOffset(0); // Обов'язково скидаємо пагінацію на початкову сторінку (0)
        userSession.setState(BotState.SHOWING_RESULTS); // Перемикаємо стан автомата на відображення оголошень

        // 4. Очищуємо екран від меню вибору цін та надсилаємо першу пачку нерухомості
        clearMarkup(chatId, messageId);
        sendAdsBatch(chatId, userSession);
    }

    /**
     * Обробляє крок вибору кількості кімнат.
     * Фіксує вибір користувача в сесії та перемикає інтерфейс на меню вибору бюджету.
     *
     * @param data        Сирі дані кнопки кімнат (наприклад, "ROOMS_1", "ROOMS_3PLUS")
     * @param userSession Поточна сесія користувача
     * @param chatId      ID чату з користувачем
     * @param messageId   ID повідомлення, під яким натиснули кнопку
     */
    private void handlerRoomsSelection(String data, UserSession userSession, long chatId, int messageId) {
        // 1. Очищуємо callback-рядок від префікса
        String roomsVal = data.replace("ROOMS_", "");

        // 2. "3PLUS" означає "3 і більше кімнат" — точний збіг тут не підходить
        if (roomsVal.equals("3PLUS")) {
            userSession.setSelectedRooms(3);
            userSession.setRoomsIsMinimum(true);
        } else {
            userSession.setSelectedRooms(Integer.parseInt(roomsVal));
            userSession.setRoomsIsMinimum(false);
        }

        // 3. Переводимо автомат у стан очікування фільтра цін
        userSession.setState(BotState.WAITING_FOR_PRICE);

        // 4. Оновлюємо поточний екран, виводячи динамічні кнопки вибору бюджету
        editToPriceSelection(chatId, messageId, userSession);
    }

    /**
     * Обробляє крок вибору типу угоди (Купівля / Оренда) — перший крок нової воронки.
     */
    private void handlerSelectDealType(String data, UserSession userSession, long chatId, int messageId) {
        DealType dealType = data.equals("DEAL_BUY") ? DealType.BUY : DealType.RENT;
        userSession.setSelectedDealType(dealType);
        userSession.setState(BotState.WAITING_FOR_PROPERTY_TYPE);
        editToPropertyTypeSelection(chatId, messageId, dealType);
    }

    /**
     * Обробляє крок вибору типу нерухомості (Квартира / Будинок / Комерційна / Земля).
     */
    private void handlerSelectPropertyType(String data, UserSession userSession, long chatId, int messageId) {
        PropertyType type = PropertyType.valueOf(data.replace("PTYPE_", ""));
        userSession.setSelectedPropertyType(type);
        userSession.setState(BotState.WAITING_FOR_CITY);
        editToCitySelection(chatId, messageId);
    }

    /**
     * Обробляє крок вибору географічного району (міста).
     * Записує район у сесію, визначає відповідну CategoryLocation для SQL-фільтра
     * на основі комбінації DealType + PropertyType, і залежно від типу нерухомості
     * веде або на вибір кімнат (лише квартири), або одразу на ціну.
     *
     * @param data        Сирі дані кнопки міста (наприклад, "CITY_CHERNIVTSI")
     * @param userSession Поточна сесія користувача
     * @param chatId      ID чату з користувачем
     * @param messageId   ID повідомлення, під яким натиснули кнопку
     */
    private void handlerSelectCity(String data, UserSession userSession, long chatId, int messageId)  {
        userSession.setSelectedCity(City.valueOf(data.replace("CITY_", "")));
        userSession.setSelectedCategory(resolveCategoryLocation(userSession.getSelectedDealType(), userSession.getSelectedPropertyType()));

        if (userSession.getSelectedPropertyType() == PropertyType.APARTMENT) {
            userSession.setState(BotState.WAITING_FOR_ROOMS);
            editToRoomsSelection(chatId, messageId);
        } else {
            // Для будинків/комерції/землі кроку кімнат немає — одразу до ціни
            userSession.setState(BotState.WAITING_FOR_PRICE);
            editToPriceSelection(chatId, messageId, userSession);
        }
    }

    /**
     * Мапить обрану користувачем комбінацію (тип угоди + тип нерухомості) на
     * реальну AnnouncementCategory, яку вміє шукати OLX-парсер. Повертає null
     * лише для комбінацій, яких OLX взагалі не має (оренда земельної ділянки —
     * такого варіанту й не пропонує editToPropertyTypeSelection, але про всяк
     * випадок обробляємо і тут захисним null).
     */
    private AnnouncementCategory resolveCategoryLocation(DealType dealType, PropertyType propertyType) {
        if (dealType == DealType.RENT) {
            return switch (propertyType) {
                case APARTMENT  -> AnnouncementCategory.RENT_LONG;
                case HOUSE      -> AnnouncementCategory.RENT_HOUSE;
                case COMMERCIAL -> AnnouncementCategory.RENT_COMMERCIAL;
                case LAND       -> null; // оренди земельних ділянок OLX не пропонує
            };
        }
        return switch (propertyType) {
            case APARTMENT  -> AnnouncementCategory.SALE_APARTMENTS;
            case HOUSE      -> AnnouncementCategory.SALE_HOUSE;
            case COMMERCIAL -> AnnouncementCategory.SALE_COMMERCIAL;
            case LAND       -> AnnouncementCategory.SALE_LAND_PARCEL;
        };
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

    // ==========================================
    // --- СЕРВІС ВИДАЧІ РЕЗУЛЬТАТІВ ---
    // ==========================================

    /**
     * Робить вибірку оголошень з бази даних SQLite за фільтрами користувача
     * і порційно відправляє їх у чат пакетами по 3 штуки.
     */
    private void sendAdsBatch(long chatId, UserSession session) throws Exception {
        // ЗАХИСТ: guard за resolveCategoryLocation(), а не просто "не квартира".
        // Будинок/Комерція/Земля тепер МАЮТЬ реальну CategoryLocation при Купівлі —
        // заглушка спрацьовує лише для справді непідтримуваних комбінацій
        // (наприклад, оренда будинку — такої категорії парсер не збирає).
        if (session.getSelectedCategory() == null) {
            SendMessage placeholder = new SendMessage();
            placeholder.setChatId(String.valueOf(chatId));
            placeholder.setText("🚧 Пошук \"" + session.getSelectedDealType().getLabel() + " — " +
                    session.getSelectedPropertyType().getLabel() + "\" ще не підтримується. Спробуйте, будь ласка, інший тип угоди.");
            placeholder.setReplyMarkup(InlineKeyboardFactory.createVertical(new LinkedHashMap<>() {{
                put("🔄 Новий пошук", "BACK_TO_FILTERS");
                put("🚪 Вихід", "EXIT_BOT");
            }}, null, null));
            execute(placeholder);
            return;
        }

        // Звертаємось до сервісу бази даних, передаючи туди заповнений об'єкт сесії фільтрів
        List<Announcement> allFilteredAds = ProjectDatabaseService.getAnnouncementsByFilter(session);
        int offset = session.getCurrentOffset();
        int total = allFilteredAds.size();

        // Перевірка: якщо оголошень взагалі немає або ми дійшли до кінця списку
        if (total == 0 || offset >= total) {
            SendMessage emptyMsg = new SendMessage();
            emptyMsg.setChatId(String.valueOf(chatId));
            emptyMsg.setText(total == 0 ? "🤷‍♂️ Об'єктів з такими параметрами не знайдено." : "🏁 Це всі оголошення за цим фільтром.");

            // Даємо кнопки швидкого виходу або перезапуску
            emptyMsg.setReplyMarkup(InlineKeyboardFactory.createVertical(new LinkedHashMap<>() {{
                put("🔄 Новий фільтр", "BACK_TO_FILTERS");
                put("🚪 Вихід", "EXIT_BOT");
            }}, null, null));

            execute(emptyMsg);
            return;
        }

        // Обчислюємо індекси для поточного порційного зрізу (максимум 3 оголошення)
        int end = Math.min(offset + 3, total);
        List<Announcement> batch = allFilteredAds.subList(offset, end);

        // Почергово відправляємо кожне оголошення з поточної пачки
        for (Announcement ad : batch) {
            SendMessage adMsg = new SendMessage();
            adMsg.setChatId(String.valueOf(chatId));
            adMsg.setParseMode("HTML");

            // Делегуємо формування HTML-шаблону окремому класу-форматувальнику залежно від категорії
            if (ad.getCategory() == AnnouncementCategory.RENT_SHORT) {
                adMsg.setText(AnnouncementFormatter.toHtmlDailyRental(ad));
            } else if (ad.getCategory() != null && ad.getCategory().name().startsWith("SALE")) {
                adMsg.setText(AnnouncementFormatter.toHtmlForSale(ad));
            } else {
                adMsg.setText(AnnouncementFormatter.toHtmlLongTermLease(ad));
            }
            execute(adMsg);

            // Якщо у оголошення прикріплені фотокартки — надсилаємо їх медіагрупою (до 10 штук)
            if (ad.getPhotos() != null && !ad.getPhotos().isEmpty()) {
                try {
                    List<InputMedia> mediaPhotos = new ArrayList<>();
                    int maxPhotos = Math.min(ad.getPhotos().size(), 10);
                    for (int i = 0; i < maxPhotos; i++) {
                        InputMediaPhoto photo = new InputMediaPhoto();
                        photo.setMedia(ad.getPhotos().get(i));
                        mediaPhotos.add(photo);
                    }
                    SendMediaGroup mediaGroup = new SendMediaGroup();
                    mediaGroup.setChatId(String.valueOf(chatId));
                    mediaGroup.setMedias(mediaPhotos);
                    execute(mediaGroup);
                } catch (Exception ignored) {}

                // Невеликий тайм-аут, щоб уникнути спам-блоку (Flood Wait) від серверів Telegram
                Thread.sleep(300);
            }
        }

        // Крок 3: Надсилаємо сервісне повідомлення керування пагінацією в самому кінці пачки
        SendMessage controlMsg = new SendMessage();
        controlMsg.setChatId(String.valueOf(chatId));
        controlMsg.setText(String.format("📊 Показано %d із %d оголошень.", end, total));

        List<List<InlineKeyboardButton>> controlKeyboard = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        // Додаємо кнопку "Наступні 3" тільки якщо в базі ще є непереглянуті об'єкти
        if (end < total) {
            row.addAll(InlineKeyboardFactory.createRow(new String[][]{{"⏭ Наступні 3", "NEXT_3"}}));
        }
        // Завжди доступні базові інструменти завершення/зміни пошуку
        row.addAll(InlineKeyboardFactory.createRow(new String[][]{
                {"⬅️ Змінити фільтр", "BACK_TO_FILTERS"}, {"🚪 Вихід", "EXIT_BOT"}
        }));

        controlKeyboard.add(row);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(controlKeyboard);
        controlMsg.setReplyMarkup(markup);
        execute(controlMsg);
    }



    /**
     * Перевіряє, чи підписаний користувач на обов'язкову групу.
     * * @param userId Унікальний Telegram ID користувача
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
     * МЕТОД-ЗАГЛУШКА: Обробка вибору пункту "Створити оголошення (здати квартиру)"
     */
    private void handlerFutureCreateAd(long chatId, int messageId) {
        // Змінюємо стан під майбутній кінцевий автомат створення оголошень (якщо необхідно)
        // session.setState(BotState.WAITING_FOR_OWN_AD_DATA);

        EditMessageText message = BotMessage.crateFutureCreateAd(chatId, messageId);
        // Кнопка повернення назад в головне меню
        message.setReplyMarkup(InlineKeyboardFactory.createVertical(new LinkedHashMap<>(), "⬅️ Назад в меню", "BACK_TO_MENU"));

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * МЕТОД-ЗАГЛУШКА: Обробка вибору пункту "Створити анкету (шукаю житло)"
     */
    private void handlerFutureCreateProfile(long chatId, int messageId) {

        EditMessageText message = BotMessage.createFutureCreateProfile(chatId, messageId);
        // Кнопка повернення назад в головне меню
        message.setReplyMarkup(InlineKeyboardFactory.createVertical(new LinkedHashMap<>(), "⬅️ Назад в меню", "BACK_TO_MENU"));

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
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