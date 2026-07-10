package core.telegram;

import model.Announcement;
import model.telegram.Config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class TelegramNotificationService {

    private static final String BOT_TOKEN = Config.TOKEN.getKey();
    private static final String CHAT_ID = Config.ID_CHAT.getKey(); // ID вашої групи/каналу

    /**
     * Головний метод: відправляє текст, отримує його ID і закидає фото в коментарі.
     */
    public static void sendNewAnnouncement(Announcement ad) {
        StringBuilder sb = new StringBuilder();
        sb.append("🆕 <b>НОВЕ ОГОЛОШЕННЯ!</b>\n\n");
        sb.append("📌 <b>Заголовок:</b> ").append(escapeHtml(ad.getTitle())).append("\n");
        if (ad.getPriceValue() != null) {
            java.math.BigDecimal priceUah = ad.getPriceValue();

            // Розраховуємо еквіваленти через наш сервіс ПриватБанку
            java.math.BigDecimal priceUsd = core.tools.PrivatBankRateService.convertUahToUsd(priceUah);
            java.math.BigDecimal priceEur = core.tools.PrivatBankRateService.convertUahToEur(priceUah);

            // Форматуємо вивід (робимо красиві пробіли в числах, напр. 2 840 500)
            java.text.DecimalFormat df = new java.text.DecimalFormat("#,###");

            sb.append("💰 <b>Ціна:</b> ").append(df.format(priceUah)).append(" грн\n");
            sb.append("💵 <b>Еквівалент:</b> $").append(df.format(priceUsd)).append("  |  €").append(df.format(priceEur)).append("\n");
        } else {
            sb.append("💰 <b>Ціна:</b> Договірна\n");
        }
        sb.append("📍 <b>Локація:</b> ").append(escapeHtml(ad.getCity().getLabel())).append("\n");
        sb.append("📅 <b>Дата:</b> ").append(ad.getDatePublished()).append("\n");

        if (ad.getRoomsCount() > 0 || ad.getTotalArea() > 0) {
            sb.append("📊 <b>Параметри:</b> ");
            if (ad.getRoomsCount() > 0) sb.append(ad.getRoomsCount()).append(" кімн. | ");
            if (ad.getTotalArea() > 0) sb.append(ad.getTotalArea()).append(" м² | ");
            if (ad.getFloor() > 0) sb.append(ad.getFloor()).append(" пов.");
            sb.append("\n");
        }

        sb.append("\n📝 <b>Опис:</b> ").append(escapeHtml(truncateDescription(ad.getDescription(), 200))).append("\n\n");
        sb.append("<a href=\"").append(ad.getUrl()).append("\">🔗 Відкрити на OLX</a> \n\n");

        sb.append("\uD83D\uDC47 <b>Фотографії!</b> \uD83D\uDC47");

        // 1. Надсилаємо текст і отримуємо ID цього повідомлення
        int mainMessageId = sendHtmlMessageAndGetId(sb.toString());

        // 2. Якщо повідомлення надіслано успішно і у квартири є фотографії — кидаємо їх в коментарі
        if (mainMessageId > 0 && ad.getPhotos() != null && !ad.getPhotos().isEmpty()) {
            // Робіть мікропаузу, щоб Telegram встиг обробити основний пост
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            sendPhotosAsComment(mainMessageId, ad.getPhotos());
        }
    }

    /**
     * Надсилає текст і повертає message_id із відповіді Telegram
     */
    private static int sendHtmlMessageAndGetId(String text) {
        try {
            String urlString = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setDoOutput(true);

            String urlParameters = "chat_id=" + URLEncoder.encode(CHAT_ID, "UTF-8")
                    + "&text=" + URLEncoder.encode(text, "UTF-8")
                    + "&parse_mode=HTML";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(urlParameters.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                // Читаємо відповідь, щоб дістати "message_id"
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) response.append(line);

                    // Швидкий пошук "message_id":12345 за допомогою регулярки без важких ліб JSON
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"message_id\"\\s*:\\s*(\\d+)").matcher(response.toString());
                    if (m.find()) {
                        return Integer.parseInt(m.group(1));
                    }
                }
            } else {
                System.err.println("❌ Помилка відправки тексту. Код: " + responseCode);
            }
        } catch (Exception e) {
            System.err.println("❌ Помилка у sendHtmlMessageAndGetId: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Надсилає до 10 фотографій альбомом (media group) як відповідь (коментар) до поста
     */
    private static void sendPhotosAsComment(int replyToMessageId, List<String> photoUrls) {
        try {
            String urlString = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMediaGroup";
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setDoOutput(true);

            // ТГ дозволяє максимум 10 фото в одному альбомі
            int maxPhotos = Math.min(photoUrls.size(), 10);

            // Формуємо структуру JSON для параметра `media`
            StringBuilder mediaJson = new StringBuilder("[");
            for (int i = 0; i < maxPhotos; i++) {
                mediaJson.append("{\"type\":\"photo\",\"media\":\"").append(photoUrls.get(i)).append("\"}");
                if (i < maxPhotos - 1) mediaJson.append(",");
            }
            mediaJson.append("]");

            // Зв'язуємо коментар через reply_parameters (новий стандарт Telegram API 2024-2026 років)
            String replyParametersJson = "{\"message_id\":" + replyToMessageId + "}";

            String urlParameters = "chat_id=" + URLEncoder.encode(CHAT_ID, "UTF-8")
                    + "&media=" + URLEncoder.encode(mediaJson.toString(), "UTF-8")
                    + "&reply_parameters=" + URLEncoder.encode(replyParametersJson, "UTF-8");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(urlParameters.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                System.err.println("❌ Помилка відправки фото в коментарі. Код: " + responseCode);
            }

        } catch (Exception e) {
            System.err.println("❌ Не вдалося надіслати фото в коментарі: " + e.getMessage());
        }
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String truncateDescription(String desc, int maxLength) {
        if (desc == null || desc.isEmpty()) return "Немає опису";
        if (desc.length() <= maxLength) return desc;
        return desc.substring(0, maxLength) + "...";
    }
}


