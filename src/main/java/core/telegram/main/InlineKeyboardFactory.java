package core.telegram.main;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Фабрика для зручного створення вбудованих клавіатур (Inline Keyboards) в Telegram.
 * <p>
 * Клас спрощує рутинну роботу з формування структури {@link InlineKeyboardMarkup}, яка
 * вимагає передачі списку рядків кнопок (список списків {@code List<List<InlineKeyboardButton>>}).
 * </p>
 * <p>
 * <b>Основні можливості:</b>
 * <ul>
 * <li>Генерація вертикальних списків меню на основі {@link Map} (де кожна кнопка займає окремий рядок).</li>
 * <li>Автоматичне додавання уніфікованої кнопки "Назад" у кінець списку.</li>
 * <li>Створення горизонтальних рядків із кнопок (наприклад, для кнопок пагінації "Вперед/Назад" або вибору кімнат).</li>
 * </ul>
 * </p>
 * * @author Mykola
 */
public class InlineKeyboardFactory {

    /**
     * Створює вертикальну клавіатуру, де кожна кнопка розташовується на новому рядку (одна під одною).
     * <p>
     * Опціонально в кінець списку додає кнопку повернення ("Назад") із заданим callback-сигналом.
     * </p>
     *
     * @param buttons      мапа кнопок, де <b>Ключ (Key)</b> — це відображуваний текст на кнопці,
     * а <b>Значення (Value)</b> — дані зворотного виклику (Callback Data) для бота
     * @param backText     текст для кнопки повернення (наприклад, "⬅️ Назад"); якщо {@code null}, кнопка не додається
     * @param backCallback callback-дані для кнопки повернення; якщо {@code null}, кнопка не додається
     * @return об'єкт {@link InlineKeyboardMarkup}, готовий для надсилання користувачу через Telegram Bot API
     */
    public static InlineKeyboardMarkup createVertical(Map<String, String> buttons, String backText, String backCallback) {
        // Створюємо головну структуру клавіатури — список рядків (rows)
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Ітеруємося по мапі та створюємо для кожного елемента окремий рядок з однією кнопкою
        for (Map.Entry<String, String> entry : buttons.entrySet()) {
            InlineKeyboardButton b = new InlineKeyboardButton();
            b.setText(entry.getKey());             // Текст кнопки, який бачить користувач
            b.setCallbackData(entry.getValue());   // Прихований ідентифікатор події для бота

            // Collections.singletonList створює незмінний список з одного елемента (економія пам'яті)
            rows.add(Collections.singletonList(b));
        }

        // Додаємо кнопку "Назад" в самий кінець списку меню, якщо передані параметри
        if (backText != null && backCallback != null) {
            InlineKeyboardButton bBack = new InlineKeyboardButton();
            bBack.setText(backText);
            bBack.setCallbackData(backCallback);
            rows.add(Collections.singletonList(bBack));
        }

        // Загортаємо сформовані рядки кнопок у фінальний об'єкт розмітки Telegram
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    /**
     * Створює один горизонтальний рядок кнопок (наприклад, для пагінації "◀️ 1 | 2 | 3 ▶️").
     * <p>
     * Отриманий список кнопок можна додати як окремий рядок до будь-якої іншої клавіатури.
     * </p>
     *
     * @param buttonData двовимірний масив рядків, де кожен елемент містить пару:
     * {@code { "Текст кнопки", "Callback-дані" }}
     * @return список кнопок {@link List<InlineKeyboardButton>}, розташованих горизонтально в один ряд
     */
    public static List<InlineKeyboardButton> createRow(String[][] buttonData) {
        List<InlineKeyboardButton> row = new ArrayList<>();

        // Почергово створюємо кнопки та додаємо їх в межах одного списку (рядка)
        for (String[] data : buttonData) {
            InlineKeyboardButton b = new InlineKeyboardButton();
            b.setText(data[0]);           // Назва кнопки (перший елемент масиву)
            b.setCallbackData(data[1]);   // Callback-дані (другий елемент масиву)
            row.add(b);
        }
        return row;
    }
}
