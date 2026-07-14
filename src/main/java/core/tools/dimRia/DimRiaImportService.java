package core.tools.dimRia;

import core.telegram.TelegramNotificationService;
import model.Announcement;
import sqlite.ProjectDatabaseService;

import java.io.File;
import java.io.PrintStream;
import java.util.List;

/**
 * Сервіс імпорту DimRia: читає згенеровані JSON файли та пише в єдину базу SQLite.
 */
public class DimRiaImportService {

    /**
     * Головний метод імпорту.
     * Проходить по всіх post_*.json у папці dimria_details і пише в БД.
     *
     * @param postsDir  шлях до папки з JSON файлами DimRia
     * @param log       PrintStream для виводу прогресу у вікно консолі
     * @param onlyNew   true — пропускати ID, що вже є в БД (інкрементальний імпорт)
     */
    public static void importFromJson(String postsDir, PrintStream log, boolean onlyNew) {
        log.println("\n=== [DimRia] ЕТАП 3: Імпорт у базу даних SQLite ===");

        // Ініціалізуємо таблиці (якщо раптом запуск першим)
        ProjectDatabaseService.initTables();

        File directory = new File(postsDir);
        List<Announcement> announcements = DimRiaDetailsParser.parseDirectory(directory, onlyNew);

        if (announcements.isEmpty()) {
            log.println("📭 [DimRia] Немає нових записів для імпорту.");
            return;
        }

        log.printf("📥 [DimRia] Починаємо імпорт %d оголошень...%n", announcements.size());

        int success = 0, failed = 0;
        for (int i = 0; i < announcements.size(); i++) {
            Announcement a = announcements.get(i);

            // Модифікуємо ID додаванням префіксу, щоб DimRia та OLX не перезаписували один одного
            String originalId = a.getId();
            if (!originalId.startsWith("DR_")) {
                a.setId("DR_" + originalId);
            }

            // 1. ПЕРЕВІРЯЄМО, чи є вже це оголошення в базі ДО збереження
            boolean isBrandNew = ! ProjectDatabaseService.exists(a.getId());

            if (onlyNew && !isBrandNew) {
                // Якщо увімкнено "тільки нові" і воно вже є — пропускаємо
                continue;
            }

            // 2. Зберігаємо в єдину базу даних
            boolean saved =  ProjectDatabaseService.saveAnnouncement(a);

            if (saved) {
                success++;

                // 3. Якщо оголошення дійсно нове — відправляємо його в Telegram групу!
                if (isBrandNew) {
                    // Тимчасово повертаємо гарне посилання без префіксу для ТГ
                    String tempId = a.getId();

                    TelegramNotificationService telegramNotificationService = TelegramNotificationService.getTelegramNotificationService(log);
                    telegramNotificationService.sendNewAnnouncement(a);

                    // Робимо паузу, щоб уникнути Flood Control від Telegram API
                    try { Thread.sleep(3500); } catch (InterruptedException ignored) {}
                }

                if (success % 50 == 0 || i == announcements.size() - 1) {
                    log.printf("  💾 [DimRia] Збережено: %d / %d%n", success, announcements.size());
                }
            } else {
                failed++;
                log.printf("  ❌ [DimRia] Помилка для ID: %s%n", a.getId());
            }
        }

        log.printf("🏁 [DimRia] Імпорт завершено. Успішно: %d | Помилок: %d%n", success, failed);
    }
}

