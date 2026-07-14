package core.telegram.main;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class InlineKeyboardFactory {
    // Створення вертикального списку кнопок з мапи (Назва -> Callback)
    public static InlineKeyboardMarkup createVertical(Map<String, String> buttons, String backText, String backCallback) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (Map.Entry<String, String> entry : buttons.entrySet()) {
            InlineKeyboardButton b = new InlineKeyboardButton();
            b.setText(entry.getKey());
            b.setCallbackData(entry.getValue());
            rows.add(Collections.singletonList(b));
        }

        if (backText != null && backCallback != null) {
            InlineKeyboardButton bBack = new InlineKeyboardButton();
            bBack.setText(backText);
            bBack.setCallbackData(backCallback);
            rows.add(Collections.singletonList(bBack));
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    // Створення горизонтального рядка кнопок (наприклад, для кімнат або пагінації)
    public static List<InlineKeyboardButton> createRow(String[][] buttonData) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (String[] data : buttonData) {
            InlineKeyboardButton b = new InlineKeyboardButton();
            b.setText(data[0]);
            b.setCallbackData(data[1]);
            row.add(b);
        }
        return row;
    }
}
