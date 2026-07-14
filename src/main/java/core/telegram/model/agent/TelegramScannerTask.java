package core.telegram.model.agent;

import sqlite.ProjectDatabaseService;

import java.util.List;

public class TelegramScannerTask implements Runnable {

    @Override
    public void run() {
        System.out.println("🚀 Фоновий потік моніторингу Telegram груп успішно запущено.");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. Беремо актуальний список груп прямо з нашої SQLite
                List<TelegramGroup> groupsToScan = ProjectDatabaseService.getActiveMonitoredGroups();

                for (TelegramGroup group : groupsToScan) {

                    // Змінна, куди запишемо максимальний ID повідомлення, яке вдалося зчитати
                    long newestMessageIdDetected = group.getLastProcessedMessageId();

                    /* 2. ТУТ БУДЕ ТВІЙ ТЕЛЕГРАМ КЛІЄНТ (наприклад, TDLib / TdLight UserBot)

                       Приклад логіки:
                       List<Message> messages = userBot.getChatHistory(group.getChatId(), group.getLastProcessedMessageId());

                       for (Message msg : messages) {
                           if (msg.getId() > newestMessageIdDetected) {
                               newestMessageIdDetected = msg.getId();
                           }

                           // Перевіряємо текст повідомлення
                           if (GroupMessageAnalyzer.isRealEstateOffer(msg.getText())) {
                               // Надсилаємо в твій Телеграм канал (чи обробляємо як нове оголошення)
                               System.out.println("🎯 Знайдено оголошення в групі: " + msg.getText());
                           }
                       }
                    */

                    // 3. Зберігаємо точку відліку (offset) в базу даних, щоб не сканувати старе
                    if (newestMessageIdDetected > group.getLastProcessedMessageId()) {
                        ProjectDatabaseService.updateGroupLastMessageId(group.getChatId(), newestMessageIdDetected);
                    }
                }

                // Пауза 5 хвилин між циклами сканування груп
                Thread.sleep(5 * 60 * 1000);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("⚠️ Помилка у циклі сканування Telegram груп: " + e.getMessage());
            }
        }
    }
}
