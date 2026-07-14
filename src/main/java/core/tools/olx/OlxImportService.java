package core.tools.olx;

import core.telegram.TelegramNotificationService;
import model.Announcement;
import sqlite.ProjectDatabaseService;

import java.io.File;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Сервіс імпорту: читає JSON файли → SQLite → Telegram.
 *
 * ВИПРАВЛЕНО:
 *  - Telegram відправка винесена в окремий список після імпорту
 *  - Thread.sleep не блокує основний цикл імпорту
 *  - Окремий потік для Telegram щоб не зависав OLX-Thread
 */
public class OlxImportService {
    private static final int TELEGRAM_MAX_AGE_DAYS = 1;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static void importFromJson(String postsDir, PrintStream log, boolean onlyNew) {
        log.println("\n=== ЕТАП 3: Імпорт у базу даних SQLite ===");

        ProjectDatabaseService.initTables();

        File directory = new File(postsDir);
        List<Announcement> announcements = OlxDetailsParser.parseDirectory(directory, onlyNew);

        if (announcements.isEmpty()) {
            log.println("📭 Немає нових записів для імпорту.");
            // Роботу з завантаження завершено успішно
            OlxStorageService.updateStateStatus(true, false);
            sendUnsentToTelegram(log, true);
            return;
        }

        log.printf("📥 Починаємо імпорт %d оголошень...%n", announcements.size());

        int success = 0, failed = 0, tooOld = 0;
        for (int i = 0; i < announcements.size(); i++) {
            Announcement a = announcements.get(i);
            boolean saved = ProjectDatabaseService.saveAnnouncement(a);

            if (saved) {
                success++;

                if (isTooOldForTelegram(a)) {
                    ProjectDatabaseService.markAsSentToTelegram(a.getId());
                    tooOld++;
                }

                if (success % 50 == 0 || i == announcements.size() - 1) {
                    log.printf("  💾 Збережено: %d / %d%n", success, announcements.size());
                }
            } else {
                failed++;
            }
        }

        log.printf("✅ Імпорт: %d збережено | %d помилок | %d старших %d днів (пропущено для TG)%n",
                success, failed, tooOld, TELEGRAM_MAX_AGE_DAYS);

        // Фіксуємо у файлі, що етап завантаження/імпорту завершено
        OlxStorageService.updateStateStatus(true, false);

        // Запускаємо фонову відправку невідправленого
        sendUnsentToTelegram(log, true);
    }

    private static boolean isTooOldForTelegram(Announcement a) {
        String dateStr = a.getDatePublished();
        if (dateStr == null || dateStr.isBlank()) return false;
        try {
            LocalDate published = LocalDate.parse(dateStr, DATE_FORMAT);
            LocalDate threshold = LocalDate.now().minusDays(TELEGRAM_MAX_AGE_DAYS);
            return published.isBefore(threshold);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Відправляє оголошення в Telegram без блокування головного шедулера.
     */
    public static void sendUnsentToTelegram(PrintStream log, boolean currentDownloadState) {
        List<Announcement> unsent = ProjectDatabaseService.getUnsentAnnouncements();

        if (unsent.isEmpty()) {
            log.println("📨 Немає невідправлених оголошень для Telegram.");
            OlxStorageService.updateStateStatus(currentDownloadState, true);
            return;
        }

        log.printf("📨 Знайдено невідправлених: %d — запускаємо Telegram-Sender-Thread%n", unsent.size());

        Thread telegramThread = new Thread(() -> {
            int sent = 0, tgFailed = 0;
            try {
                for (Announcement a : unsent) {
                    try {
                        TelegramNotificationService telegramNotificationService = TelegramNotificationService.getTelegramNotificationService(log);
                        telegramNotificationService.sendNewAnnouncement(a);
                        ProjectDatabaseService.markAsSentToTelegram(a.getId());
                        sent++;
                        Thread.sleep(3_500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        tgFailed++;
                        log.printf("❌ [Telegram] ID=%s: %s%n", a.getId(), e.getMessage());
                    }
                }
            } finally {
                log.printf("📨 [Telegram] Відправлено: %d | Помилок: %d%n", sent, tgFailed);
                // Після завершення надсилання (успішного чи збійного) маркуємо процес публікації як TRUE
                OlxStorageService.updateStateStatus(currentDownloadState, true);
            }
        }, "Telegram-Sender-Thread");

        telegramThread.setDaemon(true);
        telegramThread.start();
    }
}
