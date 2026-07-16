package core.tools.parser.olx;

import model.Announcement;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсер JSON файлів деталей оголошень OLX.
 * Читає post_{id}.json і перетворює на об'єкт Announcement.
 *
 * Не використовує зовнішні бібліотеки — тільки регулярки.
 */


public class OlxDetailsParser {

    // ── Патерни для витягання полів ───────────────────────────────────────────

    private static final Pattern P_STRING = Pattern.compile(
            "\"(%s)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final Pattern P_ARRAY_ITEMS = Pattern.compile(
            "\"(%s)\"\\s*:\\s*\\[([^\\]]*)]", Pattern.DOTALL);

    private static final Pattern P_ARRAY_VALUE = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");


    private static final Locale UKRAINIAN_LOCALE = new Locale("uk", "UA");
    private static final DateTimeFormatter TARGET_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    // Створюємо гнучкий парсер, який ігнорує регістр символів
    private static final DateTimeFormatter OLX_TEXT_DATE_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d MMMM[ yyyy]") // підтримує "24 червня" або "24 червня 2026"
            .toFormatter(UKRAINIAN_LOCALE);

    // ── Головний метод: файл → Announcement ──────────────────────────────────

    /**
     * Парсить один JSON файл і повертає Announcement або null якщо помилка.
     */
    public static Announcement parseFile(File file) {
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return parseJson(json);
        } catch (IOException e) {
            System.err.println("❌ Не вдалося прочитати файл: " + file.getName() + " — " + e.getMessage());
            return null;
        }
    }

    public static Announcement parseJson(String json) {
        String id            = extractString(json, "id");
        String city          = extractString(json, "city");
        String category      = extractString(json, "category");
        String url           = extractString(json, "url");
        String title         = extractString(json, "title");
        String price         = extractString(json, "price");
        String location      = extractString(json, "location");
        String datePublished = normalizeOlxDate(extractString(json, "date_published"));
        String seller        = extractString(json, "seller");
        String phone         = extractString(json, "phone");
        String description   = extractString(json, "description");

        List<String> params = extractArray(json, "params");
        List<String> photos = extractArray(json, "photos");

        if (id == null || id.isEmpty()) return null; // мінімальна валідація

        return new Announcement(
                id, city, category, url, title, price,
                location, datePublished, seller, phone,
                description, params, photos
        );
    }

    // ── Утиліти парсингу ──────────────────────────────────────────────────────

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) return "";
        // Розекрановуємо JSON escape-послідовності
        return unescape(m.group(1));
    }

    private static List<String> extractArray(String json, String key) {
        List<String> result = new ArrayList<>();
        Matcher arrayMatcher = Pattern.compile(
                "\"" + key + "\"\\s*:\\s*\\[([^\\]]*)]",
                Pattern.DOTALL).matcher(json);
        if (!arrayMatcher.find()) return result;

        String arrayContent = arrayMatcher.group(1);
        Matcher itemMatcher = P_ARRAY_VALUE.matcher(arrayContent);
        while (itemMatcher.find()) {
            result.add(unescape(itemMatcher.group(1)));
        }
        return result;
    }

    /**
     * Розекрановує JSON рядок: \n → перенос, \" → лапки тощо.
     */
    private static String unescape(String s) {
        return s.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    // ── Пакетний метод: директорія → список Announcement ─────────────────────

    /**
     * Читає всі post_*.json файли з директорії і повертає список оголошень.
     *
     * @param directory  папка з JSON файлами (напр. data/output/olx_details)
     * @param onlyNew    якщо true — пропускає ID що вже є в БД
     */
    public static List<Announcement> parseDirectory(File directory, boolean onlyNew) {
        List<Announcement> list = new ArrayList<>();

        if (!directory.exists() || !directory.isDirectory()) {
            System.err.println("❌ Директорія не знайдена: " + directory.getAbsolutePath());
            return list;
        }

        File[] files = directory.listFiles(
                (dir, name) -> name.startsWith("post_") && name.endsWith(".json"));

        if (files == null || files.length == 0) {
            System.out.println("📂 JSON файлів не знайдено у: " + directory.getAbsolutePath());
            return list;
        }

        int skipped = 0;
        for (File file : files) {
            Announcement a = parseFile(file);
            if (a == null) continue;

            if (onlyNew && sqlite.ProjectDatabaseService.exists(a.getId())) {
                skipped++;
                continue;
            }
            list.add(a);
        }

        System.out.printf("📋 Знайдено JSON: %d | Для імпорту: %d | Вже в БД: %d%n",
                files.length, list.size(), skipped);
        return list;
    }

    private static String normalizeOlxDate(String rawDate) {
        if (rawDate == null || rawDate.isEmpty()) {
            return LocalDate.now().format(TARGET_FORMAT);
        }

        // Прибираємо сміття, яке часто дописує OLX навколо самої дати
        String cleaned = rawDate.toLowerCase()
                .replace("опубліковано", "")
                .replace(" р.", "")
                .replace("о", "")
                .replaceAll("\\d{2}:\\d{2}", "") // видаляємо час (наприклад, 08:00)
                .trim();

        // 1. Обробка відносних дат через стандартні методи LocalDate
        if (cleaned.contains("сьогодні")) {
            return LocalDate.now().format(TARGET_FORMAT);
        }
        if (cleaned.contains("вчора")) {
            return LocalDate.now().minusDays(1).format(TARGET_FORMAT);
        }

        // 2. Парсинг текстової української дати стандартною бібліотекою Java
        try {
            // Якщо OLX не вказав рік (для поточного року вони його опускають),
            // стандартний парсер видасть помилку, тому перевіряємо і дописуємо поточний рік
            String[] parts = cleaned.split("\\s+");
            if (parts.length == 2) {
                cleaned += " " + LocalDate.now().getYear(); // робимо з "24 червня" -> "24 червня 2026"
            }

            // В один рядок парсимо український текст і відразу перетворюємо на об'єкт дати
            LocalDate parsedDate = LocalDate.parse(cleaned, OLX_TEXT_DATE_FORMATTER);
            return parsedDate.format(TARGET_FORMAT);

        } catch (Exception e) {
            // Якщо раптом прилетів невідомий формат — повертаємо сьогоднішню дату як безпечний фолбек
            return LocalDate.now().format(TARGET_FORMAT);
        }
    }
}
