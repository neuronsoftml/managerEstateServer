package controllers.olx;

import core.tools.olx.OlxHtmlParser;
import core.tools.olx.OlxStorageService;
import model.City;
import model.ProjectFolder;
import model.CategoryLocation;
import model.olx.Announcement;
import org.jsoup.nodes.Document;

import java.io.*;
import java.util.*;

/**
 * OLX парсер з підтримкою:
 *  - Динамічних категорій пошуку (OlxSearchCategory)
 *  - Динамічного списку міст (OlxCity)
 *  - Глобальної дедуплікації між усіма комбінаціями місто×категорія
 *  - Інкрементального оновлення (пропуск вже збережених ID)
 */
public class OlxController {
    private static final String OLX_BASE = "https://www.olx.ua/uk/nedvizhimost/kvartiry/";
    private static final int DELAY_PAGES = 2_000;
    private static final int DELAY_POSTS = 1_500;

    private static final City[] ACTIVE_CITIES = { City.CHERNIVTSI, City.GODILIV, City.SADGORA };
    private static final CategoryLocation[] ACTIVE_CATEGORIES = { CategoryLocation.RENT_LONG, CategoryLocation.SALE };
    private static PrintStream log;

    public static void start(PrintStream printStream) {
        log = printStream;
        try {
            OlxStorageService.initDirectories();
            log.println("📁 Робоча директорія: " + OlxStorageService.getRootAbsolutePath());

            if (!OlxStorageService.isUpdateNeeded(6, log)) {
                log.println("⏳ База оновлювалась менше 6 годин тому. Пропускаємо.");
                return;
            }

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
            if (newAds.isEmpty()) { log.println("\n🎉 База актуальна."); OlxStorageService.saveLastUpdateTime(); return; }

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

            OlxStorageService.saveLastUpdateTime();
            log.printf("%n=== ГОТОВО === ✅ Збережено: %d | ❌ Помилки: %d%n", success, failed);
        } catch (Exception e) {
            log.println("💥 Критична помилка: " + e.getMessage());
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