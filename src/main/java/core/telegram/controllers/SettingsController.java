package core.telegram.controllers;

import core.serverDB.sqlite.ProjectDatabaseService;
import core.telegram.main.InlineKeyboardFactory;
import model.SavedSearchFilter;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.LinkedHashMap;
import java.util.Optional;

/** Меню керування єдиним активним фільтром сповіщень користувача. */
public class SettingsController {
    private final TelegramSender sender;

    public SettingsController(TelegramSender sender) { this.sender = sender; }

    public void show(long chatId) {
        try {
            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setParseMode("HTML");
            message.setText(renderText(chatId));
            message.setReplyMarkup(keyboard(chatId));
            sender.executeMethod(message);
        } catch (Exception ignored) { }
    }

    public void handle(long chatId, int messageId, String data) {
        if (data.equals("SETTINGS_TOGGLE")) {
            ProjectDatabaseService.getSavedSearchFilter(chatId).ifPresent(filter ->
                    ProjectDatabaseService.setNotificationsEnabled(chatId, !filter.isNotificationsEnabled()));
        } else if (data.equals("SETTINGS_DELETE")) {
            ProjectDatabaseService.deleteSavedSearchFilter(chatId);
        } else if (data.equals("SETTINGS_EDIT")) {
            showNewFilterPrompt(chatId);
            return;
        }
        edit(chatId, messageId);
    }

    private String renderText(long chatId) {
        Optional<SavedSearchFilter> optional = ProjectDatabaseService.getSavedSearchFilter(chatId);
        if (optional.isEmpty()) return "⚙️ <b>Налаштування</b>\n\nАктивного фільтра сповіщень поки немає.";
        SavedSearchFilter f = optional.get();
        return "⚙️ <b>Налаштування сповіщень</b>\n\n"
                + "🏷 Угода: " + label(f.getDealType()) + "\n"
                + "🏠 Тип: " + label(f.getPropertyType()) + "\n"
                + "📍 Місто: " + label(f.getCity()) + "\n"
                + "🔢 Кімнати: " + (f.getRooms() == null ? "Будь-які" : (f.isRoomsMinimum() ? f.getRooms() + "+" : f.getRooms())) + "\n"
                + "💰 Бюджет: " + price(f) + "\n"
                + "🔔 Сповіщення: " + (f.isNotificationsEnabled() ? "увімкнено" : "вимкнено");
    }

    private InlineKeyboardMarkup keyboard(long chatId) {
        LinkedHashMap<String, String> buttons = new LinkedHashMap<>();
        Optional<SavedSearchFilter> filter = ProjectDatabaseService.getSavedSearchFilter(chatId);
        if (filter.isPresent()) {
            buttons.put(filter.get().isNotificationsEnabled() ? "🔕 Вимкнути сповіщення" : "🔔 Увімкнути сповіщення", "SETTINGS_TOGGLE");
            buttons.put("📝 Змінити фільтр", "SETTINGS_EDIT");
            buttons.put("🗑 Видалити фільтр", "SETTINGS_DELETE");
        } else {
            buttons.put("🔍 Створити фільтр", "SETTINGS_EDIT");
        }
        return InlineKeyboardFactory.createVertical(buttons, "⬅️ Назад в меню", "BACK_TO_MENU");
    }

    private void edit(long chatId, int messageId) {
        try {
            EditMessageText message = new EditMessageText();
            message.setChatId(String.valueOf(chatId));
            message.setMessageId(messageId);
            message.setParseMode("HTML");
            message.setText(renderText(chatId));
            message.setReplyMarkup(keyboard(chatId));
            sender.executeMethod(message);
        } catch (Exception ignored) { }
    }

    private void showNewFilterPrompt(long chatId) {
        try {
            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText("Налаштуйте нові параметри пошуку. Після перегляду результатів натисніть 🔔 для збереження.");
            message.setReplyMarkup(InlineKeyboardFactory.createVertical(new LinkedHashMap<>() {{
                put("🔍 Налаштувати фільтр", "SEARCH_RESET_FILTER");
            }}, null, null));
            sender.executeMethod(message);
        } catch (Exception ignored) { }
    }

    private String price(SavedSearchFilter f) {
        if (f.getMinPriceUsd() == null) return "Будь-який";
        return f.getMinPriceUsd() + "–" + (f.getMaxPriceUsd() == null ? "∞" : f.getMaxPriceUsd()) + " $";
    }

    private String label(Object value) {
        if (value == null) return "—";
        if (value instanceof model.DealType item) return item.getLabel();
        if (value instanceof model.PropertyType item) return item.getLabel();
        if (value instanceof model.City item) return item.getLabel();
        return value.toString();
    }
}
