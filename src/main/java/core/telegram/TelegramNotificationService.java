package core.telegram;

import core.telegram.main.ChannelDiscussionRegistry;
import core.telegram.main.PrivateMainBot;
import core.tools.PrivatBankRateService;
import model.Announcement;
import core.telegram.model.Config;
import model.Currency;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.List;

/**
 * Сервіс для автоматичної публікації оголошень нерухомості в Telegram-канал та коментарі до них.
 * <p>
 * Клас використовує Telegram Bot API для виконання наступних завдань:
 * <ol>
 * <li>Форматування та відправка головного текстового поста в Telegram-канал з підтримкою HTML-розмітки.</li>
 * <li>Автоматичний розрахунок і виведення еквівалента вартості в USD та EUR через PrivatBankRateService.</li>
 * <li>Відстеження репосту головного повідомлення у пов'язану групу обговорення (чат коментарів).</li>
 * <li>Асинхронне завантаження фотографій об'єкта (до 10 штук) у вигляді коментаря (медіагрупи) до оригінального поста.</li>
 * </ol>
 * </p>
 * <p>
 * Реалізований як <b>Singleton</b> для запобігання паралельним конфліктам ініціалізації та перевищення лімітів запитів.
 * Також містить механізми повтору (retry) при отриманні помилок обмеження частоти запитів (HTTP 429).
 * </p>
 * * @author Mykola
 */
public class TelegramNotificationService {

    /** Токен авторизації Telegram-бота */
    private static final String BOT_TOKEN = Config.TOKEN.getKey();

    /** ID цільового публічного або приватного каналу, куди надсилається головний пост */
    private static final String CHANNEL_CHAT_ID = Config.ID_CHAT.getKey();

    /** ID супергрупи, яка прив'язана до каналу як група обговорення (сюди йдуть коментарі) */
    private static final String DISCUSSION_GROUP_ID = Config.ID_CHAT_COMMENT.getKey();

    /** Єдиний екземпляр сервісу в системі (патерн Singleton) */
    private static TelegramNotificationService telegramNotificationService;

    /** Потік виведення логів роботи бота (наприклад, консоль додатка або GUI) */
    private final PrintStream botOut;

    /** Базова затримка у мілісекундах між публікаціями оголошень для запобігання флуду */
    private static final int BASE_DELAY_MS = 2000;

    /**
     * Приватний конструктор для запобігання створенню екземплярів ззовні.
     * Забезпечує захист потоку логування від значення null.
     *
     * @param botOut потік для виведення логів; якщо передано {@code null}, ініціалізується як {@code System.out}
     */
    private TelegramNotificationService(PrintStream botOut) {
        this.botOut = (botOut != null) ? botOut : System.out;
    }

    /**
     * Глобальна точка доступу до екземпляру класу (потокобезпечний Singleton).
     *
     * @param botOut потік для виведення логів
     * @return єдиний екземпляр {@link TelegramNotificationService}
     */
    public static synchronized TelegramNotificationService getTelegramNotificationService(PrintStream botOut) {
        if (telegramNotificationService == null) {
            telegramNotificationService = new TelegramNotificationService(botOut);
        }
        return telegramNotificationService;
    }

    /**
     * Формує HTML-пост оголошення, публікує його в канал та ініціює відправку фотографій у коментарі.
     * <p>
     * Метод автоматично перераховує ціну в долари та євро на основі свіжого готівкового курсу ПриватБанку.
     * Якщо головний пост опубліковано успішно, створюється новий ізольований потік, який очікує
     * автоматичного репосту повідомлення в чат обговорення і прикріплює туди фотографії як медіагрупу.
     * </p>
     *
     * @param ad об'єкт оголошення {@link Announcement} для публікації
     */
    public void sendNewAnnouncement(Announcement ad) {
        StringBuilder sb = new StringBuilder();
        sb.append("🆕 <b>НОВЕ ОГОЛОШЕННЯ!</b>\n\n");
        sb.append("📌 <b>Заголовок:</b> ").append(escapeHtml(ad.getTitle())).append("\n");

        // Форматування фінансового блоку
        if (ad.getPriceValue() != null) {
           appendPriceInfo(sb,ad);
        } else {
            sb.append("💰 <b>Ціна:</b> Договірна\n");
        }
        sb.append("📍 <b>Локація:</b> ").append(escapeHtml(ad.getCity().getLabel())).append("\n");
        sb.append("📅 <b>Дата:</b> ").append(ad.getDatePublished()).append("\n");

        // Параметри нерухомості (кількість кімнат, площа, поверх)
        if (ad.getRoomsCount() > 0 || ad.getTotalArea() > 0) {
            sb.append("📊 <b>Параметри:</b> ");
            if (ad.getRoomsCount() > 0) sb.append(ad.getRoomsCount()).append(" кімн. | ");
            if (ad.getTotalArea() > 0) sb.append(ad.getTotalArea()).append(" м² | ");
            if (ad.getFloor() > 0) sb.append(ad.getFloor()).append(" пов.");
            sb.append("\n");
        }

        // Формуємо короткий опис, безпечно екрануючи HTML-теги для запобігання помилкам парсингу на стороні Telegram
        sb.append("\n📝 <b>Опис:</b> ").append(escapeHtml(truncateDescription(ad.getDescription(), 200))).append("\n\n");
        sb.append("<a href=\"").append(ad.getUrl()).append("\">🔗 Відкрити на OLX</a> \n\n");
        sb.append("👇 <b>Фотографії в коментарях!</b> 👇");

        // 1. Публікуємо сформований пост у канал та отримуємо ID створеного повідомлення
        int channelMessageId = sendHtmlMessageAndGetId(sb.toString());

        // Якщо отримали HTTP-код 429 (Too Many Requests), робимо паузу 5 сек і пробуємо ще раз
        if (channelMessageId == -2) {
            botOut.println("⏳ Очікуємо 5 секунд через ліміт запитів (429) перед повторною спробою...");
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            channelMessageId = sendHtmlMessageAndGetId(sb.toString());
        }

        // 2. Якщо пост успішно опубліковано і оголошення містить фотографії
        if (channelMessageId > 0 && ad.getPhotos() != null && !ad.getPhotos().isEmpty()) {
            final int finalChannelMessageId = channelMessageId;
            final List<String> photos = ad.getPhotos();

            // 🚀 КРИТИЧНО: Запускаємо процес очікування репосту та надсилання фото в окремому потоці,
            // щоб не блокувати головну нитку виконання програми та дати боту вчасно ловити апдейти!
            new Thread(() -> {
                botOut.println("⏳ [Потік " + finalChannelMessageId + "] Почали очікування репосту в групу...");
                // Очікуємо появу повідомлення у групі обговорення (реєстратор зазвичай чекає до 5 секунд)
                int discussionMessageId = ChannelDiscussionRegistry.getDiscussionMessageId(finalChannelMessageId);

                if (discussionMessageId > 0) {
                    botOut.println("🚀 [Потік " + finalChannelMessageId + "] Зв'язок підтверджено! Надсилаємо фото в гілку: " + discussionMessageId);
                    sendPhotosAsComment(discussionMessageId, photos);
                } else {
                    botOut.println("❌ [Потік " + finalChannelMessageId + "] Не вдалося вчасно отримати ID дискусії (таймаут 5 сек)");
                }
            }).start();
        }

        // Штучна пауза після відправки кожного оголошення для уникнення блокування лімітами API Telegram
        try {
            Thread.sleep(BASE_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Надсилає текстове HTML-повідомлення в Telegram-канал та повертає ID створеного поста.
     *
     * @param text готовий HTML-текст повідомлення
     * @return ID повідомлення в каналі (більше 0), або {@code -2} у разі блокування лімітом 429, або {@code -1} при загальній помилці
     */
    private int sendHtmlMessageAndGetId(String text) {
        try {
            String urlString = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setDoOutput(true);

            // Формуємо POST-параметри запиту
            String urlParameters = "chat_id=" + URLEncoder.encode(CHANNEL_CHAT_ID, "UTF-8")
                    + "&text=" + URLEncoder.encode(text, "UTF-8")
                    + "&parse_mode=HTML";

            try (OutputStream os = conn.getOutputStream()) {
                os.write(urlParameters.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();

            // Перевірка перевищення лімітів надсилання запитів
            if (responseCode == 429) {
                botOut.println("⚠️ Telegram API: Превищено ліміт запитів (429) при надсиланні поста.");
                return -2;
            }

            if (responseCode == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }

                    // Витягуємо message_id створеного в каналі поста за допомогою регулярного виразу
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"message_id\"\\s*:\\s*(\\d+)").matcher(response.toString());
                    if (m.find()) {
                        return Integer.parseInt(m.group(1));
                    }
                }
            } else {
                // Логуємо детальну помилку від сервера Telegram
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder errResponse = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        errResponse.append(line);
                    }
                    botOut.println("❌ Помилка sendMessage. Код: " + responseCode + ", Текст: " + errResponse.toString());
                }
            }
        } catch (Exception e) {
            botOut.println("❌ Помилка у sendHtmlMessageAndGetId: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Надсилає групу фотографій (медіаальбом) у гілку обговорення конкретного поста як коментар.
     * <p>
     * Оскільки Telegram Bot API дозволяє надсилати максимум 10 картинок в одній медіагрупі,
     * список фотографій обрізається до цієї кількості. Запит виконує реплай на ID повідомлення
     * у зв'язаному чаті обговорень. У разі виникнення помилки 429, метод робить до 3 спроб із затримкою 6 секунд.
     * </p>
     *
     * @param discussionMessageId ID репостнутого повідомлення в чаті обговорень (слугує точкою реплаю)
     * @param photoUrls           список URL-адрес зображень для завантаження
     */
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

                // Telegram дозволяє відправляти не більше 10 медіафайлів у пакеті sendMediaGroup
                int maxPhotos = Math.min(photoUrls.size(), 10);
                StringBuilder mediaJson = new StringBuilder("[");
                for (int i = 0; i < maxPhotos; i++) {
                    mediaJson.append("{\"type\":\"photo\",\"media\":\"").append(photoUrls.get(i)).append("\"}");
                    if (i < maxPhotos - 1) {
                        mediaJson.append(",");
                    }
                }
                mediaJson.append("]");

                // Параметри відповіді (реплаю) на повідомлення у гілці обговорення
                String replyParametersJson = "{\"message_id\":" + discussionMessageId + "}";

                String urlParameters = "chat_id=" + URLEncoder.encode(DISCUSSION_GROUP_ID, "UTF-8")
                        + "&media=" + URLEncoder.encode(mediaJson.toString(), "UTF-8")
                        + "&reply_parameters=" + URLEncoder.encode(replyParametersJson, "UTF-8");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(urlParameters.getBytes(StandardCharsets.UTF_8));
                }

                int responseCode = conn.getResponseCode();

                if (responseCode == 200) {
                    botOut.println("✅ Фотографії успішно завантажено в коментарі под постом.");
                    success = true;
                } else if (responseCode == 429) {
                    attempts++;
                    botOut.println("⚠️ Спіймали 429 при відправці медіагрупи. Спроба " + attempts + " з 3. Спимо 6 секунд...");
                    Thread.sleep(6000); // Очікування розвантаження черги запитів Telegram
                } else {
                    // Зчитуємо детальну помилку від Telegram API, якщо прийшов некоректний запит (наприклад, 400 Bad Request)
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                        StringBuilder errResponse = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            errResponse.append(line);
                        }
                        botOut.println("❌ Помилка відправки фото в коментарі. Код: " + responseCode + ", Опис: " + errResponse.toString());
                    }
                    break; // Якщо помилка фатальна і не пов'язана з лімітами, повторювати немає сенсу
                }

            } catch (Exception e) {
                botOut.println("❌ Не вдалося надіслати фото в коментарі: " + e.getMessage());
                break;
            }
        }
    }

    /**
     * Екранує спеціальні символи HTML, щоб уникнути пошкодження структури тегів при публікації.
     * Необхідно викликати для довільного тексту, отриманого при парсингу (наприклад, заголовок, опис).
     *
     * @param text вихідний необроблений текст
     * @return екранований текст, безпечний для parse_mode=HTML
     */
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Обрізає опис оголошення до вказаної максимальної довжини, додаючи три крапки в кінці.
     * Запобігає перевищенню ліміту на розмір текстового повідомлення в Telegram (4096 символів).
     *
     * @param desc      необроблений повний опис об'єкта
     * @param maxLength максимальна дозволена довжина символів для публікації
     * @return обрізаний опис із символом завершення "...", або плейсхолдер при порожньому значенні
     */
    private static String truncateDescription(String desc, int maxLength) {
        if (desc == null || desc.isEmpty()) {
            return "Немає опису";
        }
        if (desc.length() <= maxLength) {
            return desc;
        }
        return desc.substring(0, maxLength) + "...";
    }

    /**
     * Форматує та додає інформацію про ціну та її еквіваленти в інші валюти до повідомлення.
     * * @param sb StringBuilder повідомлення
     * @param ad Оголошення
     */
    private void appendPriceInfo(StringBuilder sb, Announcement ad){
        BigDecimal price = ad.getPriceValue();
        String currency = ad.getPriceCurrency();
        DecimalFormat df = new DecimalFormat("#,###");

        sb.append("💰 <b>Ціна:</b> ").append(df.format(price)).append(ad.getPriceCurrency()).append("\n");

        // Використовуємо наш покращений PrivatBankRateService
        // Конвертуємо все через UAH для максимальної точності
        if (currency.equals(Currency.USD.getLabel())) {
            BigDecimal uah = PrivatBankRateService.convertUsdToUah(price);
            BigDecimal eur = PrivatBankRateService.convertUsdToEur(price);
            sb.append("💵 <b>Еквівалент:</b> EUR ").append(df.format(eur)).append(" | UAH ").append(df.format(uah)).append("\n");

        } else if (currency.equals(Currency.EUR.getLabel())) {
            BigDecimal uah = PrivatBankRateService.convertEurToUah(price);
            BigDecimal usd = PrivatBankRateService.convertEurToUsd(price);
            sb.append("💵 <b>Еквівалент:</b> USD ").append(df.format(usd)).append(" | UAH ").append(df.format(uah)).append("\n");

        } else if (currency.equals(Currency.UAH.getLabel())) {
            BigDecimal usd = PrivatBankRateService.convertUahToUsd(price);
            BigDecimal eur = PrivatBankRateService.convertUahToEur(price);
            sb.append("💵 <b>Еквівалент:</b> USD ").append(df.format(usd)).append(" | EUR ").append(df.format(eur)).append("\n");
        }
    }
}

