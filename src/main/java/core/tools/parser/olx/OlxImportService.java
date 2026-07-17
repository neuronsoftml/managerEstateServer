package core.tools.parser.olx;

import model.Announcement;
import core.serverDB.sqlite.ProjectDatabaseService;

import java.io.File;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Сервіс імпорту: читає JSON файли → зберігає в SQLite → надсилає в Telegram.
 * <p>
 * Клас відповідає за зчитування локально збережених парсером оголошень нерухомості з OLX,
 * перевірку їх унікальності, запис у реляційну базу даних та подальшу чергову публікацію
 * у Telegram-канал через окремий асинхронний потік.
 * </p>
 * * <p><b>Виправлено та оптимізовано:</b></p>
 * <ul>
 * <li>Telegram-відправка винесена в окремий список і виконується суворо після імпорту.</li>
 * <li>Примусові паузи {@code Thread.sleep} більше не блокують головний цикл імпорту та парсингу.</li>
 * <li>Використовується окремий фоновий потік для Telegram, щоб уникнути зависання головного потоку {@code OLX-Thread}.</li>
 * </ul>
 * * @author Mykola
 */
public class OlxImportService {

    /**
     * Максимальний вік оголошення в днях, дозволений для публікації в Telegram.
     * Оголошення, що старіші за це значення, імпортуються в БД, але маркуються як надіслані,
     * щоб не спамити застарілою інформацією в канал.
     */
    private static final int TELEGRAM_MAX_AGE_DAYS = 1;

    /**
     * Стандартний формат дат для парсингу публікацій з оголошень (дд.мм.рррр).
     */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Здійснює покроковий імпорт оголошень з JSON-файлів у базу даних SQLite.
     * <p>
     * Метод ініціалізує таблиці, зчитує всі збережені файли оголошень з вказаної директорії,
     * фільтрує застарілі записи і по черзі записує нові об'єкти в базу даних.
     * Після завершення імпорту запускає асинхронну відправку черги повідомлень у Telegram.
     * </p>
     *
     * @param postsDir           шлях до директорії з файлами детальної інформації оголошень (post_*.json)
     * @param log                потік для виведення логів (наприклад, у консоль або GUI)
     * @param onlyNew            якщо {@code true}, зчитуються лише ті файли, яких ще немає в реєстрі бази даних
     */
    public static void importFromJson(String postsDir, PrintStream log, boolean onlyNew) {
        log.println("\n=== ЕТАП 3: Імпорт у базу даних SQLite ===");

        // Гарантуємо існування потрібних таблиць у БД перед початком запису
        ProjectDatabaseService.initTables();

        File directory = new File(postsDir);
        // Парсимо JSON-файли з папки та конвертуємо їх у об'єкти Announcement
        List<Announcement> announcements = OlxDetailsParser.parseDirectory(directory, onlyNew);

        if (announcements.isEmpty()) {
            log.println("📭 Немає нових записів для імпорту.");
            // Роботу із завантаження файлів завершено успішно (завантажено = true, відправлено = false)
            OlxStorageService.updateStateStatus(true, false);
            // Навіть якщо нових немає, перевіряємо, чи не залишилось старих невідправлених у БД
            sendUnsentToTelegram(log, true);
            return;
        }

        log.printf("📥 Починаємо імпорт %d оголошень...%n", announcements.size());

        int success = 0;
        int failed = 0;
        int tooOld = 0;

        for (int i = 0; i < announcements.size(); i++) {
            Announcement a = announcements.get(i);
            // Спроба зберегти оголошення в SQLite (метод повертає false, якщо сталася помилка або запис дублюється)
            boolean saved = ProjectDatabaseService.saveAnnouncement(a);

            if (saved) {
                success++;

                // Якщо оголошення занадто старе за датою публікації, блокуємо його відправку в TG
                if (isTooOldForTelegram(a)) {
                    // Маркуємо в БД як "вже надіслане", щоб воно ніколи не потрапило у вибірку відправника
                    ProjectDatabaseService.markAsSentToTelegram(a.getId());
                    tooOld++;
                }

                // Логуємо проміжний прогрес кожні 50 успішних записів або на останньому елементі
                if (success % 50 == 0 || i == announcements.size() - 1) {
                    log.printf("  💾 Збережено: %d / %d%n", success, announcements.size());
                }
            } else {
                failed++;
            }
        }

        log.printf("✅ Імпорт: %d збережено | %d помилок | %d старших %d днів (пропущено для TG)%n",
                success, failed, tooOld, TELEGRAM_MAX_AGE_DAYS);

        // Оновлюємо статус: етап завантаження та збереження у файлову систему й БД повністю завершено
        OlxStorageService.updateStateStatus(true, false);

        // Запускаємо фонову відправку накопичених невідправлених оголошень у Telegram
        sendUnsentToTelegram(log, true);
    }

    /**
     * Перевіряє, чи не є оголошення застарілим для публікації в Телеграм-каналі.
     *
     * @param a об'єкт оголошення для аналізу
     * @return {@code true}, якщо оголошення було опубліковано раніше ніж {@code TELEGRAM_MAX_AGE_DAYS} тому;
     * {@code false} у протилежному випадку або при неможливості розпарсити дату
     */
    private static boolean isTooOldForTelegram(Announcement a) {
        String dateStr = a.getDatePublished();
        if (dateStr == null || dateStr.isBlank()) {
            return false;
        }
        try {
            LocalDate published = LocalDate.parse(dateStr, DATE_FORMAT);
            LocalDate threshold = LocalDate.now().minusDays(TELEGRAM_MAX_AGE_DAYS);
            // Повертає true, якщо дата публікації йде строго до визначеної межі безпеки
            return published.isBefore(threshold);
        } catch (Exception e) {
            // У разі аномального формату дати безпечніше дозволити публікацію
            return false;
        }
    }

    /**
     * Відправляє накопичені невідправлені оголошення в Telegram без блокування головного планувальника задач.
     * <p>
     * Метод робить вибірку з бази даних SQLite, ініціалізує фоновий потік демона (Thread),
     * який з невеликим таймаутом безпечно публікує пости один за одним, не навантажуючи ліміти Telegram API.
     * </p>
     *
     * @param log                 потік виведення текстових повідомлень про роботу сервісу
     * @param currentDownloadState поточний стан успішності етапу завантаження (використовується для оновлення метафайлу стану)
     */
    public static void sendUnsentToTelegram(PrintStream log, boolean currentDownloadState) {

        log.println("⚠️ [Telegram-Sender] Робота сервісу відправки тимчасово призупинена.");

        // Щоб не порушити логіку оновлення стану, якщо це потрібно для інших частин програми:
        OlxStorageService.updateStateStatus(currentDownloadState, true);

        /*
        // Отримуємо з бази даних список усіх записів, де flag_sent_telegram = 0
        List<Announcement> unsent = ProjectDatabaseService.getUnsentAnnouncements();

        if (unsent.isEmpty()) {
            log.println("📨 Немає невідправлених оголошень для Telegram.");
            // Позначаємо, що обидва етапи (і завантаження, і публікація) виконані успішно
            OlxStorageService.updateStateStatus(currentDownloadState, true);
            return;
        }

        log.printf("📨 Знайдено невідправлених: %d — запускаємо Telegram-Sender-Thread%n", unsent.size());

        // Створюємо окремий фоновий потік, щоб робота з мережею та затримки не блокували UI або головні процеси парсингу
        Thread telegramThread = new Thread(() -> {
            int sent = 0;
            int tgFailed = 0;
            try {
                for (Announcement a : unsent) {
                    try {
                        // Отримуємо синглтон-сервіс сповіщень Telegram з логуванням
                        TelegramNotificationService telegramNotificationService =
                                TelegramNotificationService.getTelegramNotificationService(log);

                        // Відправляємо медіагрупу/текст у канал
                        telegramNotificationService.sendNewAnnouncement(a);

                        // Обов'язково фіксуємо в БД успішну відправку, щоб уникнути дублювання при рестартах
                        ProjectDatabaseService.markAsSentToTelegram(a.getId());
                        sent++;

                        // Затримка 3.5 секунди між публікаціями для запобігання помилці "429 Too Many Requests"
                        Thread.sleep(3_500);
                    } catch (InterruptedException ie) {
                        // Відновлюємо прапорець перерваного потоку та виходимо з циклу
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        tgFailed++;
                        log.printf("❌ [Telegram] ID=%s: %s%n", a.getId(), e.getMessage());
                    }
                }
            } finally {
                log.printf("📨 [Telegram] Відправлено: %d | Помилок: %d%n", sent, tgFailed);
                // Після завершення надсилання (успішного чи аварійного) оновлюємо статус публікації на TRUE
                OlxStorageService.updateStateStatus(currentDownloadState, true);
            }
        }, "Telegram-Sender-Thread");

        // Робимо потік демоном, щоб він автоматично завершувався при закритті головного додатку
        telegramThread.setDaemon(true);
        telegramThread.start();

         */
    }

}

