package controllers.olx;

import core.tools.olx.OlxHtmlParser;
import core.tools.olx.OlxStorageService;
import model.City;
import model.CategoryLocation;
import model.Announcement;
import org.jsoup.nodes.Document;

import java.io.*;
import java.util.*;

public class OlxController {
    private static final String OLX_BASE = "https://www.olx.ua/uk/";
    private static final int DELAY_PAGES = 0;
    private static final int DELAY_POSTS = 0;

    private static final City[] ACTIVE_CITIES = {
            City.CHERNIVTSI,
            City.GODILIV,
            City.KOROVIA,
            City.CHAGOR
    };

    private static final CategoryLocation[] ACTIVE_CATEGORIES = {
            CategoryLocation.RENT_LONG,
            CategoryLocation.SALE,
            CategoryLocation.RENT_SHORT
    };
    private static PrintStream log;

    /**
     * Запускає цикл збору оголошень з OLX.
     * @param printStream Потік для виведення логів у вікно консолі
     * @return true — якщо парсинг успішно відбувся (були знайдені чи перевірені оголошення)
     */
    public static boolean start(PrintStream printStream) {
        log = printStream;
        try {
            OlxStorageService.initDirectories();
            log.println("📁 Робоча директорія: " + OlxStorageService.getRootAbsolutePath());

            // Перевірку часу ВИЛУЧЕНО звідси. Тепер за це відповідає UpdateBaseController через JSON-статус.

            log.println("\n=== ЕТАП 1: Збір ID оголошень ===");
            Set<String> existingIds = OlxStorageService.loadExistingIds(log);
            log.printf("📂 Вже збережено ID: %d%n", existingIds.size());

            Map<String, Announcement> freshAdsMap = new LinkedHashMap<>();

            for (City city : ACTIVE_CITIES) {
                for (CategoryLocation category : ACTIVE_CATEGORIES) {
                    String url = OLX_BASE + category.getUrlSegment() + "/" + city.getSlug() + "/";
                    log.printf("%n  🔍 %s → %s%n", city.getLabel(), category.getLabel());
                    try {
                        List<Announcement> pageAds = collectAllPages(url, city, category);
                        int before = freshAdsMap.size();
                        for (Announcement ad : pageAds) freshAdsMap.putIfAbsent(ad.getId(), ad);
                        log.printf("  📊 Знайдено: %d | Нових: %d | Дублів: %d%n",
                                pageAds.size(), (freshAdsMap.size() - before), pageAds.size() - (freshAdsMap.size() - before));
                    } catch (org.jsoup.HttpStatusException e) {
                        log.printf("  ⚠️ Пропущено %s/%s — HTTP %d%n", city.getLabel(), category.getLabel(), e.getStatusCode());
                    } catch (Exception e) {
                        log.printf("  ❌ Помилка %s/%s: %s%n", city.getLabel(), category.getLabel(), e.getMessage());
                    }
                    Thread.sleep(DELAY_PAGES);
                }
            }

            List<Announcement> newAds = new ArrayList<>();
            for (Announcement ad : freshAdsMap.values()) {
                if (!existingIds.contains(ad.getId())) newAds.add(ad);
            }

            log.printf("%n✅ Нових (не в базі): %d | ⏭ Пропущено: %d%n", newAds.size(), freshAdsMap.size() - newAds.size());

            // Якщо нових оголошень на сайті немає — повертаємо true (роботу успішно виконано, нових даних немає)
            if (newAds.isEmpty()) {
                log.println("\n🎉 База актуальна на OLX. Нових оголошень немає.");
                return true;
            }

            OlxStorageService.appendNewIds(newAds, existingIds.size());

            log.println("\n=== ЕТАП 2: Завантаження деталей НОВИХ оголошень ===");
            int success = 0, failed = 0;
            for (int i = 0; i < newAds.size(); i++) {
                Announcement ad = newAds.get(i);
                log.printf("[%d/%d] %s | %s | ID: %s%n", i + 1, newAds.size(), ad.getCity().getLabel(), ad.getCategory().getLabel(), ad.getId());
                try {
                    Document doc = OlxHtmlParser.getDocument(ad.getUrl());
                    Announcement fullAd = OlxHtmlParser.fillDetails(ad, doc);
                    OlxStorageService.saveAdDetail(fullAd);
                    success++;
                    log.printf("  ✅ post_%s.json%n", ad.getId());
                } catch (Exception e) {
                    failed++;
                    log.printf("  ❌ Помилка завантаження детальної інформації: %s%n", e.getMessage());
                }
                if (i < newAds.size() - 1) Thread.sleep(DELAY_POSTS);
            }

            log.printf("%n=== ГОТОВО === ✅ Збережено: %d | ❌ Помилки: %d%n", success, failed);
            return true;

        } catch (Exception e) {
            log.println("💥 Критична помилка в OlxController: " + e.getMessage());
            return false;
        }
    }

    private static List<Announcement> collectAllPages(String baseUrl, City city, CategoryLocation category) throws Exception {
        List<Announcement> results = new ArrayList<>();
        int page = 1;
        while (true) {
            String url = (page == 1) ? baseUrl : baseUrl + "?page=" + page;
            log.printf("    [стор. %d] %s%n", page, url);

            Document doc = OlxHtmlParser.getDocument(url);
            List<Announcement> cards = OlxHtmlParser.parsePageCards(doc, city, category);
            if (cards.isEmpty()) break;

            results.addAll(cards);
            if (!OlxHtmlParser.hasNextPage(doc, page)) break;
            page++;
            Thread.sleep(DELAY_PAGES);
        }
        return results;
    }
}