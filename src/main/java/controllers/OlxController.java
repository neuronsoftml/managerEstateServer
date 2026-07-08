package controllers;

import model.City;
import model.ProjectFolder;
import model.CategoryLocation;
import model.olx.Announcement;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OLX парсер з підтримкою:
 *  - Динамічних категорій пошуку (OlxSearchCategory)
 *  - Динамічного списку міст (OlxCity)
 *  - Глобальної дедуплікації між усіма комбінаціями місто×категорія
 *  - Інкрементального оновлення (пропуск вже збережених ID)
 */
public class OlxController {

    // ── Налаштування ──────────────────────────────────────────────────────────

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final int TIMEOUT_MS             = 15_000;
    private static final int DELAY_BETWEEN_PAGES_MS = 2_000;
    private static final int DELAY_BETWEEN_POSTS_MS = 1_500;

    private static final String OLX_BASE = "https://www.olx.ua/uk/nedvizhimost/kvartiry/";

    // ── Активні міста та категорії ────────────────────────────────────────────
    // Щоб додати або прибрати — просто редагуй ці масиви

    private static final City[] ACTIVE_CITIES = {
            City.CHERNIVTSI,
            City.GODILIV,
            City.SADGORA
    };

    private static final CategoryLocation[] ACTIVE_CATEGORIES = {
            CategoryLocation.RENT_LONG,
            CategoryLocation.SALE
    };

    // ── Шляхи ─────────────────────────────────────────────────────────────────

    private static final String ROOT         = ProjectFolder.ROOT.getName();
    private static final String OUTPUT       = ProjectFolder.OUTPUT_DIR.getName();
    private static final String POSTS_DIR    = ROOT + "/" + OUTPUT + "/" + ProjectFolder.OLX_DETAILS.getName();
    private static final String IDS_DIR      = ROOT + "/" + OUTPUT + "/" + ProjectFolder.OLX_IDS.getName();
    private static final String ALL_IDS_FILE = IDS_DIR + "/" + ProjectFolder.ALL_IDS_FILE.getName();
    private static final String META_FILE    = IDS_DIR + "/" + ProjectFolder.META_FILE.getName(); // зберігає час останнього оновлення

    private static final Pattern ID_FROM_URL   = Pattern.compile(ProjectFolder.ID_FROM_URL.toString());
    private static final DateTimeFormatter DT  = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static PrintStream log;

    // ── Точка входу ───────────────────────────────────────────────────────────

    public static void start(PrintStream printStream) {
        log = printStream;
        try {
            Files.createDirectories(Paths.get(POSTS_DIR));
            Files.createDirectories(Paths.get(IDS_DIR));
            log.println("📁 Робоча директорія: " + new File(ROOT).getAbsolutePath());

            // ── Крок 0: Перевірка чи потрібне оновлення ──────────────────────
            if (!isUpdateNeeded()) {
                log.println("⏳ База оновлювалась менше 6 годин тому. Пропускаємо.");
                return;
            }

            // ── ЕТАП 1: Збір усіх ID ─────────────────────────────────────────
            log.println("\n=== ЕТАП 1: Збір ID оголошень ===");
            log.printf("🏙 Міста: %d | 📂 Категорії: %d | Комбінацій: %d%n",
                    ACTIVE_CITIES.length,
                    ACTIVE_CATEGORIES.length,
                    ACTIVE_CITIES.length * ACTIVE_CATEGORIES.length);

            // Завантажуємо вже відомі ID (інкрементальне оновлення)
            Set<String> existingIds = loadExistingIds();
            log.printf("📂 Вже збережено ID: %d%n", existingIds.size());

            // Збираємо свіжі ID по всіх комбінаціях місто × категорія
            // LinkedHashMap — зберігає порядок і автоматично дедуплікує по id
            Map<String, Announcement> freshAdsMap = new LinkedHashMap<>();

            for (City city : ACTIVE_CITIES) {
                for (CategoryLocation category : ACTIVE_CATEGORIES) {
                    String url = buildUrl(city, category);
                    log.printf("%n  🔍 %s → %s%n", city.getLabel(), category.getLabel());
                    try {
                        List<Announcement> pageAds = collectAllPages(url, city, category);
                        int before = freshAdsMap.size();
                        for (Announcement ad : pageAds) freshAdsMap.putIfAbsent(ad.getId(), ad);
                        int added = freshAdsMap.size() - before;
                        log.printf("  📊 Знайдено: %d | Нових: %d | Дублів: %d%n",
                                pageAds.size(), added, pageAds.size() - added);

                    } catch (org.jsoup.HttpStatusException e) {
                        // 404 — пропускаємо цю комбінацію, не валимо весь процес
                        log.printf("  ⚠️ Пропущено %s/%s — HTTP %d (slug не знайдено на OLX)%n",
                                city.getLabel(), category.getLabel(), e.getStatusCode());
                    } catch (Exception e) {
                        log.printf("  ❌ Помилка %s/%s: %s%n",
                                city.getLabel(), category.getLabel(), e.getMessage());
                    }

                    Thread.sleep(DELAY_BETWEEN_PAGES_MS);
                }
            }

            log.printf("%n📦 Всього унікальних з сайту: %d%n", freshAdsMap.size());

            // ── Фільтрація: відкидаємо вже збережені (інкрементальне оновлення)
            List<Announcement> newAds       = new ArrayList<>();
            List<Announcement> skippedAds   = new ArrayList<>();

            for (Announcement ad : freshAdsMap.values()) {
                if (existingIds.contains(ad.getId())) {
                    skippedAds.add(ad);
                } else {
                    newAds.add(ad);
                }
            }

            log.printf("✅ Нових (не в базі): %d | ⏭ Пропущено (вже є): %d%n",
                    newAds.size(), skippedAds.size());

            if (newAds.isEmpty()) {
                log.println("\n🎉 Нових оголошень немає — база актуальна.");
                saveLastUpdateTime();
                return;
            }

            // Дописуємо нові ID до файлу
            appendNewIds(newAds, existingIds.size());
            log.printf("📄 all_ids.json оновлено: +%d записів (всього: %d)%n",
                    newAds.size(), existingIds.size() + newAds.size());

            // ── ЕТАП 2: Деталі тільки нових ──────────────────────────────────
            log.println("\n=== ЕТАП 2: Завантаження деталей НОВИХ оголошень ===");
            log.printf("📋 До обробки: %d%n", newAds.size());

            int success = 0, failed = 0;
            for (int i = 0; i < newAds.size(); i++) {
                Announcement ad = newAds.get(i);
                log.printf("[%d/%d] %s | %s | ID: %s%n",
                        i + 1, newAds.size(), ad.getCity().getLabel(), ad.getCategory().getLabel(), ad.getId());
                try {
                    saveAdDetail(fetchAdDetail(ad));
                    success++;
                    log.printf("  ✅ post_%s.json%n", ad.getId());
                } catch (Exception e) {
                    failed++;
                    log.printf("  ❌ %s%n", e.getMessage());
                }
                if (i < newAds.size() - 1) Thread.sleep(DELAY_BETWEEN_POSTS_MS);
            }

            saveLastUpdateTime();
            log.printf("%n=== ГОТОВО === ✅ Збережено: %d | ❌ Помилки: %d | ⏭ Пропущено дублів: %d%n",
                    success, failed, skippedAds.size());

        } catch (Exception e) {
            log.println("💥 Критична помилка: " + e.getMessage());
            e.printStackTrace(log);
        }
    }

    // ── Побудова URL ──────────────────────────────────────────────────────────

    /**
     * Будує URL пошуку для конкретного міста і категорії.
     * Формат: https://www.olx.ua/uk/nedvizhimost/kvartiry/{category}/{city}/
     */
    private static String buildUrl(City city, CategoryLocation category) {
        return OLX_BASE + category.getUrlSegment() + "/" + city.getSlug() + "/";
    }

    // ── Перевірка чи потрібне оновлення ──────────────────────────────────────

    /**
     * Читає meta.json і перевіряє чи минуло 6+ годин з останнього оновлення.
     * Якщо meta.json не існує — оновлення потрібне (перший запуск).
     */
    private static boolean isUpdateNeeded() {
        File meta = new File(META_FILE);
        if (!meta.exists()) return true;

        try {
            String content = new String(Files.readAllBytes(meta.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            Pattern p = Pattern.compile("\"last_update\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = p.matcher(content);
            if (!m.find()) return true;

            LocalDateTime lastUpdate = LocalDateTime.parse(m.group(1), DT);
            LocalDateTime threshold  = LocalDateTime.now().minusHours(6);
            boolean needed = lastUpdate.isBefore(threshold);

            if (!needed) {
                log.printf("⏱ Останнє оновлення: %s%n", lastUpdate.format(
                        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")));
            }
            return needed;

        } catch (Exception e) {
            log.println("⚠️ Не вдалося прочитати meta.json: " + e.getMessage());
            return true;
        }
    }

    private static void saveLastUpdateTime() throws IOException {
        String json = "{\n  \"last_update\": \"" + LocalDateTime.now().format(DT) + "\"\n}";
        try (FileWriter fw = new FileWriter(META_FILE, java.nio.charset.StandardCharsets.UTF_8)) {
            fw.write(json);
        }
    }

    // ── Завантаження існуючих ID ──────────────────────────────────────────────

    private static Set<String> loadExistingIds() {
        File file = new File(ALL_IDS_FILE);
        if (!file.exists()) {
            log.println("📂 all_ids.json не знайдено — перший запуск.");
            return new HashSet<>();
        }
        Set<String> ids = new HashSet<>();
        try {
            String content = new String(Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
            while (m.find()) ids.add(m.group(1));
        } catch (IOException e) {
            log.println("⚠️ Помилка читання all_ids.json: " + e.getMessage());
        }
        return ids;
    }

    // ── ЕТАП 1: Збір ID з усіх сторінок ──────────────────────────────────────

    private static List<Announcement> collectAllPages(String baseUrl,
                                                   City city,
                                                   CategoryLocation category) throws Exception {
        List<Announcement> results = new ArrayList<>();
        int page = 1;

        while (true) {
            String url = (page == 1) ? baseUrl : baseUrl + "?page=" + page;
            log.printf("    [стор. %d] %s%n", page, url);

            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .get();

            Elements cards = doc.select("[data-cy='l-card']");
            if (cards.isEmpty()) {
                log.printf("    Сторінка %d порожня — кінець пагінації.%n", page);
                break;
            }

            int before = results.size();
            for (Element card : cards) {
                String link = card.select("a[href]").first() != null
                        ? card.select("a[href]").first().absUrl("href") : "";
                if (link.isEmpty()) continue;

                String cleanLink = link.contains("?") ? link.substring(0, link.indexOf("?")) : link;
                String id = extractId(cleanLink);
                if (id.isEmpty()) continue;

                results.add(new Announcement(
                        id,
                        cleanLink,
                        card.select("h4, h6").text().trim(),
                        card.select("[data-testid='ad-price']").text().trim(),
                        card.select("[data-testid='location-date']").text().trim(),
                        city,
                        category
                ));
            }
            log.printf("    Картки: %d (на сторінці: %d)%n", results.size(), results.size() - before);

            // Перевірка наступної сторінки
            boolean hasNext = !doc.select("a[href*='page=" + (page + 1) + "']").isEmpty()
                    || !doc.select("[data-cy='pagination-forward']").isEmpty()
                    || doc.select("a:containsOwn(Вперед)").stream()
                    .anyMatch(a -> !a.attr("href").isEmpty());

            if (!hasNext) break;
            page++;
            Thread.sleep(DELAY_BETWEEN_PAGES_MS);
        }

        return results;
    }

    private static String extractId(String url) {
        Matcher m = ID_FROM_URL.matcher(url);
        return m.find() ? m.group(1) : "";
    }

    // ── ЕТАП 2: Деталі оголошення ─────────────────────────────────────────────

    private static Announcement fetchAdDetail(Announcement announcement) throws Exception {
        Document doc = Jsoup.connect(announcement.getUrl())
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .get();

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
                    "p:contains(" + announcement.getCity().getLabel() + "), p:contains(обл.)"
            ).first();
            location = locEl != null ? locEl.text().trim() : "";
        }
        // Останній запасний — беремо місто з enum щоб поле не було порожнім
        if (location.isEmpty()) location = announcement.getCity().getLabel();

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
                announcement.getId(),
                announcement.getCity().getLabel(),
                announcement.getCategory().getLabel(),
                announcement.getUrl(),
                announcement.getTitle(),
                announcement.getPriceRaw(),
                announcement.getLocation(),
                datePublished,
                seller,
                null,
                description,
                params,
                photos
               
        );
    }

    // ── Збереження all_ids.json (дозапис) ────────────────────────────────────

    private static void appendNewIds(List<Announcement> newAds, int existingCount) throws IOException {
        File file = new File(ALL_IDS_FILE);

        if (!file.exists() || existingCount == 0) {
            saveAllIds(newAds);
            return;
        }

        String content = new String(Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        StringBuilder newEntries = new StringBuilder();
        for (Announcement a : newAds) {
            newEntries.append("    {\n");
            newEntries.append("      \"id\": \"").append(esc(a.getId())).append("\",\n");
            newEntries.append("      \"city\": \"").append(esc(a.getCity().getLabel())).append("\",\n");
            newEntries.append("      \"category\": \"").append(esc(a.getCategory().getLabel())).append("\",\n");
            newEntries.append("      \"title\": \"").append(esc(a.getTitle())).append("\",\n");
            newEntries.append("      \"price\": \"").append(esc(a.getTitle())).append("\",\n");
            newEntries.append("      \"location\": \"").append(esc(a.getLocation())).append("\",\n");
            newEntries.append("      \"url\": \"").append(esc(a.getUrl())).append("\"\n");
            newEntries.append("    },\n");
        }

        int lastBracket = content.lastIndexOf("]");
        if (lastBracket == -1) { saveAllIds(newAds); return; }

        String beforeBracket = content.substring(0, lastBracket).stripTrailing();
        boolean needsComma   = beforeBracket.endsWith("}");

        StringBuilder merged = new StringBuilder(content.substring(0, lastBracket));
        if (needsComma) merged.append(",\n");
        merged.append(newEntries);
        int lastComma = merged.lastIndexOf(",");
        merged.deleteCharAt(lastComma);
        merged.append("  ]\n}");

        int newTotal = existingCount + newAds.size();
        String result = merged.toString()
                .replaceFirst("\"total\"\\s*:\\s*\\d+", "\"total\": " + newTotal)
                .replaceFirst("\"generated_at\"\\s*:\\s*\"[^\"]+\"",
                        "\"generated_at\": \"" + LocalDateTime.now() + "\"");

        try (FileWriter fw = new FileWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
            fw.write(result);
        }
    }

    private static void saveAllIds(List<Announcement> ads) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"total\": ").append(ads.size()).append(",\n");
        sb.append("  \"generated_at\": \"").append(LocalDateTime.now()).append("\",\n");
        sb.append("  \"ads\": [\n");
        for (int i = 0; i < ads.size(); i++) {
            Announcement a = ads.get(i);
            sb.append("    {\n");
            sb.append("      \"id\": \"").append(esc(a.getId())).append("\",\n");
            sb.append("      \"city\": \"").append(esc(a.getCity().getLabel())).append("\",\n");
            sb.append("      \"category\": \"").append(esc(a.getCategory().getLabel())).append("\",\n");
            sb.append("      \"title\": \"").append(esc(a.getTitle())).append("\",\n");
            sb.append("      \"price\": \"").append(esc(a.getPriceRaw())).append("\",\n");
            sb.append("      \"location\": \"").append(esc(a.getLocation())).append("\",\n");
            sb.append("      \"url\": \"").append(esc(a.getUrl())).append("\"\n");
            sb.append("    }").append(i < ads.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n}");
        try (FileWriter fw = new FileWriter(ALL_IDS_FILE, java.nio.charset.StandardCharsets.UTF_8)) {
            fw.write(sb.toString());
        }
    }

    // ── Збереження post_{id}.json ─────────────────────────────────────────────

    private static void saveAdDetail(Announcement d) throws IOException {
        String filePath = POSTS_DIR + "/post_" + d.getId().replaceAll("[^a-zA-Z0-9_\\-]", "_") + ".json";
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"id\": \"").append(esc(d.getId())).append("\",\n");
        sb.append("  \"city\": \"").append(esc(d.getCity().getLabel())).append("\",\n");
        sb.append("  \"category\": \"").append(esc(d.getCategory().getLabel())).append("\",\n");
        sb.append("  \"url\": \"").append(esc(d.getUrl())).append("\",\n");
        sb.append("  \"title\": \"").append(esc(d.getTitle())).append("\",\n");
        sb.append("  \"price\": \"").append(esc(d.getPriceRaw())).append("\",\n");
        sb.append("  \"location\": \"").append(esc(d.getLocation())).append("\",\n");
        sb.append("  \"date_published\": \"").append(esc(d.getDatePublished())).append("\",\n");
        sb.append("  \"seller\": \"").append(esc(d.getSeller())).append("\",\n");
        sb.append("  \"description\": \"").append(esc(d.getDescription())).append("\",\n");
        sb.append("  \"params\": [\n");
        for (int i = 0; i < d.getParams().size(); i++) {
            sb.append("    \"").append(esc(d.getParams().get(i))).append("\"");
            sb.append(i < d.getParams().size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n");
        sb.append("  \"photos\": [\n");
        for (int i = 0; i < d.getPhotos().size(); i++) {
            sb.append("    \"").append(esc(d.getPhotos().get(i))).append("\"");
            sb.append(i < d.getPhotos().size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n}");
        try (FileWriter fw = new FileWriter(filePath, java.nio.charset.StandardCharsets.UTF_8)) {
            fw.write(sb.toString());
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}