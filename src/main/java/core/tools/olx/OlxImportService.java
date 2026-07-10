package core.tools.olx;

import core.telegram.TelegramNotificationService;
import model.Announcement;
import sqlite.ProjectDatabaseService;

import java.io.File;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    // Оголошення старші цього порогу — в БД зберігаємо, але в Telegram НЕ відправляємо
    private static final int TELEGRAM_MAX_AGE_DAYS = 3;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static void importFromJson(String postsDir, PrintStream log, boolean onlyNew) {
        log.println("\n=== ЕТАП 3: Імпорт у базу даних SQLite ===");

        ProjectDatabaseService.initTables();

        File directory = new File(postsDir);
        List<Announcement> announcements = OlxDetailsParser.parseDirectory(directory, onlyNew);

        if (announcements.isEmpty()) {
            log.println("📭 Немає нових записів для імпорту.");
            return;
        }

        log.printf("📥 Починаємо імпорт %d оголошень...%n", announcements.size());

        int success = 0, failed = 0, tooOld = 0;
        for (int i = 0; i < announcements.size(); i++) {
            Announcement a = announcements.get(i);
            boolean saved = ProjectDatabaseService.saveAnnouncement(a);

            if (saved) {
                success++;

                // Якщо оголошення старше 7 днів — одразу позначаємо як "вже відправлено"
                // щоб воно ніколи не потрапило в Telegram чергу
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

        log.printf("✅ Імпорт: %d збережено | %d помилок | %d старших 7 днів (пропущено для TG)%n",
                success, failed, tooOld);

        // Відправляємо в Telegram тільки свіжі (sent_to_telegram = 0)
        sendUnsentToTelegram(log);
    }

    /**
     * Перевіряє чи оголошення старше TELEGRAM_MAX_AGE_DAYS днів.
     * Якщо дата не парситься — вважаємо свіжим (відправляємо).
     */
    private static boolean isTooOldForTelegram(Announcement a) {
        String dateStr = a.getDatePublished();
        if (dateStr == null || dateStr.isBlank()) return false;
        try {
            LocalDate published = LocalDate.parse(dateStr, DATE_FORMAT);
            LocalDate threshold = LocalDate.now().minusDays(TELEGRAM_MAX_AGE_DAYS);
            return published.isBefore(threshold);
        } catch (Exception e) {
            return false; // невідомий формат — не блокуємо
        }
    }

    /**
     * Відправляє в Telegram всі оголошення де sent_to_telegram = 0.
     * Викликається і після імпорту, і при старті програми.
     */
    public static void sendUnsentToTelegram(PrintStream log) {
        List<Announcement> unsent = ProjectDatabaseService.getUnsentAnnouncements();

        if (unsent.isEmpty()) {
            log.println("📨 Немає невідправлених оголошень для Telegram.");
            return;
        }

        log.printf("📨 Знайдено невідправлених: %d — запускаємо Telegram-Sender-Thread%n",
                unsent.size());

        Thread telegramThread = new Thread(() -> {
            int sent = 0, tgFailed = 0;
            for (Announcement a : unsent) {
                try {
                    TelegramNotificationService.sendNewAnnouncement(a);
                    // Позначаємо тільки після успішної відправки
                    ProjectDatabaseService.markAsSentToTelegram(a.getId());
                    sent++;
                    Thread.sleep(3_500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    tgFailed++;
                    // НЕ позначаємо — при наступному запуску спробуємо знову
                    System.err.printf("❌ [Telegram] ID=%s: %s%n", a.getId(), e.getMessage());
                }
            }
            System.out.printf("📨 [Telegram] Відправлено: %d | Помилок: %d%n", sent, tgFailed);
        }, "Telegram-Sender-Thread");

        telegramThread.setDaemon(true);
        telegramThread.start();
    }
}
