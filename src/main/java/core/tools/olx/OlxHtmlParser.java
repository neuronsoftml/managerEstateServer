package core.tools.olx;

import model.City;
import model.CategoryLocation;
import model.Announcement;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OlxHtmlParser {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 15_000;
    private static final Pattern ID_FROM_URL = Pattern.compile("-ID([A-Za-z0-9]+)\\.html");

    public static Document getDocument(String url) throws Exception {
        return Jsoup.connect(url).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
    }

    public static List<Announcement> parsePageCards(Document doc, City city, CategoryLocation category) {
        List<Announcement> results = new ArrayList<>();
        Elements cards = doc.select("[data-cy='l-card']");

        for (Element card : cards) {
            Element linkEl = card.select("a[href]").first();
            if (linkEl == null) continue;
            String link = linkEl.absUrl("href");
            String cleanLink = link.contains("?") ? link.substring(0, link.indexOf("?")) : link;
            String id = extractId(cleanLink);
            if (id.isEmpty()) continue;

            // Використовуємо новий конструктор Announcement
            results.add(new Announcement(
                    id, cleanLink, card.select("h4, h6").text().trim(),
                    card.select("[data-testid='ad-price']").text().trim(),
                    card.select("[data-testid='location-date']").text().trim(),
                    city, category
            ));
        }
        return results;
    }

    public static boolean hasNextPage(Document doc, int page) {
        return !doc.select("a[href*='page=" + (page + 1) + "']").isEmpty()
                || !doc.select("[data-cy='pagination-forward']").isEmpty()
                || doc.select("a:containsOwn(Вперед)").stream().anyMatch(a -> !a.attr("href").isEmpty());
    }

    public static Announcement fillDetails(Announcement ad, Document doc) {
        // ── Заголовок ─────────────────────────────────────────────────────────
        // og:title найнадійніший — завжди присутній у <head>
        // Формат: "Назва оголошення: ціна - Категорія на OLX"
        String title = doc.select("meta[property='og:title']").attr("content");
        if (!title.isEmpty() && title.contains(" - ")) {
            title = title.substring(0, title.lastIndexOf(" - ")).trim();
        }
        if (title.isEmpty()) title = doc.select("h4[data-cy='ad_title']").text();
        if (title.isEmpty()) title = doc.select("h1").text();

        // ── Ціна ──────────────────────────────────────────────────────────────
        String price = doc.select("[data-testid='ad-price-container']").text();
        if (price.isEmpty()) price = doc.select("h3.css-90xrc0, strong.css-kx4f5o").text();

        // ── Опис ──────────────────────────────────────────────────────────────
        String description = doc.select("[data-cy='ad_description'] div").text();
        if (description.isEmpty()) description = doc.select("[data-cy='ad_description']").text();

        // ── Локація ───────────────────────────────────────────────────────────
        String location = doc.select("[data-testid='location-with-link-region']").text();
        if (location.isEmpty()) {
            location = doc.select("[data-testid='ad-contact-location']").text();
        }
        if (location.isEmpty()) {
            // Шукаємо параграф що містить назву міста або "обл."
            Element locEl = doc.select(
                    "p:contains(" + ad.getCity().getLabel() + "), p:contains(обл.)"
            ).first();
            location = locEl != null ? locEl.text().trim() : "";
        }
        // Останній запасний — беремо місто з enum щоб поле не було порожнім
        if (location.isEmpty()) location = ad.getCity().getLabel();

        // ── Дата публікації ───────────────────────────────────────────────────
        String datePublished = doc.select("[data-cy='ad-posted-at']").text();
        if (datePublished.isEmpty()) {
            datePublished = doc.select("span:containsOwn(Опубліковано)").text();
        }

        // ── Продавець ─────────────────────────────────────────────────────────
        String seller = doc.select("[data-testid='seller-name']").text();
        if (seller.isEmpty()) {
            // Ім'я продавця є в посиланні на його профіль
            Element sellerLink = doc.select("a[href*='/uk/list/user/']").first();
            if (sellerLink != null) {
                seller = sellerLink.text().trim();
                // Прибираємо зайвий текст типу "на OLX з 2021 року"
                if (seller.contains("на OLX")) {
                    seller = seller.substring(0, seller.indexOf("на OLX")).trim();
                }
            }
        }

        // ── Параметри (площа, поверх, кількість кімнат тощо) ─────────────────
        List<String> params = new ArrayList<>();

        // Основний селектор
        for (Element li : doc.select("ul[data-testid='ad-attributes'] li")) {
            String p = li.text().trim();
            if (!p.isEmpty()) params.add(p);
        }

        // Запасний 1: div-и з відомими ключовими словами
        if (params.isEmpty()) {
            for (Element el : doc.select(
                    "p:contains(Поверх), p:contains(Площа), p:contains(Кімнат)," +
                            "li:contains(Поверх), li:contains(Площа), li:contains(Кімнат)," +
                            "div:contains(Поверх:), div:contains(Загальна площа:)," +
                            "div:contains(Кількість кімнат:), div:contains(Тип стін:)")) {
                // Беремо тільки листові вузли щоб не дублювати батьківський текст
                if (el.children().isEmpty()) {
                    String p = el.text().trim();
                    if (!p.isEmpty() && p.length() < 150) params.add(p);
                }
            }
        }

        // Запасний 2: клас offer-details або param
        if (params.isEmpty()) {
            for (Element el : doc.select("[class*='offer-details'], [class*='param']")) {
                String p = el.text().trim();
                if (!p.isEmpty() && p.length() < 150) params.add(p);
            }
        }

        // ── Фото ──────────────────────────────────────────────────────────────
        List<String> photos = new ArrayList<>();

        // Основний: img з data-testid='ad-photo'
        for (Element img : doc.select("img[data-testid='ad-photo']")) {
            String src = img.attr("src");
            if (src.isEmpty()) src = img.attr("data-src");
            if (!src.isEmpty() && !photos.contains(src)) photos.add(src);
        }

        // Запасний 1: swiper слайдер
        if (photos.isEmpty()) {
            for (Element img : doc.select(
                    ".swiper-slide img, [data-testid='swiper-image'] img")) {
                String src = img.attr("src");
                if (!src.isEmpty() && !photos.contains(src)) photos.add(src);
            }
        }

        // Запасний 2: будь-який img з CDN olxcdn.com
        if (photos.isEmpty()) {
            for (Element img : doc.select("img[src*='olxcdn.com']")) {
                String src = img.attr("src");
                if (!src.isEmpty() && !photos.contains(src)) photos.add(src);
            }
        }

        return new Announcement(
                ad.getId(),
                ad.getCity(),      // ФІКС: передаємо готовий enum напряму,
                ad.getCategory(),  // без round-trip через getLabel()/findByRawString()
                ad.getUrl(),
                ad.getTitle(),
                ad.getPriceRaw(),
                location,
                datePublished,
                seller,
                null,
                description,
                params,
                photos
        );
    }

    private static String extractId(String url) {
        Matcher m = ID_FROM_URL.matcher(url);
        return m.find() ? m.group(1) : "";
    }
}