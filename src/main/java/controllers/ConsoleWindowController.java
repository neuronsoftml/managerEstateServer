package controllers;

import model.ConsoleWindow;

import javax.swing.*;
import java.awt.*;

/**
 * Контролер для керування життєвим циклом та відображенням консольних GUI-вікон додатка.
 * <p>
 * Клас відповідає за створення, ініціалізацію та надання доступу до чотирьох окремих
 * графічних консолей логування (Swing-вікон):
 * <ol>
 * <li>Головна консоль — загальні системні логи додатка.</li>
 * <li>Консоль OLX — логи процесу парсингу та обробки оголошень з платформи OLX.</li>
 * <li>Консоль DimRia — логи взаємодії з платформою DIM.RIA (опціонально).</li>
 * <li>Консоль Telegram — виведення логів роботи Telegram-бота, відправки постів та коментарів.</li>
 * </ol>
 * </p>
 * <p>
 * Створення вікон відбувається потокобезпечно в спеціальному потоці обробки подій
 * Swing (Event Dispatch Thread - EDT) за допомогою методу {@link SwingUtilities#invokeAndWait}.
 * </p>
 * * @author Mykola
 */
public class ConsoleWindowController {

    /** Головна консоль для загальних логів системи */
    private ConsoleWindow mainConsole;

    /** Консоль для виведення процесу парсингу та інтеграції з OLX */
    private ConsoleWindow olxConsole;

    /** Консоль для виведення процесу парсингу та інтеграції з DIM.RIA */
    private ConsoleWindow dimRiaConsole;

    /** Консоль для відображення життєвого циклу та запитів Telegram-бота */
    private ConsoleWindow telegramBotConsole;

    /**
     * Запускає процес створення та візуалізації графічних вікон-консолей.
     * <p>
     * Використання {@code SwingUtilities.invokeAndWait} гарантує, що поточний робочий потік (Main)
     * призупинить своє виконання і зачекає, поки всі компоненти Swing будуть успішно створені та
     * відрендерені в Event Dispatch Thread (EDT). Це запобігає стану гонитви (race conditions) при спробі
     * отримати доступ до консолей одразу після запуску контролера.
     * </p>
     *
     * @throws RuntimeException якщо виникла помилка синхронізації або переривання потоку під час ініціалізації GUI
     */
    public void start() {
        try {
            // invokeAndWait → чекає поки вікна створяться в EDT, тільки потім іде далі по коду
            SwingUtilities.invokeAndWait(() -> {
                createWindowMainLog();
                createWindowOlxLog();
                // Наразі створення консолі DimRia закоментовано, за потреби можна розкоментувати:
                // createWindowDimLog();
                createWindowTelegramBotLog();
            });
        } catch (Exception e) {
            throw new RuntimeException("Не вдалося створити вікна консолей", e);
        }
    }

    /**
     * Створює та позиціонує вікно консолі для логування роботи парсера OLX.
     * Вікно ініціалізується фірмовим помаранчевим відтінком фону.
     */
    public void createWindowOlxLog() {
        // Створюємо вікно з відповідним заголовком та темно-зеленим фоном
        olxConsole = new ConsoleWindow("🟠 OLX Controller", new Color(20, 30, 20));
        // Встановлюємо координати розміщення вікна на екрані (X = 820, Y = 50)
        olxConsole.setLocation(820, 50);
    }

    /**
     * Створює та позиціонує вікно консолі для логування роботи парсера DIM.RIA.
     * Вікно ініціалізується темно-синім відтінком фону.
     */
    public void createWindowDimLog() {
        dimRiaConsole = new ConsoleWindow(" DimRia Controller", new Color(20, 20, 40));
        // Розміщуємо вікно у нижній частині екрана (X = 420, Y = 580)
        dimRiaConsole.setLocation(420, 580);
    }

    /**
     * Створює та позиціонує головне вікно системних логів.
     * Вікно ініціалізується нейтральним темно-сірим відтінком фону.
     */
    public void createWindowMainLog() {
        mainConsole = new ConsoleWindow("🏠 Головна консоль", new Color(30, 30, 30));
        // Розміщуємо в лівому верхньому кутку екрана (X = 0, Y = 50)
        mainConsole.setLocation(0, 50);
    }

    /**
     * Створює та позиціонує вікно консолі для логування дій Telegram-бота.
     * Вікно ініціалізується глибоким темно-синім відтінком фону.
     */
    public void createWindowTelegramBotLog() {
        telegramBotConsole = new ConsoleWindow("🔵 Telegram Controller", new Color(20, 20, 40));
        // Розміщуємо вікно у нижній частині екрана паралельно з іншими модулями (X = 420, Y = 580)
        telegramBotConsole.setLocation(420, 580);
    }

    // ── Геттери для доступу до об'єктів консолей з інших сервісів ─────────────

    /** @return об'єкт консолі OLX */
    public ConsoleWindow getOlxConsole() { return olxConsole; }

    /** @return об'єкт консолі DIM.RIA */
    public ConsoleWindow getDimRiaConsole() { return dimRiaConsole; }

    /** @return об'єкт головної консолі додатка */
    public ConsoleWindow getMainConsole() { return mainConsole; }

    /** @return об'єкт консолі Telegram-бота */
    public ConsoleWindow getTelegramBotConsole() { return telegramBotConsole; }
}
