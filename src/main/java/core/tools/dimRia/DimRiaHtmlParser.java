package core.tools.dimRia;

import model.Announcement;
import model.CategoryLocation;
import model.City;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсер HTML-структури та внутрішніх скриптів сайту DimRia.
 */
public class DimRiaHtmlParser {

    /**
     * Універсальний метод: збирає базові оголошення як зі статичних HTML-карток,
     * так і шляхом тотального сканування усіх вбудованих тегів <script> на сторінці.
     */
    public static List<Announcement> parsePageCards(Document doc, City city, CategoryLocation category) {
        List<Announcement> pageCards = new ArrayList<>();

        // Спроба 1: Збір зі звичайних HTML тегів (якщо сторінка статична)
        Elements elements = doc.select("section.realty-item, div[data-realty-id], a.realty-link");
        for (Element el : elements) {
            String id = el.attr("data-realty-id");
            if (id.isEmpty()) {
                Element linkEl = el.selectFirst("a[href*=/realty-]");
                if (linkEl != null) {
                    String href = linkEl.attr("href");
                    Matcher m = Pattern.compile("realty-(\\d+)\\.html").matcher(href);
                    if (m.find()) id = m.group(1);
                }
            }

            if (!id.isEmpty()) {
                String title = el.select(".title, h3").text().trim();
                String price = el.select(".price, .size18").text().trim();

                Announcement ad = new Announcement(
                        id, "https://dom.ria.com/uk/realty-" + id + ".html", title,
                        price, "", city, category
                );

                // Копія для стріму за рекомендацією IDE
                String finalId = id;
                if (pageCards.stream().noneMatch(c -> c.getId().equals(finalId))) {
                    pageCards.add(ad);
                }
            }
        }

        // Спроба 2: Якщо HTML-картки порожні, дістаємо ID регуляркою з УСІХ скриптів на сторінці
        if (pageCards.isEmpty()) {
            Elements scripts = doc.select("script");

            // Патерн для числових ID оголошень DimRia (від 8 до 10 знаків)
            Pattern p = Pattern.compile("\\b\\d{8,10}\\b");

            for (Element script : scripts) {
                // Об'єднуємо html та data для надійності читання контенту скрипту
                String scriptContent = script.html() + "\n" + script.data();

                // Швидка оптимізація: пропускаємо скрипти, які взагалі не стосуються нерухомості
                if (!scriptContent.contains("realty") && !scriptContent.contains("Id")) {
                    continue;
                }

                Matcher m = p.matcher(scriptContent);
                while (m.find()) {
                    String id = m.group();

                    // Копія для стріму за рекомендацією IDE (effectively final)
                    String finalId = id;

                    // Тимчасова картка (повні деталі довантажаться на Етапі 2)
                    Announcement ad = new Announcement(
                            id, "https://dom.ria.com/uk/realty-" + id + ".html",
                            "Об'єкт DimRia", "Договірна", "", city, category
                    );

                    if (pageCards.stream().noneMatch(c -> c.getId().equals(finalId))) {
                        pageCards.add(ad);
                    }
                }
            }
        }

        return pageCards;
    }

    /**
     * Перевіряє, чи є наступна сторінка пагінації в інтерфейсі списку чи JSON-контексті
     */
    public static boolean hasNextPage(Document doc) {
        // Стандартна HTML кнопка "Наступна"
        if (doc.selectFirst("a.page-link[next], a[class*=next], .pager .next a") != null) {
            return true;
        }
        // Запасна перевірка всередині тексту стейту сторінки
        String html = doc.html();
        return html.contains("\"next\":true") || html.contains("has_next_page\":true");
    }

    /**
     * Парсить сторінку деталей конкретного оголошення та збирає характеристики для OlxDetailsParser
     */
    public static String buildDetailJson(Document doc, Announcement ad) {
        // Збір посилань на фотографії
        List<String> photos = new ArrayList<>();
        for (Element img : doc.select("picture img, .carousel img, .gallery-photo")) {
            String src = img.attr("src").isEmpty() ? img.attr("data-src") : img.attr("src");
            if (!src.isEmpty() && !photos.contains(src)) photos.add(src);
        }

        // Збір текстових характеристик об'єкта
        List<String> params = new ArrayList<>();
        for (Element item : doc.select(".characteristic-item, ul.description-list li")) {
            String text = item.text().trim();
            if (!text.isEmpty()) params.add(text);
        }

        String title = doc.select("h1.heading, title").text().trim();
        String price = doc.select(".price_info strong, .price").first() != null ?
                doc.select(".price_info strong, .price").first().text().trim() : "Договірна";
        String description = doc.select("#description-text, .description").text().trim();
        String location = doc.select(".location, a[href*='#map']").text().trim();
        if (location.isEmpty()) location = ad.getCity().getLabel();

        // Формуємо структуру JSON вручну
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"id\": \"").append(esc(ad.getId())).append("\",\n")
                .append("  \"city\": \"").append(esc(ad.getCity().getLabel())).append("\",\n")
                .append("  \"category\": \"").append(esc(ad.getCategory().getLabel())).append("\",\n")
                .append("  \"url\": \"").append(esc(ad.getUrl())).append("\",\n")
                .append("  \"title\": \"").append(esc(title)).append("\",\n")
                .append("  \"price\": \"").append(esc(price)).append("\",\n")
                .append("  \"location\": \"").append(esc(location)).append("\",\n")
                .append("  \"date_published\": \"").append(esc(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))).append("\",\n")
                .append("  \"seller\": \"DimRia Seller\",\n")
                .append("  \"description\": \"").append(esc(description)).append("\",\n")
                .append("  \"params\": [");

        for (int i = 0; i < params.size(); i++) {
            sb.append("\n    \"").append(esc(params.get(i))).append("\"").append(i < params.size() - 1 ? "," : "");
        }
        sb.append("\n  ],\n  \"photos\": [");
        for (int i = 0; i < photos.size(); i++) {
            sb.append("\n    \"").append(esc(photos.get(i))).append("\"").append(i < photos.size() - 1 ? "," : "");
        }
        sb.append("\n  ]\\n}");
        return sb.toString();
    }

    /**
     * Екранування спецсимволів для формування валідного JSON
     */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}