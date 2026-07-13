package core.tools.olx;

import model.ProjectFolder;
import model.Announcement;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OlxStorageService {
    private static final String ROOT         = ProjectFolder.ROOT.getName();
    private static final String OUTPUT       = ProjectFolder.OUTPUT_DIR.getName();
    private static final String POSTS_DIR    = ROOT + "/" + OUTPUT + "/" + ProjectFolder.OLX_DETAILS.getName();
    private static final String IDS_DIR      = ROOT + "/" + OUTPUT + "/" + ProjectFolder.OLX_IDS.getName();
    private static final String ALL_IDS_FILE = IDS_DIR + "/" + ProjectFolder.ALL_IDS_FILE.getName();
    private static final String META_FILE    = IDS_DIR + "/" + ProjectFolder.META_FILE.getName();
    private static final DateTimeFormatter DT  = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public static void initDirectories() throws IOException {
        Files.createDirectories(Paths.get(POSTS_DIR));
        Files.createDirectories(Paths.get(IDS_DIR));
    }

    public static String getRootAbsolutePath() {
        return new File(ROOT).getAbsolutePath();
    }

    /**
     * ПЕРЕВІРКА СТАНУ (замість старого isUpdateNeeded):
     * Перевіряє, чи минула 1 година І чи обидва процеси завершені (true).
     */
    public static boolean isSchedulerReadyToRestart(long hours, PrintStream log) {
        File meta = new File(META_FILE);
        if (!meta.exists()) return true; // Якщо файлу ще немає — запускаємо з чистого аркуша
        try {
            String content = Files.readString(meta.toPath(), StandardCharsets.UTF_8);

            Matcher mTime = Pattern.compile("\"last_update\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
            Matcher mDownload = Pattern.compile("\"is_download_complete\"\\s*:\\s*(true|false)").matcher(content);
            Matcher mPublish = Pattern.compile("\"is_publication_complete\"\\s*:\\s*(true|false)").matcher(content);

            LocalDateTime lastUpdate = mTime.find() ? LocalDateTime.parse(mTime.group(1), DT) : LocalDateTime.MIN;
            boolean downloadComplete = mDownload.find() && Boolean.parseBoolean(mDownload.group(1));
            boolean publishComplete = mPublish.find() && Boolean.parseBoolean(mPublish.group(1));

            // 1. Умова часу
            if (!lastUpdate.isBefore(LocalDateTime.now().minusHours(hours))) {
                return false;
            }

            // 2. Умова завершеності потоків
            if (!downloadComplete || !publishComplete) {
                log.printf("⏳ Час оновлення настав, але потоки ще зайняті [Скачування: %b | Telegram: %b]. Пропуск такту.%n",
                        downloadComplete, publishComplete);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.println("⚠️ Помилка аналізу файлу метаданих (запуск дозволено): " + e.getMessage());
            return true;
        }
    }

    /**
     * Ініціалізація/скидання файлу конфігурації: ставимо час і виставляємо false для процесів.
     */
    public static void saveLastUpdateTime() throws IOException {
        String json = "{\n" +
                "  \"last_update\": \"" + LocalDateTime.now().format(DT) + "\",\n" +
                "  \"is_download_complete\": false,\n" +
                "  \"is_publication_complete\": false\n" +
                "}";
        Files.createDirectories(Paths.get(IDS_DIR));
        Files.writeString(Paths.get(META_FILE), json, StandardCharsets.UTF_8);
    }

    /**
     * Точкове синхронізоване оновлення маркерів стану в JSON
     */
    public static synchronized void updateStateStatus(boolean downloadComplete, boolean publishComplete) {
        try {
            File meta = new File(META_FILE);
            String lastUpdateTime = LocalDateTime.now().format(DT);

            if (meta.exists()) {
                String content = Files.readString(meta.toPath(), StandardCharsets.UTF_8);
                Matcher mTime = Pattern.compile("\"last_update\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
                if (mTime.find()) {
                    lastUpdateTime = mTime.group(1);
                }
            }

            String json = "{\n" +
                    "  \"last_update\": \"" + lastUpdateTime + "\",\n" +
                    "  \"is_download_complete\": " + downloadComplete + ",\n" +
                    "  \"is_publication_complete\": " + publishComplete + "\n" +
                    "}";
            Files.writeString(Paths.get(META_FILE), json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("⚠️ Не вдалося оновити статус-файл meta.json: " + e.getMessage());
        }
    }

    public static Set<String> loadExistingIds(PrintStream log) {
        File file = new File(ALL_IDS_FILE);
        if (!file.exists()) return new HashSet<>();
        Set<String> ids = new HashSet<>();
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
            while (m.find()) ids.add(m.group(1));
        } catch (IOException e) {
            log.println("⚠️ Помилка читання all_ids.json: " + e.getMessage());
        }
        return ids;
    }

    public static void appendNewIds(List<Announcement> newAds, int existingCount) throws IOException {
        File file = new File(ALL_IDS_FILE);
        if (!file.exists() || existingCount == 0) {
            saveAllIds(newAds);
            return;
        }

        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (Announcement a : newAds) {
            sb.append("    {\n")
                    .append("      \"id\": \"").append(esc(a.getId())).append("\",\n")
                    .append("      \"city\": \"").append(esc(a.getCity() != null ? a.getCity().getLabel() : "")).append("\",\n")
                    .append("      \"category\": \"").append(esc(a.getCategory() != null ? a.getCategory().getLabel() : "")).append("\",\n")
                    .append("      \"title\": \"").append(esc(a.getTitle())).append("\",\n")
                    .append("      \"price\": \"").append(esc(a.getPriceRaw())).append("\",\n")
                    .append("      \"location\": \"").append(esc(a.getLocation())).append("\",\n")
                    .append("      \"url\": \"").append(esc(a.getUrl())).append("\"\n")
                    .append("    },\n");
        }

        int lastBracket = content.lastIndexOf("]");
        if (lastBracket == -1) { saveAllIds(newAds); return; }

        StringBuilder merged = new StringBuilder(content.substring(0, lastBracket));
        if (content.substring(0, lastBracket).stripTrailing().endsWith("}")) merged.append(",\n");
        merged.append(sb);
        merged.deleteCharAt(merged.lastIndexOf(","));
        merged.append("  ]\n}");

        String result = merged.toString()
                .replaceFirst("\"total\"\\s*:\\s*\\d+", "\"total\": " + (existingCount + newAds.size()))
                .replaceFirst("\"generated_at\"\\s*:\\s*\"[^\"]+\"", "\"generated_at\": \"" + LocalDateTime.now() + "\"");

        Files.writeString(file.toPath(), result, StandardCharsets.UTF_8);
    }

    public static void saveAllIds(List<Announcement> ads) throws IOException {
        StringBuilder sb = new StringBuilder("{\n  \"total\": " + ads.size() + ",\n  \"generated_at\": \"" + LocalDateTime.now() + "\",\n  \"ads\": [\n");
        for (int i = 0; i < ads.size(); i++) {
            Announcement a = ads.get(i);
            sb.append("    {\n")
                    .append("      \"id\": \"").append(esc(a.getId())).append("\",\n")
                    .append("      \"city\": \"").append(esc(a.getCity() != null ? a.getCity().getLabel() : "")).append("\",\n")
                    .append("      \"category\": \"").append(esc(a.getCategory() != null ? a.getCategory().getLabel() : "")).append("\",\n")
                    .append("      \"title\": \"").append(esc(a.getTitle())).append("\",\n")
                    .append("      \"price\": \"").append(esc(a.getPriceRaw())).append("\",\n")
                    .append("      \"location\": \"").append(esc(a.getLocation())).append("\",\n")
                    .append("      \"url\": \"").append(esc(a.getUrl())).append("\"\n")
                    .append("    }").append(i < ads.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n}");
        Files.writeString(Paths.get(ALL_IDS_FILE), sb.toString(), StandardCharsets.UTF_8);
    }

    public static void saveAdDetail(Announcement d) throws IOException {
        String filePath = POSTS_DIR + "/post_" + d.getId().replaceAll("[^a-zA-Z0-9_\\-]", "_") + ".json";
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"id\": \"").append(esc(d.getId())).append("\",\n")
                .append("  \"city\": \"").append(esc(d.getCity() != null ? d.getCity().getLabel() : "")).append("\",\n")
                .append("  \"category\": \"").append(esc(d.getCategory() != null ? d.getCategory().getLabel() : "")).append("\",\n")
                .append("  \"url\": \"").append(esc(d.getUrl())).append("\",\n")
                .append("  \"title\": \"").append(esc(d.getTitle())).append("\",\n")
                .append("  \"price\": \"").append(esc(d.getPriceRaw())).append("\",\n")
                .append("  \"location\": \"").append(esc(d.getLocation())).append("\",\n")
                .append("  \"date_published\": \"").append(esc(d.getDatePublished())).append("\",\n")
                .append("  \"seller\": \"").append(esc(d.getSeller())).append("\",\n")
                .append("  \"description\": \"").append(esc(d.getDescription())).append("\",\n")
                .append("  \"params\": [");

        for (int i = 0; i < d.getParams().size(); i++) {
            sb.append("\n    \"").append(esc(d.getParams().get(i))).append("\"").append(i < d.getParams().size() - 1 ? "," : "");
        }
        sb.append("\n  ],\n  \"photos\": [");
        for (int i = 0; i < d.getPhotos().size(); i++) {
            sb.append("\n    \"").append(esc(d.getPhotos().get(i))).append("\"").append(i < d.getPhotos().size() - 1 ? "," : "");
        }
        sb.append("\n  ]\n}");
        Files.writeString(Paths.get(filePath), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
