package core.telegram.controllers;

import core.telegram.main.AnnouncementFormatter;
import core.telegram.main.InlineKeyboardFactory;
import core.telegram.model.BotState;
import core.telegram.model.UserSession;
import model.*;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import core.serverDB.sqlite.ProjectDatabaseService;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Контролер бізнес-процесу "Пошук нерухомості".
 * <p>
 * Відповідає за весь покроковий сценарій вибору фільтрів (тип угоди → тип нерухомості →
 * район → кімнати → ціна), включно з кнопками "Назад" всередині цієї воронки, а також за
 * запит до {@link ProjectDatabaseService} та порційне (пагіноване) виведення результатів.
 * </p>
 * Не знає про інші бізнес-процеси (створення оголошення/анкети) чи про глобальну навігацію —
 * загальні кнопки "Назад в меню" та "Вихід" обробляються окремо в {@link NavigationController}.
 */
public class SearchAdsController implements BotController {

    private final TelegramSender sender;

    public SearchAdsController(TelegramSender sender) {
        this.sender = sender;
    }

    @Override
    public void handle(Update update, UserSession session, PrintStream botOut) {
        try {
            long chatId = update.getCallbackQuery().getMessage().getChatId();
            int messageId = update.getCallbackQuery().getMessage().getMessageId();
            String data = update.getCallbackQuery().getData();

            // --- Кнопки "Назад" всередині воронки пошуку ---
            switch (data) {
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

            // --- Точка входу з головного меню ---
            if (data.equals("MENU_SEARCH_ADS")) {
                session.setState(BotState.WAITING_FOR_DEAL_TYPE);
                editToDealTypeSelection(chatId, messageId);
                return;
            }

            // --- Покрокова воронка фільтрів ---
            if (data.startsWith("DEAL_") && session.getState() == BotState.WAITING_FOR_DEAL_TYPE) {
                handlerSelectDealType(data, session, chatId, messageId);
            } else if (data.startsWith("PTYPE_") && session.getState() == BotState.WAITING_FOR_PROPERTY_TYPE) {
                handlerSelectPropertyType(data, session, chatId, messageId);
            } else if (data.startsWith("CITY_") && session.getState() == BotState.WAITING_FOR_CITY) {
                handlerSelectCity(data, session, chatId, messageId);
            } else if (data.startsWith("ROOMS_") && session.getState() == BotState.WAITING_FOR_ROOMS) {
                handlerRoomsSelection(data, session, chatId, messageId);
            } else if (data.startsWith("PRICE_") && session.getState() == BotState.WAITING_FOR_PRICE) {
                handlerPriceSelection(data, session, chatId, messageId);
            } else if (data.equals("NEXT_3") && session.getState() == BotState.SHOWING_RESULTS) {
                handlerNextPageButton(session, chatId, messageId);
            } else if (data.equals("BACK_TO_FILTERS")) {
                handlerBackFiltersButton(session, chatId, messageId);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==========================================
    // --- МЕТОДИ ВІДОБРАЖЕННЯ ЕКРАНІВ (UI) ---
    // ==========================================

    /** КРОК 1: Тип угоди — Купівля чи Оренда. */
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

        try { sender.executeMethod(edit); } catch (Exception e) { e.printStackTrace(); }
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

        try { sender.executeMethod(edit); } catch (Exception e) { e.printStackTrace(); }
    }

    /** КРОК 3: Редагує повідомлення зі списком доступних районів (міст). */
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

        try { sender.executeMethod(edit); } catch (Exception e) { e.printStackTrace(); }
    }

    /** КРОК 4 (лише для квартир): Редагує повідомлення для вибору кількості кімнат. */
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

        try { sender.executeMethod(edit); } catch (Exception e) { e.printStackTrace(); }
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

        try { sender.executeMethod(edit); } catch (Exception e) { e.printStackTrace(); }
    }

    // ====================================================
    // --- ОБРОБНИКИ КРОКІВ ФІЛЬТРАЦІЇ ТА КЕРУВАННЯ ---
    // ====================================================

    /** Обробляє крок вибору типу угоди (Купівля / Оренда) — перший крок воронки. */
    private void handlerSelectDealType(String data, UserSession userSession, long chatId, int messageId) {
        DealType dealType = data.equals("DEAL_BUY") ? DealType.BUY : DealType.RENT;
        userSession.setSelectedDealType(dealType);
        userSession.setState(BotState.WAITING_FOR_PROPERTY_TYPE);
        editToPropertyTypeSelection(chatId, messageId, dealType);
    }

    /** Обробляє крок вибору типу нерухомості (Квартира / Будинок / Комерційна / Земля). */
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
     */
    private void handlerSelectCity(String data, UserSession userSession, long chatId, int messageId) {
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

    /** Обробляє крок вибору кількості кімнат. Переводить автомат до вибору бюджету. */
    private void handlerRoomsSelection(String data, UserSession userSession, long chatId, int messageId) {
        String roomsVal = data.replace("ROOMS_", "");

        // "3PLUS" означає "3 і більше кімнат" — точний збіг тут не підходить
        if (roomsVal.equals("3PLUS")) {
            userSession.setSelectedRooms(3);
            userSession.setRoomsIsMinimum(true);
        } else {
            userSession.setSelectedRooms(Integer.parseInt(roomsVal));
            userSession.setRoomsIsMinimum(false);
        }

        userSession.setState(BotState.WAITING_FOR_PRICE);
        editToPriceSelection(chatId, messageId, userSession);
    }

    /**
     * Обробляє вибір цінового діапазону користувачем (фінальний крок фільтрації).
     * Парсить callback-дані, фіксує рамки бюджету та запускає першу видачу результатів.
     */
    private void handlerPriceSelection(String data, UserSession userSession, long chatId, int messageId) throws Exception {
        String priceVal = data.replace("PRICE_", "");

        if (priceVal.equals("ANY")) {
            userSession.setPriceRangeUsd(null, null);
        } else {
            String[] parts = priceVal.split("_");
            userSession.setPriceRangeUsd(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        }

        userSession.setCurrentOffset(0); // Обов'язково скидаємо пагінацію на початкову сторінку (0)
        userSession.setState(BotState.SHOWING_RESULTS);

        clearMarkup(chatId, messageId);
        sendAdsBatch(chatId, userSession);
    }

    /**
     * Обробляє натискання пагінації "Наступні 3".
     * Обчислює та встановлює новий зсув (offset), після чого виводить наступну сторінку оголошень.
     */
    private void handlerNextPageButton(UserSession userSession, long chatId, int messageId) throws Exception {
        clearMarkup(chatId, messageId);

        int newOffset = userSession.getCurrentOffset() + 3;
        userSession.setCurrentOffset(newOffset);

        sendAdsBatch(chatId, userSession);
    }

    /**
     * Обробляє натискання "Змінити фільтр" під результатами пошуку.
     * Повністю скидає всі фільтри та одразу перемальовує поточне повідомлення
     * на перший крок воронки (вибір типу угоди).
     */
    private void handlerBackFiltersButton(UserSession userSession, long chatId, int messageId) throws TelegramApiException {
        clearMarkup(chatId, messageId);

        userSession.setState(BotState.WAITING_FOR_DEAL_TYPE);
        userSession.setSelectedDealType(null);
        userSession.setSelectedPropertyType(null);
        userSession.setSelectedCity(null);
        userSession.setSelectedCategory(null);
        userSession.setSelectedRooms(null);
        userSession.setRoomsIsMinimum(false);
        userSession.setPriceRangeUsd(null, null);

        editToDealTypeSelection(chatId, messageId);
    }

    /**
     * Мапить обрану користувачем комбінацію (тип угоди + тип нерухомості) на
     * реальну AnnouncementCategory, яку вміє шукати OLX-парсер. Повертає null
     * лише для комбінацій, яких OLX взагалі не має (оренда земельної ділянки).
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

    // ==========================================
    // --- СЕРВІС ВИДАЧІ РЕЗУЛЬТАТІВ ---
    // ==========================================

    /**
     * Робить вибірку оголошень з бази даних SQLite за фільтрами користувача
     * і порційно відправляє їх у чат пакетами по 3 штуки.
     */
    private void sendAdsBatch(long chatId, UserSession session) throws Exception {
        // ЗАХИСТ: guard за resolveCategoryLocation(), а не просто "не квартира".
        if (session.getSelectedCategory() == null) {
            SendMessage placeholder = new SendMessage();
            placeholder.setChatId(String.valueOf(chatId));
            placeholder.setText("🚧 Пошук \"" + session.getSelectedDealType().getLabel() + " — " +
                    session.getSelectedPropertyType().getLabel() + "\" ще не підтримується. Спробуйте, будь ласка, інший тип угоди.");
            placeholder.setReplyMarkup(InlineKeyboardFactory.createVertical(new LinkedHashMap<>() {{
                put("🔄 Новий пошук", "BACK_TO_FILTERS");
                put("🚪 Вихід", "EXIT_BOT");
            }}, null, null));
            sender.executeMethod(placeholder);
            return;
        }

        List<Announcement> allFilteredAds = ProjectDatabaseService.getAnnouncementsByFilter(session);
        int offset = session.getCurrentOffset();
        int total = allFilteredAds.size();

        if (total == 0 || offset >= total) {
            SendMessage emptyMsg = new SendMessage();
            emptyMsg.setChatId(String.valueOf(chatId));
            emptyMsg.setText(total == 0 ? "🤷‍♂️ Об'єктів з такими параметрами не знайдено." : "🏁 Це всі оголошення за цим фільтром.");

            emptyMsg.setReplyMarkup(InlineKeyboardFactory.createVertical(new LinkedHashMap<>() {{
                put("🔄 Новий фільтр", "BACK_TO_FILTERS");
                put("🚪 Вихід", "EXIT_BOT");
            }}, null, null));

            sender.executeMethod(emptyMsg);
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

            if (ad.getCategory() == AnnouncementCategory.RENT_SHORT) {
                adMsg.setText(AnnouncementFormatter.toHtmlDailyRental(ad));
            } else if (ad.getCategory() != null && ad.getCategory().name().startsWith("SALE")) {
                adMsg.setText(AnnouncementFormatter.toHtmlForSale(ad));
            } else {
                adMsg.setText(AnnouncementFormatter.toHtmlLongTermLease(ad));
            }
            sender.executeMethod(adMsg);

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
                    sender.executeMethod(mediaGroup);
                } catch (Exception ignored) {}

                // Невеликий тайм-аут, щоб уникнути спам-блоку (Flood Wait) від серверів Telegram
                Thread.sleep(300);
            }
        }

        // Надсилаємо сервісне повідомлення керування пагінацією в самому кінці пачки
        SendMessage controlMsg = new SendMessage();
        controlMsg.setChatId(String.valueOf(chatId));
        controlMsg.setText(String.format("📊 Показано %d із %d оголошень.", end, total));

        List<List<InlineKeyboardButton>> controlKeyboard = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();

        if (end < total) {
            row.addAll(InlineKeyboardFactory.createRow(new String[][]{{"⏭ Наступні 3", "NEXT_3"}}));
        }
        row.addAll(InlineKeyboardFactory.createRow(new String[][]{
                {"⬅️ Змінити фільтр", "BACK_TO_FILTERS"}, {"🚪 Вихід", "EXIT_BOT"}
        }));

        controlKeyboard.add(row);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(controlKeyboard);
        controlMsg.setReplyMarkup(markup);
        sender.executeMethod(controlMsg);
    }

    /**
     * Прибирає inline-кнопки під повідомленням, щоб користувач не міг натиснути
     * застарілі кнопки з минулих кроків.
     */
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
