package core.tools.parser.olx;

import model.City;
import model.AnnouncementCategory;
import model.Announcement;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Парсер HTML-сторінок платформи OLX.
 * Відповідає за завантаження документів за посиланням, збір прев'ю-карток оголошень
 * зі списку категорій та детальний розбір (scraping) повної інформації зі сторінки об'єкта.
 * * @author Mykola
 */
public class OlxHtmlParser {

    // User-Agent реального браузера для уникнення блокувань з боку системи захисту OLX
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // Максимальний час очікування відповіді від сервера (15 секунд)
    private static final int TIMEOUT_MS = 15_000;

    // Регулярний вираз для вилучення унікального ID оголошення з його URL (наприклад, "...-IDx7Y2b.html")
    private static final Pattern ID_FROM_URL = Pattern.compile("-ID([A-Za-z0-9]+)\\.html");

    /**
     * Завантажує та парсить HTML-документ за вказаним URL за допомогою Jsoup.
     *
     * @param url посилання на сторінку OLX
     * @return об'єкт {@link Document}, готовий до вибірки даних через CSS-селектори
     * @throws Exception якщо виникла помилка мережі або таймаут
     */
    public static Document getDocument(String url) throws Exception {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .get();
    }

    /**
     * Парсить головну сторінку списку (категорії) та збирає базові прев'ю-картки оголошень.
     * Використовує спрощений конструктор {@link Announcement} без повного опису та параметрів.
     *
     * @param doc      HTML-документ сторінки списку оголошень
     * @param city     об'єкт міста (Enum)
     * @param category об'єкт категорії (Enum)
     * @return список базових об'єктів {@link Announcement}
     */
    public static List<Announcement> parsePageCards(Document doc, City city, AnnouncementCategory category) {
        List<Announcement> results = new ArrayList<>();
        // Отримуємо всі блоки карток оголошень на сторінці списку
        Elements cards = doc.select("[data-cy='l-card']");

        for (Element card : cards) {
            Element linkEl = card.select("a[href]").first();
            if (linkEl == null) continue;

            // Формуємо абсолютне посилання та очищуємо його від UTM-міток та GET-параметрів
            String link = linkEl.absUrl("href");
            String cleanLink = link.contains("?") ? link.substring(0, link.indexOf("?")) : link;

            // Вилучаємо ID оголошення. Якщо лінк веде на зовнішній сайт (наприклад, рекламу Otodom), ігноруємо
            String id = extractId(cleanLink);
            if (id.isEmpty()) continue;

            // Зберігаємо картку, використовуючи швидкий прев'ю-конструктор
            results.add(new Announcement(
                    id,
                    cleanLink,
                    card.select("h4, h6").text().trim(),                          // Заголовок картки
                    card.select("[data-testid='ad-price']").text().trim(),        // Ціна на картці
                    card.select("[data-testid='location-date']").text().trim(),   // Локація та дата знизу картки
                    city,
                    category
            ));
        }
        return results;
    }

    /**
     * Перевіряє, чи є наступна сторінка у пагінації для поточного списку оголошень.
     *
     * @param doc  HTML-документ сторінки списку
     * @param page номер поточної сторінки
     * @return true, якщо існує наступна сторінка для парсингу
     */
    public static boolean hasNextPage(Document doc, int page) {
        return !doc.select("a[href*='page=" + (page + 1) + "']").isEmpty()
                || !doc.select("[data-cy='pagination-forward']").isEmpty()
                || doc.select("a:containsOwn(Вперед)").stream().anyMatch(a -> !a.attr("href").isEmpty());
    }

    /**
     * Наповнює вже існуюче базове оголошення (карту) детальною інформацією,
     * зібраною безпосередньо зі сторінки самого оголошення.
     *
     * @param ad  базове оголошення, отримане з прев'ю-картки
     * @param doc HTML-документ детальної сторінки оголошення
     * @return новий об'єкт {@link Announcement} із заповненими детальними полями
     */
    public static Announcement fillDetails(Announcement ad, Document doc) {
        String title         = parseTitle(doc);
        String price         = parsePrice(doc);
        String description   = parseDescription(doc);
        String location      = parseLocation(doc, ad.getCity().getLabel());
        String datePublished = parseDatePublished(doc);
        String seller        = parseSeller(doc);
        List<String> params  = parseParams(doc);
        List<String> photos  = parsePhotos(doc);

        return new Announcement(
                ad.getId(), ad.getCity(), ad.getCategory(), ad.getUrl(),
                title, price, location, datePublished, seller, null,
                description, params, photos
        );
    }

    // ── Приватні методи-помічники для чистоти коду та легкої підтримки ──

    /**
     * Парсить заголовок оголошення.
     * Найнадійніший спосіб — тег "og:title" в <head>, оскільки він завжди чистий і стабільний.
     */
    private static String parseTitle(Document doc) {
        String title = doc.select("meta[property='og:title']").attr("content");
        // Якщо ціна є в заголовку, спробуємо її відрізати
        if (title.contains(" - ")) {
            title = title.substring(0, title.lastIndexOf(" - ")).trim();
        }
        // ДОДАТКОВА ПЕРЕВІРКА: якщо заголовок все ще містить знак $ або € або символ валюти
        if (title.matches(".*\\d+\\s*\\$")) {
            title = title.replaceAll("\\d+\\s*\\$", "").trim();
        }
        return title.isEmpty() ? doc.select("h1").text() : title;
    }

    /**
     * Парсить відображувану ціну об'єкта.
     */
    private static String parsePrice(Document doc) {
        String price = doc.select("[data-testid='ad-price-container']").text();
        // Fallback-селектор для старих версій верстки або особливих категорій
        return !price.isEmpty() ? price : doc.select("h3.css-90xrc0, strong.css-kx4f5o").text();
    }

    /**
     * Парсить повний текстовий опис оголошення.
     */
    private static String parseDescription(Document doc) {
        String desc = doc.select("[data-cy='ad_description'] div").text();
        // Fallback-селектор, якщо структура div-ів всередині опису зміниться
        return !desc.isEmpty() ? desc : doc.select("[data-cy='ad_description']").text();
    }

    /**
     * Парсить географічне розташування об'єкта.
     */
    private static String parseLocation(Document doc, String cityLabel) {
        // Спроба 1: Отримання локації з лінком на карту/регіон
        String loc = doc.select("[data-testid='location-with-link-region']").text();
        if (loc.isEmpty()) {
            // Спроба 2: Текстовий блок контактної локації
            loc = doc.select("[data-testid='ad-contact-location']").text();
        }
        if (loc.isEmpty()) {
            // Спроба 3: Пошук параграфа з назвою цільового міста або області
            Element locEl = doc.select("p:contains(" + cityLabel + "), p:contains(обл.)").first();
            loc = (locEl != null) ? locEl.text().trim() : "";
        }
        // Якщо нічого не знайшли — повертаємо назву міста з конфігу, щоб поле не було порожнім
        return loc.isEmpty() ? cityLabel : loc;
    }

    /**
     * Парсить дату публікації оголошення на сайті.
     */
    private static String parseDatePublished(Document doc) {
        String date = doc.select("[data-cy='ad-posted-at']").text();
        // Fallback для мобільних версій або специфічних шаблонів
        return !date.isEmpty() ? date : doc.select("span:containsOwn(Опубліковано)").text();
    }

    /**
     * Парсить ім'я автора оголошення (продавця).
     */
    private static String parseSeller(Document doc) {
        String seller = doc.select("[data-testid='seller-name']").text();
        if (seller.isEmpty()) {
            // Якщо блоку імені немає, шукаємо лінк на профіль та витягуємо ім'я з тексту посилання
            Element link = doc.select("a[href*='/uk/list/user/']").first();
            if (link != null) {
                seller = link.text().trim();
                // Очищуємо ім'я від додаткового сервісного тексту (наприклад, "Олексій на OLX з 2018")
                if (seller.contains("на OLX")) {
                    seller = seller.substring(0, seller.indexOf("на OLX")).trim();
                }
            }
        }
        return seller;
    }

    /**
     * Збирає динамічний список характеристик (кімнатність, площа, поверх тощо).
     * Використовує багаторівневі fallback-селектори для максимальної стійкості до змін верстки.
     */
    private static List<String> parseParams(Document doc) {
        List<String> params = new ArrayList<>();

        // Сценарій 1 (Основний): Елементи списку в загальному контейнері атрибутів
        for (Element li : doc.select("ul[data-testid='ad-attributes'] li")) {
            String p = li.text().trim();
            if (!p.isEmpty()) params.add(p);
        }
        if (!params.isEmpty()) return params;

        // Сценарій 2 (Запасний): Пошук окремих тегів, які містять ключові слова характеристик нерухомості
        for (Element el : doc.select(
                "p:contains(Поверх), p:contains(Площа), p:contains(Кімнат)," +
                        "li:contains(Поверх), li:contains(Площа), li:contains(Кімнат)," +
                        "div:contains(Поверх:), div:contains(Загальна площа:)," +
                        "div:contains(Кількість кімнат:), div:contains(Тип стін:)")) {
            // Перевіряємо, що елемент кінцевий (не має дітей), щоб уникнути дублювання тексту батьківських блоків
            if (el.children().isEmpty()) {
                String p = el.text().trim();
                if (!p.isEmpty() && p.length() < 150) params.add(p);
            }
        }
        if (!params.isEmpty()) return params;

        // Сценарій 3 (Запасний): Збір будь-яких елементів зі специфічними класами деталей
        for (Element el : doc.select("[class*='offer-details'], [class*='param']")) {
            String p = el.text().trim();
            if (!p.isEmpty() && p.length() < 150) params.add(p);
        }
        return params;
    }

    /**
     * Збирає посилання на фотографії об'єкта.
     * Має 3 рівні резервних селекторів (основні фото, swiper-слайдер та прямі посилання на фото-сервер).
     */
    private static List<String> parsePhotos(Document doc) {
        List<String> photos = new ArrayList<>();

        // Сценарій 1 (Основний): Зображення з атрибутом "ad-photo"
        for (Element img : doc.select("img[data-testid='ad-photo']")) {
            // Підтримуємо lazy-loading зображень (беремо src або data-src, якщо картинка ще не підвантажилась)
            String src = img.attr("src").isEmpty() ? img.attr("data-src") : img.attr("src");
            if (!src.isEmpty() && !photos.contains(src)) photos.add(src);
        }
        if (!photos.isEmpty()) return photos;

        // Сценарій 2 (Запасний): Фотографії всередині Swiper-слайдерів
        for (Element img : doc.select(".swiper-slide img, [data-testid='swiper-image'] img")) {
            String src = img.attr("src");
            if (!src.isEmpty() && !photos.contains(src)) photos.add(src);
        }
        if (!photos.isEmpty()) return photos;

        // Сценарій 3 (Запасний): Збір будь-яких зображень, що завантажуються безпосередньо з CDN-серверів OLX
        for (Element img : doc.select("img[src*='olxcdn.com']")) {
            String src = img.attr("src");
            if (!src.isEmpty() && !photos.contains(src)) photos.add(src);
        }
        return photos;
    }

    /**
     * Допоміжний метод для вилучення ID оголошення з посилання за допомогою регулярного виразу.
     */
    private static String extractId(String url) {
        Matcher m = ID_FROM_URL.matcher(url);
        return m.find() ? m.group(1) : "";
    }
}