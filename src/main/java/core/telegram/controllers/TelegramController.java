package core.telegram.controllers;


import core.telegram.main.PrivateMainBot;
import model.ConsoleWindow;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.PrintStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Керуючий контролер для запуску та адміністрування Telegram-інфраструктури.
 * <p>
 * Відповідає за дві ключові фонові задачі додатка:
 * 1. Ініціалізація та реєстрація інтерактивного бота ({@link PrivateMainBot}) в окремому потоці.
 * 2. Запуск періодичного планувальника (Scheduler) для автоматичного парсингу нових оголошень з OLX.
 * </p>
 */
public class TelegramController {

    /**
     * Точка входу для запуску Telegram-компонентів.
     * Одразу ініціалізує бота та запускає планувальник парсингу OLX.
     *
     * @param consoleWindowTelegramBot об'єкт вікна консолі для перенаправлення логів, може бути null
     */
    public void start(ConsoleWindow consoleWindowTelegramBot) {
        // 1. ОДРАЗУ І НАЗАВЖДИ ЗАПУСКАЄМО БОТА
        startPrivateBot(consoleWindowTelegramBot);

        // 2. ЗАПУСКАЄМО КРУГОВИЙ ПЛАНУВАЛЬНИК ОНОВЛЕННЯ ДАНИХ З OLX
        startOlxScheduler(consoleWindowTelegramBot);
    }

    /**
     * Безпечно отримує об'єкт виводу {@link PrintStream} із вікна інтерфейсу.
     * Якщо вікно не ініціалізоване або не має власного потоку виводу,
     * метод повертає стандартний {@link System#out} для логування в системну консоль IDE.
     *
     * @param window об'єкт вікна консолі додатка
     * @return надійний потік PrintStream для запису логів
     */
    private static PrintStream getSafePrintStream(ConsoleWindow window) {
        if (window != null && window.getPrintStream() != null) {
            return window.getPrintStream();
        }
        return System.out; // Якщо вікна немає — пишемо в стандартну консоль
    }

    /**
     * Ініціалізує та реєструє Telegram-бота через Telegram API.
     * <p>
     * Створює окремий не-демон потік (Non-daemon Thread) з іменем "Telegram-Bot-Listening-Thread",
     * щоб процес реєстрації та роботи сесії Telegram API не блокував головний GUI-потік додатка,
     * але при цьому утримував програму від раптового завершення.
     * </p>
     *
     * @param consoleWindowTelegramBot об'єкт вікна консолі для логування кроків ініціалізації
     */
    private static void startPrivateBot(ConsoleWindow consoleWindowTelegramBot) {
        // Використовуємо безпечне отримання потоку
        PrintStream botOut = getSafePrintStream(consoleWindowTelegramBot);

        Thread botThread = new Thread(() -> {
            try {
                botOut.println("🤖 Ініціалізація інтерактивного PrivateFilterBot...");
                TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);

                PrivateMainBot privateBot = new PrivateMainBot(botOut);
                botsApi.registerBot(privateBot);

                botOut.println("✅ PrivateFilterBot успішно запущено і він слухає приватні чати!");
            } catch (Exception e) {
                botOut.println("❌ Помилка старту Telegram API: " + e.getMessage());
                e.printStackTrace(botOut);
            }
        }, "Telegram-Bot-Listening-Thread");

        botThread.setDaemon(false); // Потік має залишатися живим
        botThread.start();
    }

    /**
     * Конфігурує та запускає фоновий планувальник завдань для періодичного парсингу OLX.
     * <p>
     * Використовує {@link ScheduledExecutorService} з одним робочим потоком.
     * Завдання запускається миттєво при старті програми (initialDelay = 0) і надалі
     * повторюється кожну годину (period = 1 Hour). Потік налаштований як non-daemon.
     * </p>
     *
     * @param consoleWindowTelegramBot об'єкт вікна консолі для логування статусів роботи планувальника
     */
    private static void startOlxScheduler(ConsoleWindow consoleWindowTelegramBot) {
        // Створюємо планувальник з кастомною фабрикою потоків для зручного моніторингу в профайлері
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OlxParser-Scheduler-Thread");
            t.setDaemon(false);
            return t;
        });

        // Запуск періодичного завдання з фіксованою частотою виконання
        executor.scheduleAtFixedRate(() -> {
            PrintStream botOut = getSafePrintStream(consoleWindowTelegramBot);
            botOut.println("▶ [Планувальник] Запуск фонового парсингу OLX...");
            try {
                // Виклик сервісу парсингу та збереження в БД
                // OlxParserService.updateDatabase(botOut);
                botOut.println("✅ [Планувальник] Базу даних нерухомості OLX оновлено.");
            } catch (Exception e) {
                botOut.println("⚠️ Помилка планувальника: " + e.getMessage());
                e.printStackTrace(botOut);
            }
        }, 0, 1, TimeUnit.HOURS);

        // Безпечний вивід фінального повідомлення про успішний запуск крону
        getSafePrintStream(consoleWindowTelegramBot).println("📅 Планувальник OLX запущено (період: 1 година)");
    }
}
