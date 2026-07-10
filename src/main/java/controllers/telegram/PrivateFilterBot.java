package controllers.telegram;

import model.Announcement;
import model.CategoryLocation;
import model.City;
import model.telegram.BotState;
import model.telegram.Config;
import model.telegram.UserSession;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import sqlite.DatabaseManager;
import sqlite.ProjectDatabaseService;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;

public class PrivateFilterBot extends TelegramLongPollingBot {

    private final Map<Long, UserSession> userSessions = new HashMap<>();

    @Override
    public String getBotUsername() {
        return Config.NAME.getKey();
    }

    @Override
    public String getBotToken() {
        return Config.TOKEN.getKey();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            if (messageText.equals("/start")) {
                startWorkflow(chatId);
            }
        }
        else if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();
            long chatId = update.getCallbackQuery().getMessage().getChatId();
            int messageId = update.getCallbackQuery().getMessage().getMessageId();

            UserSession session = userSessions.computeIfAbsent(chatId, k -> new UserSession(BotState.START));
            handleCallback(chatId, messageId, callbackData, session);
        }
    }

    private void startWorkflow(long chatId) {
        UserSession session = new UserSession(BotState.WAITING_FOR_CITY);
        userSessions.put(chatId, session);
        sendCitySelection(chatId);
    }

    private void sendCitySelection(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("👋 Вітаю! Оберіть район для пошуку нерухомості:");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (City city : City.values()) {
            InlineKeyboardButton b = new InlineKeyboardButton();
            b.setText(city.getLabel());
            b.setCallbackData("CITY_" + city.name());
            rows.add(Collections.singletonList(b));
        }
        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);
        try { execute(message); } catch (Exception e) { e.printStackTrace(); }
    }

    private void editToCategorySelection(long chatId, int messageId, City city) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText("🏢 Район \"" + city.getLabel() + "\" зафіксовано.\n🔑 Оберіть тип угоди:");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (CategoryLocation cat : CategoryLocation.values()) {
            InlineKeyboardButton b = new InlineKeyboardButton();
            b.setText(cat.getLabel());
            b.setCallbackData("CAT_" + cat.name());
            rows.add(Collections.singletonList(b));
        }
        InlineKeyboardButton back = new InlineKeyboardButton();
        back.setText("⬅️ Назад до районів");
        back.setCallbackData("BACK_TO_CITY");
        rows.add(Collections.singletonList(back));

        markup.setKeyboard(rows);
        edit.setReplyMarkup(markup);
        try { execute(edit); } catch (Exception e) { e.printStackTrace(); }
    }

    private void editToRoomsSelection(long chatId, int messageId) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText("📊 Скільки кімнат вас цікавить?");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> roomRow = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            InlineKeyboardButton b = new InlineKeyboardButton();
            b.setText(i + "-кімнатна");
            b.setCallbackData("ROOMS_" + i);
            roomRow.add(b);
        }
        rows.add(roomRow);

        InlineKeyboardButton skip = new InlineKeyboardButton();
        skip.setText("Показати всі варіанти (пропустити)");
        skip.setCallbackData("ROOMS_ANY");
        rows.add(Collections.singletonList(skip));

        InlineKeyboardButton back = new InlineKeyboardButton();
        back.setText("⬅️ Назад до категорій");
        back.setCallbackData("BACK_TO_CAT");
        rows.add(Collections.singletonList(back));

        markup.setKeyboard(rows);
        edit.setReplyMarkup(markup);
        try { execute(edit); } catch (Exception e) { e.printStackTrace(); }
    }

    private void editToPriceSelection(long chatId, int messageId, UserSession session) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText("💰 Оберіть бажаний бюджет (у доларах $):");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Перевіряємо тип угоди на основі обраної категорії
        boolean isRent = session.getSelectedCategory() != null &&
                (session.getSelectedCategory().name().contains("RENT") ||
                        session.getSelectedCategory().getLabel().toLowerCase().contains("оренда"));

        String[][] prices;

        if (isRent) {
            prices = new String[][]{
                    {"150$ - 300$", "PRICE_150_300"},
                    {"300$ - 450$", "PRICE_300_450"},
                    {"450$ - 600$", "PRICE_450_600"},
                    {"600$ - 1000$", "PRICE_600_1000"},
                    {"Будь-яка ціна", "PRICE_ANY"}
            };
        } else {
            prices = new String[][]{
                    {"30 000$ - 45 000$", "PRICE_30000_45000"},
                    {"45 000$ - 60 000$", "PRICE_45000_60000"},
                    {"60 000$ - 75 000$", "PRICE_60000_75000"},
                    {"75 000$ - 2 000 000$", "PRICE_75000_2000000"},
                    {"Будь-яка ціна", "PRICE_ANY"}
            };
        }

        for (String[] p : prices) {
            InlineKeyboardButton b = new InlineKeyboardButton();
            b.setText(p[0]);
            b.setCallbackData(p[1]);
            rows.add(Collections.singletonList(b));
        }

        InlineKeyboardButton back = new InlineKeyboardButton();
        back.setText("⬅️ Назад до кімнат");
        back.setCallbackData("BACK_TO_ROOMS");
        rows.add(Collections.singletonList(back));

        markup.setKeyboard(rows);
        edit.setReplyMarkup(markup);
        try { execute(edit); } catch (Exception e) { e.printStackTrace(); }
    }

    private void handleCallback(long chatId, int messageId, String data, UserSession session) {
        try {
            // КНОПКИ НАЗАД
            if (data.equals("BACK_TO_CITY")) {
                session.setState(BotState.WAITING_FOR_CITY);
                session.setSelectedCity(null);

                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId));
                edit.setMessageId(messageId);
                edit.setText("👋 Оберіть район для пошуку нерухомості:");
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (City city : City.values()) {
                    InlineKeyboardButton b = new InlineKeyboardButton();
                    b.setText(city.getLabel());
                    b.setCallbackData("CITY_" + city.name());
                    rows.add(Collections.singletonList(b));
                }
                markup.setKeyboard(rows);
                edit.setReplyMarkup(markup);
                execute(edit);
                return;
            }

            if (data.equals("BACK_TO_CAT")) {
                session.setState(BotState.WAITING_FOR_CATEGORY);
                session.setSelectedCategory(null);
                editToCategorySelection(chatId, messageId, session.getSelectedCity());
                return;
            }

            if (data.equals("BACK_TO_ROOMS")) {
                session.setState(BotState.WAITING_FOR_ROOMS);
                session.setSelectedRooms(null);
                editToRoomsSelection(chatId, messageId);
                return;
            }

            // КРОК 1 -> КРОК 2 (Вибір категорії)
            if (data.startsWith("CITY_") && session.getState() == BotState.WAITING_FOR_CITY) {
                session.setSelectedCity(City.valueOf(data.replace("CITY_", "")));
                session.setState(BotState.WAITING_FOR_CATEGORY);
                editToCategorySelection(chatId, messageId, session.getSelectedCity());
            }
            // КРОК 2 -> КРОК 3 (Кімнати)
            else if (data.startsWith("CAT_") && session.getState() == BotState.WAITING_FOR_CATEGORY) {
                session.setSelectedCategory(CategoryLocation.valueOf(data.replace("CAT_", "")));
                session.setState(BotState.WAITING_FOR_ROOMS);
                editToRoomsSelection(chatId, messageId);
            }
            // КРОК 3 -> КРОК 4 (Ціна з урахуванням обраної категорії)
            else if (data.startsWith("ROOMS_") && session.getState() == BotState.WAITING_FOR_ROOMS) {
                String roomsVal = data.replace("ROOMS_", "");
                if (roomsVal.equals("ANY")) {
                    session.setSelectedRooms(null);
                } else {
                    session.setSelectedRooms(Integer.parseInt(roomsVal));
                }
                session.setState(BotState.WAITING_FOR_PRICE);
                editToPriceSelection(chatId, messageId, session);
            }
            // КРОК 4 -> СТАРТ ВИДАЧІ РЕЗУЛЬТАТІВ
            else if (data.startsWith("PRICE_") && session.getState() == BotState.WAITING_FOR_PRICE) {
                String priceVal = data.replace("PRICE_", "");
                if (priceVal.equals("ANY")) {
                    session.setPriceRangeUsd(null, null);
                } else {
                    String[] parts = priceVal.split("_");
                    session.setPriceRangeUsd(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                }

                session.setCurrentOffset(0);
                session.setState(BotState.SHOWING_RESULTS);

                try {
                    EditMessageReplyMarkup clearMarkup = new EditMessageReplyMarkup();
                    clearMarkup.setChatId(String.valueOf(chatId));
                    clearMarkup.setMessageId(messageId);
                    clearMarkup.setReplyMarkup(null);
                    execute(clearMarkup);
                } catch (Exception ignored) {}

                sendAdsBatch(chatId, session);
            }
            // ПАГІНАЦІЯ ТА КЕРУВАННЯ ВИДАЧЕЮ
            else if (session.getState() == BotState.SHOWING_RESULTS) {
                if (data.equals("NEXT_3")) {
                    try {
                        EditMessageReplyMarkup clearMarkup = new EditMessageReplyMarkup();
                        clearMarkup.setChatId(String.valueOf(chatId));
                        clearMarkup.setMessageId(messageId);
                        clearMarkup.setReplyMarkup(null);
                        execute(clearMarkup);
                    } catch (Exception ignored) {}

                    session.setCurrentOffset(session.getCurrentOffset() + 3);
                    sendAdsBatch(chatId, session);
                }
                else if (data.equals("BACK_TO_FILTERS") || data.equals("EXIT_BOT")) {
                    try {
                        EditMessageReplyMarkup clearMarkup = new EditMessageReplyMarkup();
                        clearMarkup.setChatId(String.valueOf(chatId));
                        clearMarkup.setMessageId(messageId);
                        clearMarkup.setReplyMarkup(null);
                        execute(clearMarkup);
                    } catch (Exception ignored) {}

                    if (data.equals("BACK_TO_FILTERS")) {
                        userSessions.remove(chatId);
                        startWorkflow(chatId);
                    } else {
                        session.setState(BotState.START);
                        userSessions.remove(chatId);
                        SendMessage exitMsg = new SendMessage();
                        exitMsg.setChatId(String.valueOf(chatId));
                        exitMsg.setText("🚪 Пошук завершено. Напишіть /start для нового запиту.");
                        execute(exitMsg);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendAdsBatch(long chatId, UserSession session) throws Exception {
        List<Announcement> allFilteredAds = ProjectDatabaseService.getAnnouncementsByFilter(session);

        int offset = session.getCurrentOffset();
        int total = allFilteredAds.size();

        if (total == 0 || offset >= total) {
            SendMessage emptyMsg = new SendMessage();
            emptyMsg.setChatId(String.valueOf(chatId));
            emptyMsg.setText(total == 0 ? "🤷‍♂️ Об'єктів з такими параметрами не знайдено." : "🏁 Це всі оголошення за цим фільтром.");

            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            List<InlineKeyboardButton> row = new ArrayList<>();
            InlineKeyboardButton backButton = new InlineKeyboardButton();
            backButton.setText("🔄 Новий фільтр");
            backButton.setCallbackData("BACK_TO_FILTERS");
            InlineKeyboardButton exitButton = new InlineKeyboardButton();
            exitButton.setText("🚪 Вихід");
            exitButton.setCallbackData("EXIT_BOT");

            row.add(backButton);
            row.add(exitButton);
            markup.setKeyboard(Collections.singletonList(row));
            emptyMsg.setReplyMarkup(markup);
            execute(emptyMsg);
            return;
        }

        int end = Math.min(offset + 3, total);
        List<Announcement> batch = allFilteredAds.subList(offset, end);
        DecimalFormat df = new DecimalFormat("#,###.##");

        for (Announcement ad : batch) {
            StringBuilder sb = new StringBuilder();
            sb.append("🆕 <b>НОВЕ ОГОЛОШЕННЯ!</b>\n\n");
            sb.append("📌 <b>Заголовок:</b> ").append(escapeHtml(ad.getTitle())).append("\n");

            if (ad.getPriceValue() != null) {
                BigDecimal priceUah = ad.getPriceValue();
                BigDecimal priceUsd = core.tools.PrivatBankRateService.convertUahToUsd(priceUah);
                BigDecimal priceEur = core.tools.PrivatBankRateService.convertUahToEur(priceUah);

                sb.append("💰 <b>Ціна:</b> ").append(df.format(priceUah)).append(" грн\n");
                sb.append("💵 <b>Еквівалент:</b> $").append(df.format(priceUsd)).append("  |  €").append(df.format(priceEur)).append("\n");
            } else {
                sb.append("💰 <b>Ціна:</b> Договірна\n");
            }

            sb.append("📍 <b>Локація:</b> ").append(ad.getLocation() != null ? escapeHtml(ad.getLocation()) : ad.getCity().getLabel()).append("\n");
            sb.append("📅 <b>Дата:</b> ").append(ad.getDatePublished() != null ? ad.getDatePublished() : "-").append("\n");

            sb.append("📊 <b>Параметри:</b> ");
            sb.append(ad.getRoomsCount() > 0 ? ad.getRoomsCount() + " кімн." : "-").append(" | ");
            sb.append(ad.getTotalArea() > 0 ? ad.getTotalArea() + " м²" : "-").append(" | ");
            sb.append(ad.getFloor() > 0 ? ad.getFloor() + " пов." : "-").append("\n");

            sb.append("📝 <b>Опис:</b> ").append(escapeHtml(truncateDescription(ad.getDescription(), 200))).append("\n\n");
            sb.append("<a href=\"").append(ad.getUrl()).append("\">🔗 Відкрити на OLX</a>\n");

            SendMessage adMsg = new SendMessage();
            adMsg.setChatId(String.valueOf(chatId));
            adMsg.setParseMode("HTML");
            adMsg.setText(sb.toString());
            execute(adMsg);

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
                Thread.sleep(300);
            }
        }

        SendMessage controlMsg = new SendMessage();
        controlMsg.setChatId(String.valueOf(chatId));
        controlMsg.setText(String.format("📊 Показано %d із %d оголошень.", end, total));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<InlineKeyboardButton> row = new ArrayList<>();

        if (end < total) {
            InlineKeyboardButton nextButton = new InlineKeyboardButton();
            nextButton.setText("⏭ Наступні 3");
            nextButton.setCallbackData("NEXT_3");
            row.add(nextButton);
        }

        InlineKeyboardButton backButton = new InlineKeyboardButton();
        backButton.setText("⬅️ Змінити фільтр");
        backButton.setCallbackData("BACK_TO_FILTERS");
        row.add(backButton);

        InlineKeyboardButton exitButton = new InlineKeyboardButton();
        exitButton.setText("🚪 Вихід");
        exitButton.setCallbackData("EXIT_BOT");
        row.add(exitButton);

        markup.setKeyboard(Collections.singletonList(row));
        controlMsg.setReplyMarkup(markup);
        execute(controlMsg);
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String truncateDescription(String desc, int maxLength) {
        if (desc == null || desc.isEmpty()) return "Немає опису";
        if (desc.length() <= maxLength) return desc;
        return desc.substring(0, maxLength) + "...";
    }
}