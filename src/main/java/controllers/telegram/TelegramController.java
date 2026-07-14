package controllers.telegram;


import core.telegram.main.PrivateMainBot;
import model.ConsoleWindow;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.PrintStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TelegramController {

    public  void start(ConsoleWindow consoleWindowTelegramBot) {
        // 1. ОДРАЗУ І НАЗАВЖДИ ЗАПУСКАЄМО БОТА
        startPrivateBot(consoleWindowTelegramBot);

        // 2. ЗАПУСКАЄМО КРУГОВИЙ ПЛАНУВАЛЬНИК ОНОВЛЕННЯ ДАНИХ З OLX
        startOlxScheduler(consoleWindowTelegramBot);
    }

    /**
     * Безпечно отримує PrintStream з вікна або повертає дефолтний System.out
     */
    private static PrintStream getSafePrintStream(ConsoleWindow window) {
        if (window != null && window.getPrintStream() != null) {
            return window.getPrintStream();
        }
        return System.out; // Якщо вікна немає — пишемо в стандартну консоль IDEA
    }

    private static void startPrivateBot(ConsoleWindow consoleWindowTelegramBot) {
        // Використовуємо безпечне отримання потоку
        PrintStream botOut = getSafePrintStream(consoleWindowTelegramBot);

        Thread botThread = new Thread(() -> {
            try {
                botOut.println("🤖 Ініціалізація інтерактивного PrivateFilterBot...");
                TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);

                PrivateMainBot privateBot = new PrivateMainBot();
                botsApi.registerBot(privateBot);

                botOut.println("✅ PrivateFilterBot успішно запущено і він слухає приватні чати!");
            } catch (Exception e) {
                botOut.println("❌ Помилка старту Telegram API: " + e.getMessage());
                e.printStackTrace(botOut);
            }
        }, "Telegram-Bot-Listening-Thread");

        botThread.setDaemon(false);
        botThread.start();
    }

    private static void startOlxScheduler(ConsoleWindow consoleWindowTelegramBot) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OlxParser-Scheduler-Thread");
            t.setDaemon(false);
            return t;
        });

        executor.scheduleAtFixedRate(() -> {
            PrintStream botOut = getSafePrintStream(consoleWindowTelegramBot);
            botOut.println("▶ [Планувальник] Запуск фонового парсингу OLX...");
            try {
                // OlxParserService.updateDatabase(botOut);
                botOut.println("✅ [Планувальник] Базу даних нерухомості OLX оновлено.");
            } catch (Exception e) {
                botOut.println("⚠️ Помилка планувальника: " + e.getMessage());
                e.printStackTrace(botOut);
            }
        }, 0, 1, TimeUnit.HOURS);

        // Безпечний вивід фінального повідомлення
        getSafePrintStream(consoleWindowTelegramBot).println("📅 Планувальник OLX запущено (період: 1 година)");
    }
}
