package core.telegram;

import core.serverDB.sqlite.ProjectDatabaseService;
import core.telegram.controllers.TelegramSender;
import model.Announcement;
import model.SavedSearchFilter;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import core.telegram.main.InlineKeyboardFactory;

import java.util.LinkedHashMap;

import java.io.PrintStream;
import java.util.List;

/** Окремий фоновий потік персональних повідомлень за збереженими фільтрами. */
public final class SearchNotificationService {
    private static volatile TelegramSender sender;

    private SearchNotificationService() { }

    public static void configure(TelegramSender telegramSender) {
        sender = telegramSender;
    }

    /** Не блокує OLX-воркер або потік отримання Update від Telegram. */
    public static void runAfterSuccessfulImport(PrintStream log) {
        TelegramSender configuredSender = sender;
        if (configuredSender == null) {
            log.println("⚠️ [Notifications] Бот ще не готовий; розсилку пропущено.");
            return;
        }
        Thread worker = new Thread(() -> dispatch(configuredSender, log), "Search-Notifications-Worker");
        worker.setDaemon(true);
        worker.start();
    }

    private static void dispatch(TelegramSender telegramSender, PrintStream log) {
        List<SavedSearchFilter> filters = ProjectDatabaseService.getEnabledSubscribedFilters();
        int prompts = 0;
        for (SavedSearchFilter filter : filters) {
            try {
                List<Announcement> announcements = ProjectDatabaseService.getNewAnnouncementsForFilter(filter);
                if (!announcements.isEmpty() && ProjectDatabaseService.saveNotificationBatch(filter.getTelegramId(), announcements)) {
                    SendMessage message = new SendMessage();
                    message.setChatId(String.valueOf(filter.getTelegramId()));
                    message.setText("🔔 Я знайшов нові оголошення, бажаєте переглянути?");
                    message.setReplyMarkup(InlineKeyboardFactory.createVertical(new LinkedHashMap<>() {{
                        put("✅ Так, хочу переглянути", "NOTIF_VIEW");
                        put("❌ Ні, не хочу", "NOTIF_IGNORE");
                    }}, null, null));
                    telegramSender.executeMethod(message);
                    prompts++;
                }
                // Черга зафіксована, тому той самий набір не створить повторний запит у наступному циклі.
                ProjectDatabaseService.markFilterChecked(filter.getTelegramId());
            } catch (Exception e) {
                log.println("⚠️ [Notifications] chat=" + filter.getTelegramId() + ": " + e.getMessage());
            }
        }
        log.printf("🔔 [Notifications] Активних фільтрів: %d, створено запитів: %d%n", filters.size(), prompts);
    }
}
