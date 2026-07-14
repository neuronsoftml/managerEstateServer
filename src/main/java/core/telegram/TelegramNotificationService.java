package core.telegram;

import core.telegram.main.ChannelDiscussionRegistry;
import core.telegram.main.PrivateMainBot;
import model.Announcement;
import core.telegram.model.Config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class TelegramNotificationService {
    private static final String BOT_TOKEN = Config.TOKEN.getKey();
    private static final String CHANNEL_CHAT_ID = Config.ID_CHAT.getKey(); // Твій канал (куди йде пост)
    private static final String DISCUSSION_GROUP_ID = Config.ID_CHAT_COMMENT.getKey(); // Твоя група обговорення

    private static TelegramNotificationService telegramNotificationService;
    private final PrintStream botOut;

    // Константа для базової затримки між публікаціями оголошень
    private static final int BASE_DELAY_MS = 2000;

    // Конструктор тепер приватний (оскільки ми використовуємо патерн Singleton)
    private TelegramNotificationService(PrintStream botOut) {
        // Захист від NullPointerException: якщо передали null, використовуємо стандартний System.out
        this.botOut = (botOut != null) ? botOut : System.out;
    }

    public static synchronized TelegramNotificationService getTelegramNotificationService(PrintStream botOut) {
        if (telegramNotificationService == null) {
            telegramNotificationService = new TelegramNotificationService(botOut);
        }
        return telegramNotificationService;
    }

    public void sendNewAnnouncement(Announcement ad) {
        StringBuilder sb = new StringBuilder();
        sb.append("🆕 <b>НОВЕ ОГОЛОШЕННЯ!</b>\n\n");
        sb.append("📌 <b>Заголовок:</b> ").append(escapeHtml(ad.getTitle())).append("\n");

        if (ad.getPriceValue() != null) {
            java.math.BigDecimal priceUah = ad.getPriceValue();
            java.math.BigDecimal priceUsd = core.tools.PrivatBankRateService.convertUahToUsd(priceUah);
            java.math.BigDecimal priceEur = core.tools.PrivatBankRateService.convertUahToEur(priceUah);
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
        sb.append("👇 <b>Фотографії в коментарях!</b> 👇");

        // 1. Публікуємо пост в канал
        int channelMessageId = sendHtmlMessageAndGetId(sb.toString());

        // Якщо отримали ліміт або помилку, спробуємо почекати і повторити один раз
        if (channelMessageId == -2) {
            botOut.println("⏳ Очікуємо 5 секунд через ліміт запитів (429) перед повторною спробою...");
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            channelMessageId = sendHtmlMessageAndGetId(sb.toString());
        }

        // 2. Якщо пост успішний і є фотографії
        if (channelMessageId > 0 && ad.getPhotos() != null && !ad.getPhotos().isEmpty()) {
            final int finalChannelMessageId = channelMessageId;
            final List<String> photos = ad.getPhotos();

            // 🚀 КРИТИЧНО: Запускаємо процес очікування репосту та надсилання фото в окремому потоці,
            // щоб не блокувати головну нитку виконання програми та дати боту вчасно ловити апдейти!
            new Thread(() -> {
                botOut.println("⏳ [Потік " + finalChannelMessageId + "] Почали очікування репосту в групу...");
                int discussionMessageId = ChannelDiscussionRegistry.getDiscussionMessageId(finalChannelMessageId);

                if (discussionMessageId > 0) {
                    botOut.println("🚀 [Потік " + finalChannelMessageId + "] Зв'язок підтверджено! Надсилаємо фото в гілку: " + discussionMessageId);
                    sendPhotosAsComment(discussionMessageId, photos);
                } else {
                    botOut.println("❌ [Потік " + finalChannelMessageId + "] Не вдалося вчасно отримати ID дискусії (таймаут 5 сек)");
                }
            }).start();
        }

        // Штучна пауза після КОЖНОГО оголошення під час імпорту
        try {
            Thread.sleep(BASE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int sendHtmlMessageAndGetId(String text) {
        try {
            String urlString = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setDoOutput(true);

            String urlParameters = "chat_id=" + URLEncoder.encode(CHANNEL_CHAT_ID, "UTF-8")
                    + "&text=" + URLEncoder.encode(text, "UTF-8")
                    + "&parse_mode=HTML";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(urlParameters.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();

            // ОБРОБКА ЛІМІТУ ЗАПИТІВ (Too Many Requests)
            if (responseCode == 429) {
                botOut.println("⚠️ Telegram API: Превищено ліміт запитів (429) при надсиланні поста.");
                return -2;
            }

            if (responseCode == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) response.append(line);

                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"message_id\"\\s*:\\s*(\\d+)").matcher(response.toString());
                    if (m.find()) {
                        return Integer.parseInt(m.group(1));
                    }
                }
            } else {
                // Логуємо помилку, якщо пост взагалі не опублікувався
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder errResponse = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) errResponse.append(line);
                    botOut.println("❌ Помилка sendMessage. Код: " + responseCode + ", Текст: " + errResponse.toString());
                }
            }
        } catch (Exception e) {
            botOut.println("❌ Помилка у sendHtmlMessageAndGetId: " + e.getMessage());
        }
        return -1;
    }

    private void sendPhotosAsComment(int discussionMessageId, List<String> photoUrls) {
        int attempts = 0;
        boolean success = false;

        while (attempts < 3 && !success) {
            try {
                String urlString = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMediaGroup";
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                conn.setDoOutput(true);

                int maxPhotos = Math.min(photoUrls.size(), 10);
                StringBuilder mediaJson = new StringBuilder("[");
                for (int i = 0; i < maxPhotos; i++) {
                    mediaJson.append("{\"type\":\"photo\",\"media\":\"").append(photoUrls.get(i)).append("\"}");
                    if (i < maxPhotos - 1) mediaJson.append(",");
                }
                mediaJson.append("]");

                String replyParametersJson = "{\"message_id\":" + discussionMessageId + "}";

                String urlParameters = "chat_id=" + URLEncoder.encode(DISCUSSION_GROUP_ID, "UTF-8")
                        + "&media=" + URLEncoder.encode(mediaJson.toString(), "UTF-8")
                        + "&reply_parameters=" + URLEncoder.encode(replyParametersJson, "UTF-8");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(urlParameters.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();

                if (responseCode == 200) {
                    botOut.println("✅ Фотографії успішно завантажено в коментарі під постом.");
                    success = true;
                } else if (responseCode == 429) {
                    attempts++;
                    botOut.println("⚠️ Спіймали 429 при відправці медіагрупи. Спроба " + attempts + " з 3. Спимо 6 секунд...");
                    Thread.sleep(6000);
                } else {
                    // Зчитуємо детальну помилку від Telegram API, якщо наприклад прийшов 400 Bad Request
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                        StringBuilder errResponse = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) errResponse.append(line);
                        botOut.println("❌ Помилка відправки фото в коментарі. Код: " + responseCode + ", Опис: " + errResponse.toString());
                    }
                    break; // Якщо помилка не 429, повторювати немає сенсу
                }

            } catch (Exception e) {
                botOut.println("❌ Не вдалося надіслати фото в коментарі: " + e.getMessage());
                break;
            }
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


