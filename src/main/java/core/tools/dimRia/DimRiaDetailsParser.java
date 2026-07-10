package core.tools.dimRia;

import model.Announcement;
import model.CategoryLocation;
import model.City;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DimRiaDetailsParser {
    private static final Pattern P_STRING = Pattern.compile("\"(%s)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern P_ARRAY_ITEMS = Pattern.compile("\"(%s)\"\\s*:\\s*\\[([^\\]]*)]", Pattern.DOTALL);
    private static final Pattern P_ARRAY_VALUE = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    public static List<Announcement> parseDirectory(File directory, boolean onlyNew) {
        List<Announcement> results = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.startsWith("post_") && name.endsWith(".json"));

        if (files == null) return results;

        for (File file : files) {
            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);

                String id = extractField(content, "id");
                String url = extractField(content, "url");
                String title = extractField(content, "title");
                String priceRaw = extractField(content, "price");
                String location = extractField(content, "location");
                String datePublished = extractField(content, "date_published");
                String seller = extractField(content, "seller");
                String description = extractField(content, "description");

                String cityLabel = extractField(content, "city");
                String categoryLabel = extractField(content, "category");

                List<String> params = extractArray(content, "params");
                List<String> photos = extractArray(content, "photos");

                // Зворотний мапінг Enum на основі текстових міток (Labels)
                City city = City.CHERNIVTSI; // фолбек за замовчуванням
                for (City c : City.values()) {
                    if (c.getLabel().equalsIgnoreCase(cityLabel)) { city = c; break; }
                }

                CategoryLocation category = CategoryLocation.RENT_LONG;
                for (CategoryLocation cl : CategoryLocation.values()) {
                    if (cl.getLabel().equalsIgnoreCase(categoryLabel)) { category = cl; break; }
                }

                Announcement a = new Announcement(
                        id, city.getLabel(), category.getLabel(), url, title,
                        priceRaw, location, datePublished, seller, null, description, params, photos
                );

                // Передаємо об'єкти Enum, щоб працювала фільтрація або ТГ-нотифікації
                a.setCity(city);
                a.setCategory(category);

                results.add(a);
            } catch (Exception e) {
                System.err.println("❌ Помилка парсингу файлу " + file.getName() + ": " + e.getMessage());
            }
        }
        return results;
    }

    private static String extractField(String json, String key) {
        Matcher m = Pattern.compile(String.format(P_STRING.pattern(), key)).matcher(json);
        if (m.find()) {
            return m.group(2).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
        }
        return "";
    }

    private static List<String> extractArray(String json, String key) {
        List<String> list = new ArrayList<>();
        Matcher mArray = Pattern.compile(String.format(P_ARRAY_ITEMS.pattern(), key)).matcher(json);
        if (mArray.find()) {
            Matcher mValue = P_ARRAY_VALUE.matcher(mArray.group(2));
            while (mValue.find()) {
                list.add(mValue.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
            }
        }
        return list;
    }
}
