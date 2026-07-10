package core.tools.dimRia;

import model.Announcement;
import model.ProjectFolder;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DimRiaStorageService {
    private static final String ROOT         = ProjectFolder.ROOT.getName();
    private static final String OUTPUT       = ProjectFolder.OUTPUT_DIR.getName();
    private static final String POSTS_DIR    = ROOT + "/" + OUTPUT + "/dimria_details";
    private static final String IDS_DIR      = ROOT + "/" + OUTPUT + "/dimria_ids";
    private static final String ALL_IDS_FILE = IDS_DIR + "/all_ids.json";
    private static final String META_FILE    = IDS_DIR + "/meta.json";
    private static final DateTimeFormatter DT  = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public static void initDirectories() throws IOException {
        Files.createDirectories(Paths.get(POSTS_DIR));
        Files.createDirectories(Paths.get(IDS_DIR));
    }

    public static String getRootAbsolutePath() {
        return new File(ROOT).getAbsolutePath();
    }

    public static String getPostsDir() {
        return POSTS_DIR;
    }

    public static boolean isUpdateNeeded(long hours, PrintStream log) {
        File meta = new File(META_FILE);
        if (!meta.exists()) return true;
        try {
            String content = Files.readString(meta.toPath(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"last_update\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
            if (!m.find()) return true;

            LocalDateTime lastUpdate = LocalDateTime.parse(m.group(1), DT);
            if (lastUpdate.isAfter(LocalDateTime.now().minusHours(hours))) {
                log.printf("⏱ Останнє оновлення DimRia: %s%n", lastUpdate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")));
                return false;
            }
        } catch (Exception e) {
            log.println("⚠️ Не вдалося прочитати meta.json для DimRia: " + e.getMessage());
        }
        return true;
    }

    public static void saveLastUpdateTime() throws IOException {
        String json = "{\n  \"last_update\": \"" + LocalDateTime.now().format(DT) + "\"\n}";
        Files.writeString(Paths.get(META_FILE), json, StandardCharsets.UTF_8);
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
            log.println("⚠️ Помилка читання all_ids.json DimRia: " + e.getMessage());
        }
        return ids;
    }

    public static void saveAdDetailJson(String id, String jsonState) throws IOException {
        String filePath = POSTS_DIR + "/post_" + id + ".json";
        Files.writeString(Paths.get(filePath), jsonState, StandardCharsets.UTF_8);
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

    private static void saveAllIds(List<Announcement> ads) throws IOException {
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

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}