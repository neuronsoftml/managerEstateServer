package core.serverDB.controllers;

import core.olx.OlxController;

import core.tools.parser.olx.OlxImportService;
import core.tools.parser.olx.OlxStorageService;

import model.ConsoleWindow;
import model.ProjectFolder;

import java.io.PrintStream;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


/**
 * Контролер для організації регулярного оновлення бази даних оголошень нерухомості.
 * <p>
 * <b>Архітектурне рішення (Захист від зависань / Watchdog-альтернатива):</b>
 * <ul>
 * <li>Стандартний {@code Executors.newSingleThreadScheduledExecutor()} використовує один потік на всі повтори.
 * Якщо цей потік хоч раз зависне (через мережевий таймаут, блокування бази даних або GUI-дедлок),
 * метод {@code scheduleWithFixedDelay} "вмирає" назавжди без жодних повідомлень у логах.</li>
 * <li><b>Рішення:</b> Замість класичного ScheduledExecutor застосовано підхід із головним керуючим потоком-планувальником
 * (Master Scheduler Loop). Він щохвилини перевіряє стан системи, аналізує конфігураційний файл-маркер і за потреби
 * запускає ізольований робочий потік (Worker Thread) для виконання парсингу. Якщо старий воркер завис,
 * головний потік намагається його примусово перервати ({@link Thread#interrupt()}), гарантуючи стабільність роботи 24/7.</li>
 * </ul>
 * </p>
 * * @author Mykola
 */
public class UpdateBaseController {

    /** Графічна консоль для виведення логів парсингу та імпорту оголошень OLX */
    private ConsoleWindow consoleWindowOlx;

    /** Головний керуючий потік-планувальник, що працює у вічному циклі */
    private Thread masterSchedulerThread;

    /** Прапорець активності планувальника. Повинен бути {@code volatile} для гарантії видимості між потоками */
    private volatile boolean isRunning = false;

    /** Поточний робочий потік, який безпосередньо виконує парсинг та імпорт у базу */
    private Thread currentWorkerThread = null;

    /**
     * Запускає щоденне завдання періодичного оновлення бази даних та відправки постів.
     * <p>
     * При старті першочергово виконується перевірка наявності невідправлених оголошень у локальній БД
     * (на випадок аварійного завершення роботи чи відсутності інтернету під час попереднього сеансу),
     * після чого ініціалізується фоновий цикл перевірки умов перезапуску.
     * </p>
     *
     * @param consoleWindowOlx    вікно консолі для виведення логів OLX
     * @param consoleWindowDimRia вікно консолі для виведення логів DIM.RIA (зарезервовано)
     */
    public void startDailyTask(ConsoleWindow consoleWindowOlx, ConsoleWindow consoleWindowDimRia) {
        this.consoleWindowOlx = consoleWindowOlx;

        // Крок 1: При старті програми дозакидаємо старі невідправлені хвости в Telegram
        retrySendUnsentOnStartup(consoleWindowOlx.getPrintStream());

        // Крок 2: Запуск головного планувальника на базі вічного циклу
        startOlxMasterLoop();
    }

    /**
     * Створює ізольований потік для асинхронної спроби повторного надсилання оголошень,
     * які були оброблені, але не потрапили в Telegram через помилки чи обрив зв'язку.
     *
     * @param log потік виведення інформації для логування
     */
    private static void retrySendUnsentOnStartup(PrintStream log) {
        log.println("🔁 Перевірка невідправлених оголошень при старті...");
        // Створюємо окремий короткоживучий потік, щоб не блокувати головний потік ініціалізації додатка
        new Thread(() -> OlxImportService.sendUnsentToTelegram(log, true),
                "Telegram-Retry-Startup").start();
    }

    /**
     * Запускає фоновий керуючий потік (Master Scheduler Loop).
     * <p>
     * Метод є синхронізованим для запобігання випадкового створення дублікатів планувальника.
     * Цикл з періодичністю в 1 хвилину опитує конфігураційний стан (файл {@code meta.json} через сервіс)
     * щодо необхідності нового запуску імпорту.
     * </p>
     */
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

                        // Захист від підвисань: якщо старий worker ще живий — надсилаємо переривання (interrupt)
                        if (currentWorkerThread != null && currentWorkerThread.isAlive()) {
                            olxOut.println("⚠️ Попередній робочий потік застряг. Перериваємо його...");
                            currentWorkerThread.interrupt();
                        }

                        // Скидаємо маркери у файлі meta.json в false та фіксуємо поточний час старту
                        OlxStorageService.saveLastUpdateTime();

                        // Створюємо новий робочий потік "з нуля" для повної ізоляції даних під час парсингу
                        currentWorkerThread = new Thread(() -> executeParsingAndImport(olxOut), "OLX-Worker-Task");
                        currentWorkerThread.setDaemon(false); // Воркер не повинен раптово вмерти, якщо JVM не закривається
                        currentWorkerThread.start();
                    }

                    // Перевіряємо статус-файл кожну хвилину (60 000 мс)
                    Thread.sleep(60_000);

                } catch (InterruptedException e) {
                    olxOut.println("🛑 Головний шедулер перервано.");
                    Thread.currentThread().interrupt(); // Перевстановлюємо статус переривання
                    break;
                } catch (Exception ex) {
                    olxOut.println("💥 Помилка у циклі перевірки шедулера: " + ex.getMessage());
                    try {
                        // Робимо невелику паузу перед повтором у разі збою читання файлу
                        Thread.sleep(10_000);
                    } catch (InterruptedException ignored) {}
                }
            }
        }, "OLX-Master-Scheduler");

        // Робимо потік демоном, щоб він не заважав завершенню роботи додатка при закритті GUI-вікон
        masterSchedulerThread.setDaemon(true);
        masterSchedulerThread.start();
    }

    /**
     * Виконує послідовний процес збору даних: чистий парсинг сайту OLX,
     * збереження в базу даних SQLite та автоматичне надсилання нових постів у Telegram.
     * <p>
     * Метод виконується в окремому робочому потоці {@code currentWorkerThread}.
     * </p>
     *
     * @param olxOut потік виведення логів для консолі OLX
     */
    private void executeParsingAndImport(PrintStream olxOut) {
        long startTime = System.currentTimeMillis();
        olxOut.println("▶ Старт OLX циклу: " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")));

        try {
            // Крок 1: Запуск чистого парсингу OLX (збір HTML та формування JSON файлів деталей)
            boolean parsed = OlxController.start(olxOut);

            if (parsed) {
                // Шлях до папки з вивантаженими JSON файлами деталей оголошень
                String postsDir = ProjectFolder.ROOT.getName() + "/"
                        + ProjectFolder.OUTPUT_DIR.getName() + "/"
                        + ProjectFolder.OLX_DETAILS.getName();

                // Крок 2: Імпорт отриманих JSON в локальну базу даних SQLite та автоматичний виклик Telegram-транслятора
                boolean imported = OlxImportService.importFromJson(postsDir, olxOut, true);
                if (imported) {
                    startBrokenOlxCleanupTask(olxOut);
                } else {
                    olxOut.println("⚠️ Імпорт завершився з помилками — очищення битих оголошень пропущено.");
                }
            } else {
                olxOut.println("⏭ Парсинг повернув false (помилка). Позначаємо завантаження завершеним без нових даних.");
                // Оновлюємо статус-файл для запобігання вічного циклу помилок
                OlxStorageService.updateStateStatus(true, false);
                // Навіть якщо парсинг впав, пробуємо надіслати те, що раніше "застрягло" в базі
                OlxImportService.sendUnsentToTelegram(olxOut, true);
            }

        } catch (Throwable t) {
            olxOut.println("💥 [Критична помилка в робочому потоці]: " + t.getMessage());
            t.printStackTrace(olxOut);
            // У разі крашу обов'язково розблоковуємо статус шедулера, щоб система не зависла в стані "завантажується"
            OlxStorageService.updateStateStatus(true, false);
        } finally {
            // Розрахунок витраченого часу на повний цикл обробки
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            olxOut.printf("✅ Робочий потік завантаження закінчив роботу за %d хв %d сек.%n", elapsed / 60, elapsed % 60);
            olxOut.println("▶ ══════════════════════════════════════");
        }
    }

    /**
     * Допоміжний метод для безпечного виведення повідомлень у консоль або стандартний системний вивід.
     *
     * @param message текст повідомлення
     */
    private void logToConsole(String message) {
        if (consoleWindowOlx != null && consoleWindowOlx.getPrintStream() != null) {
            consoleWindowOlx.getPrintStream().println(message);
        } else {
            System.out.println(message);
        }
    }

    /**
     * Примусово зупиняє роботу регулярного планувальника.
     * Викликається при виході з програми або вимкненні відповідного сервісу в GUI.
     */
    public void stopDailyTask() {
        this.isRunning = false;
        if (masterSchedulerThread != null) {
            masterSchedulerThread.interrupt();
        }
    }

    /**
     * Запускає фоновий потік для очищення бази даних від битих посилань.
     * @param log потік виведення логів
     */
    private void startBrokenOlxCleanupTask(PrintStream log) {
        new Thread(() -> {
            log.println("🧹 [Cleanup] Перевірка JSON-деталей OLX на биті посилання...");
            String report = OlxStorageService.cleanupBrokenDetails(log);
            log.println("🧹 [Cleanup] Результат: " + report);
        }, "OLX-Details-Cleanup-Task").start();
    }
}
