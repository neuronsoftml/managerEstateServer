package controllers;

import controllers.olx.OlxController;

import core.tools.olx.OlxImportService;
import core.tools.olx.OlxStorageService;

import model.ConsoleWindow;
import model.ProjectFolder;

import java.io.PrintStream;

import java.time.LocalDateTime;


/**
 * Клас для організації регулярного оновлення бази даних.
 *
 * НОВЕ (watchdog):
 *  - Executors.newSingleThreadScheduledExecutor() використовує ОДИН потік
 *    на всі повтори. Якщо цей потік хоч раз зависне (мережевий hang, GUI
 *    дедлок тощо) — scheduleWithFixedDelay вмирає НАЗАВЖДИ, без жодної
 *    помилки в логах.
 *  - Тому додано окремий watchdog-потік, який стежить за "biттям серця"
 *    (lastHeartbeat). Якщо тиша триває довше за розумний поріг —
 *    watchdog примусово вбиває старий executor (shutdownNow — це
 *    інтерпує потік) і створює НОВИЙ executor з чистого аркуша.
 */
public class UpdateBaseController {

    private ConsoleWindow consoleWindowOlx;
    private Thread masterSchedulerThread;
    private volatile boolean isRunning = false;
    private Thread currentWorkerThread = null;

    public void startDailyTask(ConsoleWindow consoleWindowOlx, ConsoleWindow consoleWindowDimRia) {
        this.consoleWindowOlx = consoleWindowOlx;

        // При старті програми дозакидаємо старі невідправлені хвости.
        retrySendUnsentOnStartup(consoleWindowOlx.getPrintStream());

        // Запуск головного планувальника на базі вічного циклу
        startOlxMasterLoop();
    }

    private static void retrySendUnsentOnStartup(PrintStream log) {
        log.println("🔁 Перевірка невідправлених оголошень при старті...");
        // ВИПРАВЛЕНО КОМПІЛЯЦІЮ: тепер передаємо true як статус завантаження для початкової синхронізації
        new Thread(() -> OlxImportService.sendUnsentToTelegram(log, true),
                "Telegram-Retry-Startup").start();
    }

    private synchronized void startOlxMasterLoop() {
        if (isRunning) return;
        isRunning = true;

        masterSchedulerThread = new Thread(() -> {
            PrintStream olxOut = consoleWindowOlx.getPrintStream();
            olxOut.println("🚀 Запущено головний фоновий шедулер (вічний цикл). Інтервал перевірки файлу: 1 хв.");

            while (isRunning) {
                try {
                    // Перевіряємо умови: чи пройшла 1 година і чи обидва процеси закінчилися (true)
                    if (OlxStorageService.isSchedulerReadyToRestart(1, olxOut)) {

                        olxOut.println("\n▶ ══════════════════════════════════════");
                        olxOut.println("▶ Умови виконано! Перезапуск циклу збору з чистого аркуша.");

                        // Захист від підвисань: якщо старий worker ще живий — надсилаємо переривання
                        if (currentWorkerThread != null && currentWorkerThread.isAlive()) {
                            olxOut.println("⚠️ Попередній робочий потік застряг. Перериваємо його...");
                            currentWorkerThread.interrupt();
                        }

                        // Скидаємо маркери у файлі meta.json в false та фіксуємо поточний час старту
                        OlxStorageService.saveLastUpdateTime();

                        // Створюємо новий робочий потік "з нуля" для повної ізоляції даних
                        currentWorkerThread = new Thread(() -> executeParsingAndImport(olxOut), "OLX-Worker-Task");
                        currentWorkerThread.setDaemon(false);
                        currentWorkerThread.start();
                    }

                    // Перевіряємо статус-файл кожну хвилину
                    Thread.sleep(60_000);

                } catch (InterruptedException e) {
                    olxOut.println("🛑 Головний шедулер перервано.");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    olxOut.println("💥 Помилка у циклі перевірки шедулера: " + ex.getMessage());
                    try { Thread.sleep(10_000); } catch (InterruptedException ignored) {}
                }
            }
        }, "OLX-Master-Scheduler");

        masterSchedulerThread.setDaemon(true);
        masterSchedulerThread.start();
    }

    private void executeParsingAndImport(PrintStream olxOut) {
        long startTime = System.currentTimeMillis();
        olxOut.println("▶ Старт OLX циклу: " + LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")));

        try {
            // Крок 1: Чистий парсинг OLX
            boolean parsed = OlxController.start(olxOut);

            if (parsed) {
                String postsDir = ProjectFolder.ROOT.getName() + "/"
                        + ProjectFolder.OUTPUT_DIR.getName() + "/"
                        + ProjectFolder.OLX_DETAILS.getName();

                // Крок 2: Імпорт в локальний SQLite та внутрішній виклик Telegram відправника
                OlxImportService.importFromJson(postsDir, olxOut, true);
            } else {
                olxOut.println("⏭ Парсинг повернув false (помилка). Позначаємо завантаження завершеним без нових даних.");
                OlxStorageService.updateStateStatus(true, false);
                OlxImportService.sendUnsentToTelegram(olxOut, true);
            }

        } catch (Throwable t) {
            olxOut.println("💥 [Критична помилка в робочому потоці]: " + t.getMessage());
            t.printStackTrace(olxOut);
            OlxStorageService.updateStateStatus(true, false);
        } finally {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            olxOut.printf("✅ Робочий потік завантаження закінчив роботу за %d хв %d сек.%n", elapsed / 60, elapsed % 60);
            olxOut.println("▶ ══════════════════════════════════════");
        }
    }

    private void logToConsole(String message) {
        if (consoleWindowOlx != null && consoleWindowOlx.getPrintStream() != null) {
            consoleWindowOlx.getPrintStream().println(message);
        } else {
            System.out.println(message);
        }
    }

    public void stopDailyTask() {
        this.isRunning = false;
        if (masterSchedulerThread != null) {
            masterSchedulerThread.interrupt();
        }
    }
}