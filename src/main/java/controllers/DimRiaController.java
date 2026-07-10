package controllers;

import core.tools.dimRia.DimRiaHtmlParser;
import core.tools.dimRia.DimRiaStorageService;
import core.tools.olx.OlxHtmlParser;
import model.CategoryLocation;
import model.City;
import model.Announcement;
import org.jsoup.nodes.Document;


import java.io.PrintStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DimRiaController {

    private static final int DELAY_PAGES = 2000;
    private static final int DELAY_POSTS = 2500;

    private static final City[] ACTIVE_CITIES = {
            City.CHERNIVTSI, City.GODILIV, City.KOROVIA, City.CHAGOR
    };

    private static final CategoryLocation[] ACTIVE_CATEGORIES = { CategoryLocation.RENT_LONG, CategoryLocation.SALE };
    private static PrintStream log;

    public static void start(PrintStream printStream) {
        log = printStream;
        try {
            DimRiaStorageService.initDirectories();
            log.println("📁 Робоча директорія DimRia: " + DimRiaStorageService.getRootAbsolutePath());

            if (!DimRiaStorageService.isUpdateNeeded(6, log)) {
                log.println("⏳ База DimRia оновлювалась менше 6 годин тому. Пропускаємо.");
                return;
            }

            log.println("\n=== [DimRia API] ЕТАП 1: Збір ID оголошень ===");
            Set<String> existingIds = DimRiaStorageService.loadExistingIds(log);
            log.printf("📂 Вже відстежується ID: %d%n", existingIds.size());

            Map<String, Announcement> freshAdsMap = new LinkedHashMap<>();

            for (City city : ACTIVE_CITIES) {
                for (CategoryLocation category : ACTIVE_CATEGORIES) {

                    log.printf("%n  🔍 %s → %s [DimRia]%n", city.getLabel(), category.getLabel());
                    try {
                        List<Announcement> pageAds = collectAllPages(city, category);
                        int before = freshAdsMap.size();
                        for (Announcement ad : pageAds) freshAdsMap.putIfAbsent(ad.getId(), ad);

                        log.printf("  📊 Знайдено: %d | Нових у сесії: %d | Дублів сесії: %d%n",
                                pageAds.size(), (freshAdsMap.size() - before), pageAds.size() - (freshAdsMap.size() - before));
                    } catch (org.jsoup.HttpStatusException e) {
                        log.printf("  ⚠️ Пропущено %s/%s — HTTP %d%n", city.getLabel(), category.getLabel(), e.getStatusCode());
                    } catch (Exception e) {
                        log.printf("  ❌ Помилка збору %s/%s: %s%n", city.getLabel(), category.getLabel(), e.getMessage());
                    }
                    Thread.sleep(DELAY_PAGES);
                }
            }

            List<Announcement> newAds = new ArrayList<>();
            for (Announcement ad : freshAdsMap.values()) {
                if (!existingIds.contains(ad.getId())) newAds.add(ad);
            }

            log.printf("%n✅ Нових для завантаження: %d | ⏭ Пропущено: %d%n", newAds.size(), freshAdsMap.size() - newAds.size());
            if (newAds.isEmpty()) {
                log.println("\n🎉 База DimRia актуальна.");
                DimRiaStorageService.saveLastUpdateTime();
                return;
            }

            DimRiaStorageService.appendNewIds(newAds, existingIds.size());

            log.println("\n=== [DimRia HTML] ЕТАП 2: Завантаження деталей НОВИХ оголошень ===");
            int success = 0, failed = 0;
            for (int i = 0; i < newAds.size(); i++) {
                Announcement ad = newAds.get(i);
                log.printf("[%d/%d] %s | %s | ID: %s%n", i + 1, newAds.size(), ad.getCity().getLabel(), ad.getCategory().getLabel(), ad.getId());
                try {
                    Document doc = OlxHtmlParser.getDocument(ad.getUrl());
                    String jsonState = DimRiaHtmlParser.buildDetailJson(doc, ad);

                    DimRiaStorageService.saveAdDetailJson(ad.getId(), jsonState);
                    success++;
                    log.printf("  ✅ dimria_post_%s.json%n", ad.getId());
                } catch (Exception e) {
                    failed++;
                    log.printf("  ❌ Помилка завантаження деталей ID %s: %s%n", ad.getId(), e.getMessage());
                }
                if (i < newAds.size() - 1) Thread.sleep(DELAY_POSTS);
            }

            DimRiaStorageService.saveLastUpdateTime();
            log.printf("%n=== DimRia ГОТОВО === ✅ Збережено нових файлів: %d | ❌ Помилки: %d%n", success, failed);
        } catch (Exception e) {
            log.println("💥 Критична помилка контролера DimRia: " + e.getMessage());
        }
    }

    /**
     * Збирає ID оголошень напряму з внутрішнього JSON API, використовуючи браузерні Cookies
     */
    private static List<Announcement> collectAllPages(City city, CategoryLocation category) throws Exception {
        List<Announcement> results = new ArrayList<>();

        // operation=3 (оренда), operation=1 (продаж)
        String operation = (category == CategoryLocation.SALE) ? "1" : "3";
        String dimRiaCityId = getDimRiaCityId(city);

        // Звертаємося до точного внутрішнього JSON API сайту
        StringBuilder sb = new StringBuilder("https://dom.ria.com/node/api/searchBlocks?category=1&realty_type=2");
        sb.append("&operation=").append(operation)
                .append("&state_id=25")
                .append("&price_cur=1")
                .append("&wo_dupl=1")
                .append("&excludeSold=1")
                .append("&sort=inspected_sort")
                .append("&limit=60") // Беремо відразу 60 об'єктів
                .append("&client=searchV2");

        if (!dimRiaCityId.equals("0")) {
            sb.append("&city_ids=").append(dimRiaCityId);
        }

        String apiUrl = sb.toString();
        log.printf("    [API Request with Cookies] %s%n", apiUrl);

        // Твої живі куки з браузера
        String myCookies = "_clsk=jnc1lq%5E1783601780001%5E3%5E1%5En.clarity.ms%2Fcollect; "
                + "_ga_HJZP5P77GH=GS2.1.s1783596981$o1$g1$t1783600525$j60$l0$h125488171; "
                + "_fbp=fb.1.1783596981980.809981691374725438; gdpr=[2,3]; "
                + "niuid=44b0e631e72f437d6efbd9dafd49bf19; "
                + "lang=uk; nisess=99b037098fe9dc388103f9a761cfd36f; "
                + "PHPSESSID=p1v1qk4b5b69ofv701277ajei1; "
                + "PSP_CHECK=f8a07316ce79d55903e3c760bb5c403db4c07ed78043e04e4c81c3639571251617080931; ui=dfc99cd805afa812";

        // Робимо запит до API, передаючи куки браузера
        String jsonResponse = org.jsoup.Jsoup.connect(apiUrl)
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest") // Показуємо, що це AJAX запит від фронтенду
                .header("Cookie", myCookies)
                .header("Referer", "https://dom.ria.com/uk/")
                .ignoreContentType(true) // Ігноруємо MIME-тип, бо чекаємо JSON текст
                .execute()
                .body();

        // Шукаємо ID оголошень у JSON (тепер тут будуть тільки чисті масиви ID!)
        Pattern p = Pattern.compile("\\b\\d{8,10}\\b");
        Matcher m = p.matcher(jsonResponse);

        while (m.find()) {
            String id = m.group();
            String finalId = id;

            Announcement ad = new Announcement(
                    id, "https://dom.ria.com/uk/realty-" + id + ".html",
                    "Об'єкт DimRia", "Договірна", "", city, category
            );

            if (results.stream().noneMatch(c -> c.getId().equals(finalId))) {
                results.add(ad);
            }
        }

        return results;
    }

    /**
     * Повертає внутрішні ID міст/селів для пошукової системи DimRia.
     */
    private static String getDimRiaCityId(City city) {
        switch (city) {
            case CHERNIVTSI:
                return "25";     // 25 — ID міста Чернівці (і його внутрішніх районів)
            case GODILIV:
                return "24729";  // Внутрішній ID села Годилів
            case KOROVIA:
                return "24734";  // Внутрішній ID села Коровія
            case CHAGOR:
                return "24754";  // Внутрішній ID села Чагор
            default:
                return "0";      // 0 — пошук загалом по всій області (state_id=25)
        }
    }
}


