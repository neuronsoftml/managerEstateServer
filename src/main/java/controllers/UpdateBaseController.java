package controllers;

import controllers.olx.OlxController;
import core.tools.dimRia.DimRiaImportService;
import core.tools.olx.OlxImportService;
import materials.Emoji;
import model.ConsoleWindow;
import model.ProjectFolder;

import java.io.PrintStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Клас для організації регулярного оновлення бази даних.
 *
 * ВИПРАВЛЕНО:
 *  - AtomicBoolean isRunning — захист від паралельного запуску
 *  - scheduleWithFixedDelay замість scheduleAtFixedRate —
 *    наступний старт відраховується ПІСЛЯ завершення попереднього
 */
public class UpdateBaseController {

    public void startDailyTask(ConsoleWindow consoleWindowOlx, ConsoleWindow consoleWindowDimRia) {
        // При старті — одразу дозакидаємо невідправлені (якщо були збої раніше)
        retrySendUnsentOnStartup(consoleWindowOlx.getPrintStream());
        startOlxScheduler(consoleWindowOlx);
    }

    /**
     * Викликається ОДИН РАЗ при старті програми.
     * Знаходить всі оголошення де sent_to_telegram = 0 і дозакидає їх.
     */
    private static void retrySendUnsentOnStartup(PrintStream log) {
        log.println("🔁 Перевірка невідправлених оголошень при старті...");
        // Запускаємо в окремому потоці щоб не блокувати старт
        new Thread(() -> OlxImportService.sendUnsentToTelegram(log),
                "Telegram-Retry-Startup").start();
    }

    private static void startOlxScheduler(ConsoleWindow consoleWindowOlx) {
        AtomicBoolean isRunning = new AtomicBoolean(false);

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OLX-Thread");
            t.setDaemon(false);
            t.setUncaughtExceptionHandler((thread, ex) -> {
                System.err.println("💥 [OLX-Thread] " + ex.getMessage());
                isRunning.set(false);
            });
            return t;
        });

        // scheduleWithFixedDelay — наступний старт відраховується ПІСЛЯ завершення
        executor.scheduleWithFixedDelay(() -> {
            if (!isRunning.compareAndSet(false, true)) {
                System.out.println("⏭ [OLX] Попередній цикл ще виконується — пропускаємо.");
                return;
            }

            PrintStream olxOut = consoleWindowOlx.getPrintStream();
            long startTime = System.currentTimeMillis();
            olxOut.println("▶ ══════════════════════════════════════");
            olxOut.println("▶ Старт OLX циклу: " + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")));

            try {
                // Етап 1 + 2: парсинг → JSON
                OlxController.start(olxOut);

                // Етап 3: JSON → SQLite + Telegram
                String postsDir = ProjectFolder.ROOT.getName() + "/"
                        + ProjectFolder.OUTPUT_DIR.getName() + "/"
                        + ProjectFolder.OLX_DETAILS.getName();
                OlxImportService.importFromJson(postsDir, olxOut, true);

            } catch (Exception e) {
                olxOut.println(Emoji.ERROR.view() + " Помилка циклу: " + e.getMessage());
                e.printStackTrace(olxOut);
            } finally {
                isRunning.set(false);
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                olxOut.printf("✅ Цикл завершено за %d хв %d сек. Наступний через 1 год.%n",
                        elapsed / 60, elapsed % 60);
                olxOut.println("▶ ══════════════════════════════════════");
            }

        }, 0, 1, TimeUnit.HOURS);

        System.out.println(Emoji.CALENDAR.view() + " OLX планувальник запущено.");
    }
}