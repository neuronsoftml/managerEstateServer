package core.telegram.controllers;

import core.serverDB.sqlite.ProjectDatabaseService;
import core.telegram.main.InlineKeyboardFactory;
import core.telegram.model.BotState;
import core.telegram.model.UserSession;
import model.*;
import model.Currency;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.*;



/**
 * Контролер майстра (Wizard) "Створити оголошення" (продаж/оренда).
 * <p>
 * Реалізує покроковий FSM-сценарій: вибір типу угоди → тип нерухомості → тип ринку
 * (новобудова/вторинний) → динамічний (залежно від {@link PropertyType}) набір
 * основних полів → поверх/поверховість → термін/опис/фото → номер телефону
 * (через кнопку "Поділитись контактом") → екран резюме з точковим редагуванням
 * кожного поля → підтвердження та інструкція з оплати.
 * </p>
 * <p><b>Динамічний UI (одне актуальне повідомлення):</b> замість надсилання нового
 * повідомлення на кожному кроці, контролер зберігає {@code messageId} останнього
 * повідомлення майстра в {@code UserSession.createPostMessageId} і <b>редагує</b> саме
 * його ({@code EditMessageText}) на кожному наступному кроці. Якщо редагування не
 * вдається (повідомлення видалене користувачем, старше 48 год тощо) — надсилається
 * нове повідомлення, і його {@code messageId} стає новим "актуальним".</p>
 * <p><b>Виняток — крок телефону:</b> Telegram Bot API дозволяє прикріпити
 * {@code ReplyKeyboardMarkup} (кнопку "Поділитись контактом") лише до <b>нового</b>
 * повідомлення — редагувати вже надіслане повідомлення так, щоб додати цю кнопку,
 * неможливо (у {@code editMessageText} підтримується лише {@code InlineKeyboardMarkup}).
 * Тому на цьому кроці попередній екран майстра видаляється, а запит на контакт
 * надсилається окремим повідомленням, яке прибирається одразу після отримання номера.</p>
 *
 * @author Mykola
 */
public class CreatePostWizardController implements BotController {

    // ── Callback-дані цього майстра (унікальний префікс "cp_" — Create Post) ──

    private static final String CB_CANCEL = "cp_cancel_post";
    private static final String CB_PHOTOS_DONE = "cp_photos_done";
    private static final String CB_CONFIRM_PAYMENT = "cp_confirm_payment";

    // Префікси для розбору callback-даних кроків із фіксованим набором варіантів
    private static final String CB_DEAL_PREFIX = "cp_deal_";
    private static final String CB_PROP_PREFIX = "cp_prop_";
    private static final String CB_MARKET_PREFIX = "cp_market_";
    private static final String CB_CUR_PREFIX = "cp_cur_";
    private static final String CB_SELLER_PREFIX = "cp_seller_";
    private static final String CB_DUR_PREFIX = "cp_dur_";
    private static final String CB_EDIT_PREFIX = "cp_edit_";

    private final TelegramSender sender;

    public CreatePostWizardController(TelegramSender sender) {
        this.sender = sender;
    }

    // ── Точка входу в майстер (викликається з CreateAdPostController) ─────────

    /**
     * Ініціалізує нову чернетку оголошення в сесії та надсилає перший крок майстра —
     * вибір типу угоди (Купівля/Продаж чи Оренда).
     */
    public void start(long chatId, int triggerMessageId, UserSession session) throws TelegramApiException {
        clearTemporaryMessage(chatId, session);
        deleteMessage(chatId, triggerMessageId);
        session.setCreatePostDraft(new CreatePostDraft());
        session.setCreatePostEditMode(false);
        session.setCreatePostMessageId(0);
        session.setState(BotState.CREATE_POST_WAITING_FOR_DEAL_TYPE);

        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put("🏷 Продаж", CB_DEAL_PREFIX + "buy");
        buttons.put("🔑 Оренда", CB_DEAL_PREFIX + "rent");

        renderStep(chatId, session, "📢 <b>Створення оголошення</b>\n\nОберіть тип угоди:", buttons);
    }

    @Override
    public void handle(Update update, UserSession session, PrintStream botOut) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallback(update, session);
            } else if (update.hasMessage()) {
                handleMessage(update, session);
            }
        } catch (Exception e) {
            e.printStackTrace(botOut);
        }
    }

    // ── Обробка натискань на inline-кнопки ─────────────────────────────────────

    private void handleCallback(Update update, UserSession session) throws TelegramApiException {
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        long telegramUserId = update.getCallbackQuery().getFrom().getId();
        String data = update.getCallbackQuery().getData();
        CreatePostDraft draft = session.getCreatePostDraft();
        BotState state = session.getState();

        // Скасування доступне на будь-якому кроці майстра
        if (CB_CANCEL.equals(data)) {
            cancelWizard(chatId, session);
            return;
        }

        switch (state) {
            case CREATE_POST_WAITING_FOR_DEAL_TYPE -> {
                if (!data.startsWith(CB_DEAL_PREFIX)) return;
                draft.setDealType(data.endsWith("buy") ? DealType.BUY : DealType.RENT);
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_PROPERTY_TYPE -> {
                if (!data.startsWith(CB_PROP_PREFIX)) return;
                PropertyType type = PropertyType.valueOf(data.substring(CB_PROP_PREFIX.length()).toUpperCase());
                draft.setPropertyType(type);
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_MARKET_TYPE -> {
                if (!data.startsWith(CB_MARKET_PREFIX)) return;
                draft.setMarketType(PropertyMarketType.valueOf(data.substring(CB_MARKET_PREFIX.length()).toUpperCase()));
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_CURRENCY -> {
                if (!data.startsWith(CB_CUR_PREFIX)) return;
                draft.setCurrency(Currency.valueOf(data.substring(CB_CUR_PREFIX.length()).toUpperCase()));
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_SELLER_TYPE -> {
                if (!data.startsWith(CB_SELLER_PREFIX)) return;
                draft.setSellerType(SellerType.valueOf(data.substring(CB_SELLER_PREFIX.length()).toUpperCase()));
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_DURATION -> {
                if (!data.startsWith(CB_DUR_PREFIX)) return;
                draft.setDurationDays(Integer.parseInt(data.substring(CB_DUR_PREFIX.length())));
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_PHOTOS -> {
                if (CB_PHOTOS_DONE.equals(data)) {
                    if (draft.getPhotoFileIds().isEmpty()) {
                        sendSimple(chatId, "⚠️ Потрібно додати щонайменше одне фото перед продовженням.", session);
                        return;
                    }
                    afterFieldCollected(chatId, session);
                }
            }

            case CREATE_POST_SUMMARY -> {
                if (CB_CONFIRM_PAYMENT.equals(data)) {
                    finalizeAndRequestPayment(chatId, telegramUserId, session);
                }
                // Відкриття меню редагування
                else if ("cp_open_edit".equals(data)) {
                    session.setEditMenuOpen(true);
                    renderSummary(chatId, session);
                }
                // Закриття меню редагування
                else if ("cp_close_edit".equals(data)) {
                    session.setEditMenuOpen(false);
                    renderSummary(chatId, session);
                }
                // Редагування конкретного поля
                else if (data.startsWith(CB_EDIT_PREFIX)) {
                    BotState target = BotState.valueOf(data.substring(CB_EDIT_PREFIX.length()));
                    if (target == BotState.CREATE_POST_WAITING_FOR_PHOTOS) {
                        draft.getPhotoFileIds().clear();
                    }
                    session.setCreatePostEditMode(true);
                    // Скидаємо стан меню, щоб після завершення редагування
                    // користувач бачив головний екран резюме, а не розгорнуте меню
                    session.setEditMenuOpen(false);
                    advanceTo(chatId, session, target);
                }
            }

            default -> { /* Callback не стосується цього майстра — ігноруємо */ }
        }
    }

    // ── Обробка звичайних текстових/фото/контакт повідомлень ───────────────────

    private void handleMessage(Update update, UserSession session) throws TelegramApiException {
        long chatId = update.getMessage().getChatId();
        CreatePostDraft draft = session.getCreatePostDraft();
        BotState state = session.getState();

        if (state == BotState.CREATE_POST_WAITING_FOR_PHOTOS) {
            if (update.getMessage().hasPhoto()) {
                List<PhotoSize> sizes = update.getMessage().getPhoto();
                String fileId = sizes.get(sizes.size() - 1).getFileId(); // найбільший розмір — останній в списку
                draft.getPhotoFileIds().add(fileId);

                Map<String, String> buttons = new LinkedHashMap<>();
                buttons.put("✅ Готово", CB_PHOTOS_DONE);
                renderStep(chatId, session, "📷 Фото додано (" + draft.getPhotoFileIds().size() + "). " +
                        "Надішліть ще фото або натисніть «Готово».", buttons);
            } else {
                sendSimple(chatId, "⚠️ Будь ласка, надішліть фотографію об'єкта (або натисніть «Готово», якщо вже додали хоча б одну).", session);
            }
            return;
        }

        if (state == BotState.CREATE_POST_WAITING_FOR_PHONE) {
            if (update.getMessage().hasContact()) {
                draft.setPhoneNumber(update.getMessage().getContact().getPhoneNumber());
                removeContactKeyboard(chatId, session);
                afterFieldCollected(chatId, session);
            } else {
                sendPhoneRequest(chatId, "⚠️ Скористайтесь кнопкою нижче, щоб надіслати номер телефону:", session);
            }
            return;
        }

        if (!update.getMessage().hasText()) return;
        String text = update.getMessage().getText().trim();

        switch (state) {
            case CREATE_POST_WAITING_FOR_TITLE -> {
                if (text.isBlank() || text.length() > 120) {
                    sendSimple(chatId, "⚠️ Назва має бути не порожньою та не довшою за 120 символів. Спробуйте ще раз:", session);
                    return;
                }
                draft.setTitle(text);
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_PRICE -> {
                BigDecimal price = parsePositiveDecimal(text);
                if (price == null) {
                    sendSimple(chatId, "⚠️ Введіть коректне додатнє число (наприклад, 25000). Спробуйте ще раз:", session);
                    return;
                }
                draft.setPrice(price);
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_LOCATION -> {
                if (text.isBlank()) {
                    sendSimple(chatId, "⚠️ Опишіть локацію (район, вулиця тощо). Спробуйте ще раз:",session);
                    return;
                }
                draft.setLocation(text);
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_LAND_AREA -> {
                Double area = parsePositiveDouble(text);
                if (area == null) {
                    sendSimple(chatId, "⚠️ Введіть коректну площу ділянки числом (наприклад, 10.5). Спробуйте ще раз:",session);
                    return;
                }
                draft.setLandArea(area);
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_ROOMS -> {
                Integer rooms = parsePositiveInt(text);
                if (rooms == null) {
                    sendSimple(chatId, "⚠️ Введіть кількість кімнат цілим числом (наприклад, 2). Спробуйте ще раз:",session);
                    return;
                }
                draft.setRooms(rooms);
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_AREA -> {
                Double area = parsePositiveDouble(text);
                if (area == null) {
                    sendSimple(chatId, "⚠️ Введіть коректну загальну площу числом (наприклад, 54.2). Спробуйте ще раз:",session);
                    return;
                }
                draft.setTotalArea(area);
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_FLOOR -> {
                Integer floor = parseIntAllowZero(text);
                if (floor == null) {
                    sendSimple(chatId, "⚠️ Введіть поверх цілим числом (наприклад, 3). Спробуйте ще раз:",session);
                    return;
                }
                draft.setFloor(floor);
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_TOTAL_FLOORS -> {
                Integer totalFloors = parsePositiveInt(text);
                if (totalFloors == null) {
                    sendSimple(chatId, "⚠️ Введіть загальну поверховість будинку цілим додатним числом (наприклад, 9). Спробуйте ще раз:",session);
                    return;
                }
                if (draft.getFloor() != null && totalFloors < draft.getFloor()) {
                    sendSimple(chatId, "⚠️ Поверховість не може бути меншою за вказаний поверх (" + draft.getFloor() + "). Спробуйте ще раз:",session);
                    return;
                }
                draft.setTotalFloors(totalFloors);
                afterFieldCollected(chatId, session);
            }

            case CREATE_POST_WAITING_FOR_DESCRIPTION -> {
                if (text.isBlank()) {
                    sendSimple(chatId, "⚠️ Опис не може бути порожнім. Спробуйте ще раз:",session);
                    return;
                }
                draft.setDescription(text);
                afterFieldCollected(chatId, session);
            }

            default -> { /* Текстове повідомлення не очікується на цьому кроці — ігноруємо */ }
        }
    }

    // ── Переходи між кроками ────────────────────────────────────────────────────

    /**
     * Викликається одразу після того, як значення поточного поля успішно зібрано й провалідовано.
     * Якщо крок було відкрито з екрану резюме (режим редагування) — повертає користувача одразу
     * до резюме. Інакше — переходить до наступного кроку динамічної послідовності.
     */
    private void afterFieldCollected(long chatId, UserSession session) throws TelegramApiException {
        clearTemporaryMessage(chatId, session);
        if (session.isCreatePostEditMode()) {
            session.setCreatePostEditMode(false);
            renderSummary(chatId, session);
            return;
        }

        BotState next = nextState(session);
        advanceTo(chatId, session, next);
    }

    private void advanceTo(long chatId, UserSession session, BotState next) throws TelegramApiException {
        session.setState(next);

        switch (next) {
            case CREATE_POST_WAITING_FOR_PROPERTY_TYPE -> promptPropertyType(chatId, session);
            case CREATE_POST_WAITING_FOR_MARKET_TYPE -> promptMarketType(chatId, session);
            case CREATE_POST_WAITING_FOR_TITLE -> sendCancellable(chatId, session, "✏️ Введіть назву оголошення, (заголовок) :");
            case CREATE_POST_WAITING_FOR_CURRENCY -> promptCurrency(chatId, session);
            case CREATE_POST_WAITING_FOR_PRICE -> sendCancellable(chatId, session, "💰 Ціна (число):");
            case CREATE_POST_WAITING_FOR_LOCATION -> sendCancellable(chatId, session, "📍Локацію:");
            case CREATE_POST_WAITING_FOR_LAND_AREA -> sendCancellable(chatId, session, "🌱Площа земельної ділянки (в сотках):");
            case CREATE_POST_WAITING_FOR_ROOMS -> sendCancellable(chatId, session, "🚪 Кількість кімнат:");
            case CREATE_POST_WAITING_FOR_AREA -> sendCancellable(chatId, session, "📐 Площа(м²)");
            case CREATE_POST_WAITING_FOR_FLOOR -> sendCancellable(chatId, session, "🏢 Поверх:");
            case CREATE_POST_WAITING_FOR_TOTAL_FLOORS -> sendCancellable(chatId, session, "🏢 Поверховість:");
            case CREATE_POST_WAITING_FOR_SELLER_TYPE -> promptSellerType(chatId, session);
            case CREATE_POST_WAITING_FOR_DURATION -> promptDuration(chatId, session);
            case CREATE_POST_WAITING_FOR_DESCRIPTION -> sendCancellable(chatId, session, "📝 Опис:");
            case CREATE_POST_WAITING_FOR_PHOTOS -> promptPhotos(chatId, session);
            case CREATE_POST_WAITING_FOR_PHONE -> promptPhone(chatId, session);
            case CREATE_POST_SUMMARY -> renderSummary(chatId, session);
            default -> { /* немає окремого промпту для інших станів (наприклад, DEAL_TYPE — лише стартовий) */ }
        }
    }

    /**
     * Обчислює наступний крок динамічної послідовності залежно від обраного {@link PropertyType}.
     * Повертає {@link BotState#CREATE_POST_SUMMARY}, якщо поточний крок був останнім у послідовності.
     */
    private BotState nextState(UserSession session) {
        List<BotState> sequence = buildStepSequence(session.getCreatePostDraft().getPropertyType());
        int idx = sequence.indexOf(session.getState());
        if (idx == -1 || idx == sequence.size() - 1) {
            return BotState.CREATE_POST_SUMMARY;
        }
        return sequence.get(idx + 1);
    }

    /**
     * Будує повну послідовність кроків майстра від типу угоди до телефону включно.
     * {@code type} може бути {@code null} під час обчислення кроків, що йдуть ДО вибору
     * типу нерухомості (це не впливає на їхню позицію в списку — умовні поля додаються
     * лише в "хвості" послідовності, після {@code LOCATION}).
     */
    private List<BotState> buildStepSequence(PropertyType type) {
        List<BotState> steps = new ArrayList<>();
        steps.add(BotState.CREATE_POST_WAITING_FOR_DEAL_TYPE);
        steps.add(BotState.CREATE_POST_WAITING_FOR_PROPERTY_TYPE);
        steps.add(BotState.CREATE_POST_WAITING_FOR_MARKET_TYPE);
        steps.add(BotState.CREATE_POST_WAITING_FOR_TITLE);
        steps.add(BotState.CREATE_POST_WAITING_FOR_CURRENCY);
        steps.add(BotState.CREATE_POST_WAITING_FOR_PRICE);
        steps.add(BotState.CREATE_POST_WAITING_FOR_LOCATION);

        if (type == PropertyType.HOUSE || type == PropertyType.LAND) {
            steps.add(BotState.CREATE_POST_WAITING_FOR_LAND_AREA);
        }
        if (type == PropertyType.APARTMENT || type == PropertyType.HOUSE) {
            steps.add(BotState.CREATE_POST_WAITING_FOR_ROOMS);
        }
        if (type != PropertyType.LAND) {
            steps.add(BotState.CREATE_POST_WAITING_FOR_AREA);
            steps.add(BotState.CREATE_POST_WAITING_FOR_FLOOR);
            steps.add(BotState.CREATE_POST_WAITING_FOR_TOTAL_FLOORS);
        }

        steps.add(BotState.CREATE_POST_WAITING_FOR_SELLER_TYPE);
        steps.add(BotState.CREATE_POST_WAITING_FOR_DURATION);
        steps.add(BotState.CREATE_POST_WAITING_FOR_DESCRIPTION);
        steps.add(BotState.CREATE_POST_WAITING_FOR_PHOTOS);
        steps.add(BotState.CREATE_POST_WAITING_FOR_PHONE);
        return steps;
    }

    // ── Промпти для конкретних кроків ───────────────────────────────────────────

    private void promptPropertyType(long chatId, UserSession session) throws TelegramApiException {
        Map<String, String> buttons = new LinkedHashMap<>();
        for (PropertyType type : PropertyType.values()) {
            buttons.put(type.getLabel(), CB_PROP_PREFIX + type.name().toLowerCase());
        }
        renderStep(chatId, session, "🏠 Оберіть тип нерухомості:", buttons);
    }

    private void promptMarketType(long chatId, UserSession session) throws TelegramApiException {
        Map<String, String> buttons = new LinkedHashMap<>();
        for (PropertyMarketType market : PropertyMarketType.values()) {
            buttons.put(market.getLabel(), CB_MARKET_PREFIX + market.name().toLowerCase());
        }
        renderStep(chatId, session, "🏗 Оберіть тип ринку:", buttons);
    }

    private void promptCurrency(long chatId, UserSession session) throws TelegramApiException {
        Map<String, String> buttons = new LinkedHashMap<>();
        for (Currency c : Currency.values()) {
            buttons.put(c.getSlug() + " " + c.getLabel(), CB_CUR_PREFIX + c.name().toLowerCase());
        }
        renderStep(chatId, session, "💱 Оберіть тип валюти:", buttons);
    }

    private void promptSellerType(long chatId, UserSession session) throws TelegramApiException {
        Map<String, String> buttons = new LinkedHashMap<>();
        for (SellerType s : SellerType.values()) {
            buttons.put(s.getLabel(), CB_SELLER_PREFIX + s.name().toLowerCase());
        }
        renderStep(chatId, session, "🧑‍💼 Хто розміщує оголошення?", buttons);
    }

    private void promptDuration(long chatId, UserSession session) throws TelegramApiException {
        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put("3 дні", CB_DUR_PREFIX + "3");
        buttons.put("7 днів", CB_DUR_PREFIX + "7");
        buttons.put("15 днів", CB_DUR_PREFIX + "15");
        buttons.put("30 днів", CB_DUR_PREFIX + "30");
        renderStep(chatId, session, "📅 Оберіть термін публікації оголошення:", buttons);
    }

    private void promptPhotos(long chatId, UserSession session) throws TelegramApiException {
        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put("✅ Готово", CB_PHOTOS_DONE);
        renderStep(chatId, session, "📷 Надішліть одну або кілька фотографій об'єкта. " +
                "Коли завершите — натисніть «Готово».", buttons);
    }

    /**
     * Крок телефону: прибирає inline-клавіатуру попереднього повідомлення майстра
     * (саме повідомлення й текст лишаються — це "чорновий" слід кроку фото/опису)
     * і надсилає ОКРЕМЕ нове повідомлення з кнопкою "Поділитись контактом", яку
     * неможливо додати через редагування (Telegram Bot API дозволяє ReplyKeyboardMarkup
     * лише при надсиланні нового повідомлення).
     */
    private void promptPhone(long chatId, UserSession session) throws TelegramApiException {
        // ReplyKeyboardMarkup потребує нового повідомлення; попередній екран майстра
        // більше не потрібен, тому видаляємо його, а не лишаємо в чаті.
        deleteActiveWizardMessage(chatId, session);
        sendPhoneRequest(chatId, "☎️ Останній крок — вкажіть контактний номер телефону.\n" +
                "Натисніть кнопку нижче, щоб надіслати номер зі свого профілю Telegram:", session);
    }

    private void sendPhoneRequest(long chatId, String text, UserSession session) throws TelegramApiException {
        clearTemporaryMessage(chatId, session);
        KeyboardButton contactButton = new KeyboardButton("📱 Надіслати номер телефону");
        contactButton.setRequestContact(true);

        KeyboardRow row = new KeyboardRow();
        row.add(contactButton);

        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row));
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(true);

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setReplyMarkup(keyboard);

        Message sent = sender.executeMethod(message);
        if (sent != null) {
            session.setLastTemporaryMessageId(sent.getMessageId());
        }
    }

    /** Прибирає нативну (не-inline) клавіатуру запиту контакту одразу після отримання номера. */
    private void removeContactKeyboard(long chatId, UserSession session) throws TelegramApiException {
        ReplyKeyboardRemove remove = new ReplyKeyboardRemove();
        remove.setRemoveKeyboard(true);
        clearTemporaryMessage(chatId, session);
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("\u2063"); // Telegram застосує ReplyKeyboardRemove навіть для короткого службового повідомлення.
        message.setReplyMarkup(remove);
        Message sent = sender.executeMethod(message);
        if (sent != null) deleteMessage(chatId, sent.getMessageId());
    }

    // ── Екран резюме (Summary Screen) ───────────────────────────────────────────

    private void renderSummary(long chatId, UserSession session) throws TelegramApiException {
        CreatePostDraft draft = session.getCreatePostDraft();
        draft.applySystemDefaults();

        // Формування тексту (залишається без змін)
        StringBuilder sb = new StringBuilder("<b>📋 Перевірте дані оголошення:</b>\n\n");
        sb.append("🏷 Угода: ").append(draft.getDealType() != null ? draft.getDealType().getLabel() : "—").append('\n');
        sb.append("🏠 Тип: ").append(draft.getPropertyType() != null ? draft.getPropertyType().getLabel() : "—").append('\n');
        sb.append("🏗 Ринок: ").append(draft.getMarketType() != null ? draft.getMarketType().getLabel() : "—").append('\n');
        sb.append("✏️ Назва: ").append(nvl(draft.getTitle())).append('\n');
        sb.append("💰 Ціна: ").append(draft.getPrice() != null ? draft.getPrice().toPlainString() : "—")
                .append(' ').append(draft.getCurrency() != null ? draft.getCurrency().getSlug() : "").append('\n');
        sb.append("📍 Локація: ").append(nvl(draft.getLocation())).append('\n');

        if (draft.isLandAreaApplicable()) sb.append("🌱 Площа ділянки: ").append(draft.getLandArea() != null ? draft.getLandArea() + " сот." : "—").append('\n');
        if (draft.isRoomsApplicable()) sb.append("🚪 Кімнат: ").append(draft.getRooms() != null ? draft.getRooms() : "—").append('\n');
        if (draft.isTotalAreaApplicable()) sb.append("📐 Площа: ").append(draft.getTotalArea() != null ? draft.getTotalArea() + " кв.м" : "—").append('\n');
        if (draft.isFloorApplicable()) sb.append("🏢 Поверх: ").append(draft.getFloor() != null ? draft.getFloor() : "—").append('\n');
        if (draft.isTotalFloorsApplicable()) sb.append("🏢 Поверховість: ").append(draft.getTotalFloors() != null ? draft.getTotalFloors() : "—").append('\n');

        sb.append("🧑‍💼 Продавець: ").append(draft.getSellerType() != null ? draft.getSellerType().getLabel() : "—").append('\n');
        sb.append("📅 Термін: ").append(draft.getDurationDays() != null ? draft.getDurationDays() + " дн." : "—").append('\n');
        sb.append("📝 Опис: ").append(nvl(draft.getDescription())).append('\n');
        sb.append("📷 Фото: ").append(draft.getPhotoFileIds().size()).append(" шт.\n");
        sb.append("☎️ Телефон: ").append(nvl(draft.getPhoneNumber())).append("\n\n");
        sb.append("💵 Вартість розміщення: ").append(draft.calculateListingFeeUah()).append(" грн");

        Map<String, String> buttons = new LinkedHashMap<>();

        // Логіка перемикання кнопок
        if (session.isEditMenuOpen()) {
            // Кнопки редагування конкретних полів
            buttons.put("✏️ Назва", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_TITLE.name());
            buttons.put("✏️ Ринок", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_MARKET_TYPE.name());
            buttons.put("✏️ Валюта", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_CURRENCY.name());
            buttons.put("✏️ Ціна", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_PRICE.name());
            buttons.put("✏️ Локація", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_LOCATION.name());
            if (draft.isLandAreaApplicable()) buttons.put("✏️ Площа ділянки", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_LAND_AREA.name());
            if (draft.isRoomsApplicable()) buttons.put("✏️ Кімнати", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_ROOMS.name());
            if (draft.isTotalAreaApplicable()) buttons.put("✏️ Площа", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_AREA.name());
            if (draft.isFloorApplicable()) buttons.put("✏️ Поверх", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_FLOOR.name());
            if (draft.isTotalFloorsApplicable()) buttons.put("✏️ Поверховість", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_TOTAL_FLOORS.name());
            buttons.put("✏️ Тип продавця", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_SELLER_TYPE.name());
            buttons.put("✏️ Термін публікації", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_DURATION.name());
            buttons.put("✏️ Опис", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_DESCRIPTION.name());
            buttons.put("✏️ Фото", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_PHOTOS.name());
            buttons.put("✏️ Телефон", CB_EDIT_PREFIX + BotState.CREATE_POST_WAITING_FOR_PHONE.name());

            buttons.put("⬅️ Назад", "cp_close_edit");
        } else {
            // Головні дії
            buttons.put("✅ Підтвердити та оплатити", CB_CONFIRM_PAYMENT);
            buttons.put("✏️ Редагувати поля", "cp_open_edit");
        }

        session.setState(BotState.CREATE_POST_SUMMARY);
        renderStep(chatId, session, sb.toString(), buttons);
    }



    // ── Фіналізація та інструкція з оплати ──────────────────────────────────────

    private void finalizeAndRequestPayment(long chatId, long telegramUserId, UserSession session) throws TelegramApiException {
        CreatePostDraft draft = session.getCreatePostDraft();
        draft.applySystemDefaults();

        boolean saved = ProjectDatabaseService.saveUserCreatedPost(telegramUserId, draft);

        if (!saved) {
            sendSimple(chatId, "❌ Сталася помилка під час збереження оголошення. Спробуйте підтвердити ще раз.",session);
            return;
        }

        String text = "✅ Дякуємо! Ваше оголошення прийнято.\n\n" +
                "Будь ласка, здійсніть платіж на суму <b>" + draft.calculateListingFeeUah() + " грн</b>.\n" +
                "У призначенні платежу обов'язково вкажіть код: <b>" + draft.getPaymentCode() + "</b>\n\n" +
                "Після надходження оплати оголошення буде опубліковано.";
        deleteActiveWizardMessage(chatId, session);
        clearTemporaryMessage(chatId, session);
        sendTerminalMessage(chatId, text);

        // Скидаємо стан майстра — сесія готова до наступної взаємодії з головним меню
        session.setCreatePostDraft(new CreatePostDraft());
        session.setCreatePostEditMode(false);
        session.setCreatePostMessageId(0);
        session.setState(BotState.MAIN_MENU);
    }

    private void cancelWizard(long chatId, UserSession session) throws TelegramApiException {
        deleteActiveWizardMessage(chatId, session);
        clearTemporaryMessage(chatId, session);
        session.setCreatePostDraft(new CreatePostDraft());
        session.setCreatePostEditMode(false);
        session.setCreatePostMessageId(0);
        session.setState(BotState.MAIN_MENU);
        sendTerminalMessage(chatId, "❌ Створення оголошення скасовано.");
    }

    // ── Допоміжні методи парсингу вводу ──────────────────────────────────────────

    private BigDecimal parsePositiveDecimal(String text) {
        try {
            BigDecimal value = new BigDecimal(text.replace(",", ".").replaceAll("\\s", ""));
            return value.signum() > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double parsePositiveDouble(String text) {
        try {
            double value = Double.parseDouble(text.replace(",", ".").replaceAll("\\s", ""));
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parsePositiveInt(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseIntAllowZero(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String nvl(String s) {
        return (s == null || s.isBlank()) ? "—" : s;
    }

    // ── Допоміжні методи надсилання/редагування повідомлень ─────────────────────

    /**
     * Малює черговий крок майстра в <b>єдиному</b> актуальному повідомленні: якщо
     * попередній крок уже надсилав повідомлення ({@code session.getCreatePostMessageId() != 0}),
     * воно РЕДАГУЄТЬСЯ на місці (текст + inline-клавіатура). Якщо редагування неможливе
     * (повідомлення видалене, застаріле тощо) — надсилається нове, і його ID стає новим "актуальним".
     */
    private void renderStep(long chatId, UserSession session, String text, Map<String, String> buttons) throws TelegramApiException {
        InlineKeyboardMarkup markup = InlineKeyboardFactory.createVertical(buttons, "❌ Скасувати", CB_CANCEL);
        int previousMessageId = session.getCreatePostMessageId();

        if (previousMessageId != 0) {
            try {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId));
                edit.setMessageId(previousMessageId);
                edit.setText(text);
                edit.setParseMode("HTML");
                edit.setReplyMarkup(markup);
                sender.executeMethod(edit);
                return; // messageId лишається тим самим — нового повідомлення не було
            } catch (TelegramApiException e) {
                // Повідомлення вже не існує (видалене користувачем, старше 48 год тощо) — надсилаємо нове нижче
            }
        }

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("HTML");
        message.setReplyMarkup(markup);

        Message sent = sender.executeMethod(message);
        if (sent != null) {
            session.setCreatePostMessageId(sent.getMessageId());
        }
    }

    /** Надсилає текстовий промпт вільного вводу з єдиною кнопкою "Скасувати" (той самий механізм редагування). */
    private void sendCancellable(long chatId, UserSession session, String text) throws TelegramApiException {
        renderStep(chatId, session, text, Collections.emptyMap());
    }

    /** Видаляє єдиний актуальний екран майстра та очищає його ID у сесії. */
    private void deleteActiveWizardMessage(long chatId, UserSession session) {
        int messageId = session.getCreatePostMessageId();
        if (messageId == 0) return;
        deleteMessage(chatId, messageId);
        session.setCreatePostMessageId(0);
    }

    /** Надсилає окреме службове повідомлення без клавіатури (не бере участі в механізмі редагування кроків). */
    private void sendSimple(long chatId, String text, UserSession session) throws TelegramApiException {
        clearTemporaryMessage(chatId, session);

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("HTML");

        Message sent = sender.executeMethod(message);
        if (sent != null) session.setLastTemporaryMessageId(sent.getMessageId());
    }

    private void sendTerminalMessage(long chatId, String text) throws TelegramApiException {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("HTML");
        sender.executeMethod(message);
    }

    private void clearTemporaryMessage(long chatId, UserSession session) {
        int messageId = session.getLastTemporaryMessageId();
        if (messageId == 0) return;
        deleteMessage(chatId, messageId);
        session.setLastTemporaryMessageId(0);
    }

    private void deleteMessage(long chatId, int messageId) {
        try { sender.deleteMessage(chatId, messageId); } catch (Exception ignored) { }
    }
}
