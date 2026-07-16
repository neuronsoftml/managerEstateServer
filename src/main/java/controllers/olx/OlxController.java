package controllers.olx;

import core.tools.parser.olx.OlxHtmlParser;
import core.tools.parser.olx.OlxStorageService;
import model.City;
import model.AnnouncementCategory;
import model.Announcement;
import org.jsoup.nodes.Document;

import java.io.*;
import java.util.*;

/**
 * Контролер процесу парсингу та синхронізації оголошень з OLX.
 * <p>
 * Реалізує двохетапний алгоритм збору даних:
 * <ol>
 * <li><b>Етап 1 (Збір ID):</b> Проходить по всіх активних категоріях та локаціях, збирає картки оголошень,
 * фільтрує дублікати унікальних ID та відсіює вже збережені раніше оголошення.</li>
 * <li><b>Етап 2 (Деталізація):</b> Для кожного знайденого нового оголошення робить індивідуальний запит
 * на сторінку деталей, витягує повний опис/параметри та серіалізує в JSON.</li>
 * </ol>
 * </p>
 */
public class OlxController {

    /** Базовий URL українського сегмента OLX */
    private static final String OLX_BASE = "https://www.olx.ua/uk/";

    /** Затримка між запитами до сторінок списку оголошень (пагінація) в мілісекундах */
    private static final int DELAY_PAGES = 0;

    /** Затримка між запитами до індивідуальних сторінок оголошень у мілісекундах */
    private static final int DELAY_POSTS = 0;

    /** Список населених пунктів (локацій), для яких здійснюється моніторинг нерухомості */
    private static final City[] ACTIVE_CITIES = {
            City.CHERNIVTSI,
            City.GODILIV,
            City.KOROVIA,
            City.CHAGOR
    };

    /** Цільові категорії оголошень нерухомості (оренда, продаж квартир, будинків тощо) */
    private static final AnnouncementCategory[] ACTIVE_CATEGORIES = {
            AnnouncementCategory.RENT_LONG,
            AnnouncementCategory.SALE_APARTMENTS,
            AnnouncementCategory.RENT_SHORT,
            AnnouncementCategory.RENT_HOUSE,
            AnnouncementCategory.RENT_COMMERCIAL,
            AnnouncementCategory.SALE_HOUSE,
            AnnouncementCategory.SALE_COMMERCIAL,
            AnnouncementCategory.SALE_LAND_PARCEL
    };

    /** Потік для виведення логів та поточної діагностики роботи парсера */
    private static PrintStream log;

    /**
     * Запускає повний цикл збору оголошень з OLX.
     * * @param printStream потік для виведення логів у вікно консолі або файл
     * @return true — якщо парсинг успішно завершився (навіть якщо нових оголошень не виявлено),
     * false — у разі виникнення критичного збою чи винятку.
     */
    public static boolean start(PrintStream printStream) {
        log = printStream;
        try {
            // Ініціалізація структури локальних директорій для збереження результатів
            OlxStorageService.initDirectories();
            log.println("📁 Робоча директорія: " + OlxStorageService.getRootAbsolutePath());

            log.println("\n=== ЕТАП 1: Збір ID оголошень ===");
            // Завантаження вже існуючих ID для виключення повторного парсингу деталей
            Set<String> existingIds = OlxStorageService.loadExistingIds(log);
            log.printf("📂 Вже збережено ID: %d%n", existingIds.size());

            // Тимчасова мапа для усунення дублікатів оголошень під час поточного циклу збору
            Map<String, Announcement> freshAdsMap = new LinkedHashMap<>();

            // Послідовний обхід матриці Локації x Категорії
            for (City city : ACTIVE_CITIES) {
                for (AnnouncementCategory category : ACTIVE_CATEGORIES) {
                    String url = OLX_BASE + category.getUrlSegment() + "/" + city.getSlug() + "/";
                    log.printf("%n  🔍 %s → %s%n", city.getLabel(), category.getLabel());
                    try {
                        // Парсинг сторінок категорії для поточної локації
                        List<Announcement> pageAds = collectAllPages(url, city, category);
                        int before = freshAdsMap.size();

                        // Додаємо оголошення до мапи, ігноруючи повтори
                        for (Announcement ad : pageAds) freshAdsMap.putIfAbsent(ad.getId(), ad);

                        log.printf("  📊 Знайдено: %d | Нових: %d | Дублів: %d%n",
                                pageAds.size(),
                                (freshAdsMap.size() - before),
                                pageAds.size() - (freshAdsMap.size() - before));

                    } catch (org.jsoup.HttpStatusException e) {
                        // Окремий перехоплення 404/500 помилок HTTP (наприклад, якщо категорія порожня для міста)
                        log.printf("  ⚠️ Пропущено %s/%s — HTTP %d%n", city.getLabel(), category.getLabel(), e.getStatusCode());
                    } catch (Exception e) {
                        log.printf("  ❌ Помилка %s/%s: %s%n", city.getLabel(), category.getLabel(), e.getMessage());
                    }
                    if (DELAY_PAGES > 0) Thread.sleep(DELAY_PAGES);
                }
            }

            // Відбір тільки тих оголошень, яких ще немає у нашій базі даних
            List<Announcement> newAds = new ArrayList<>();
            for (Announcement ad : freshAdsMap.values()) {
                if (!existingIds.contains(ad.getId())) newAds.add(ad);
            }

            log.printf("%n✅ Нових (не в базі): %d | ⏭ Пропущено: %d%n", newAds.size(), freshAdsMap.size() - newAds.size());

            // Якщо нових оголошень на сайті немає — завершуємо роботу
            if (newAds.isEmpty()) {
                log.println("\n🎉 База актуальна на OLX. Нових оголошень немає.");
                return true;
            }

            // Дописуємо нові ID у файл-індекс бази даних
            OlxStorageService.appendNewIds(newAds, existingIds.size());

            log.println("\n=== ЕТАП 2: Завантаження деталей НОВИХ оголошень ===");
            int success = 0, failed = 0;

            // Проходимо по кожному новому оголошенню для збору детальної інформації
            for (int i = 0; i < newAds.size(); i++) {
                Announcement ad = newAds.get(i);
                log.printf("[%d/%d] %s | %s | ID: %s%n", i + 1, newAds.size(), ad.getCity().getLabel(), ad.getCategory().getLabel(), ad.getId());
                try {
                    // Завантаження HTML сторінки оголошення
                    Document doc = OlxHtmlParser.getDocument(ad.getUrl());
                    // Витяг параметрів (опис, площа, ціна, фото тощо)
                    Announcement fullAd = OlxHtmlParser.fillDetails(ad, doc);
                    // Збереження деталізованого об'єкта у локальне файлове сховище (JSON)
                    OlxStorageService.saveAdDetail(fullAd);
                    success++;
                    log.printf("  ✅ post_%s.json%n", ad.getId());
                } catch (Exception e) {
                    failed++;
                    log.printf("  ❌ Помилка завантаження детальної інформації: %s%n", e.getMessage());
                }

                // Інтервальна затримка для мінімізації ризику блокування (Rate Limiting)
                if (i < newAds.size() - 1 && DELAY_POSTS > 0) {
                    Thread.sleep(DELAY_POSTS);
                }
            }

            log.printf("%n=== ГОТОВО === ✅ Збережено: %d | ❌ Помилки: %d%n", success, failed);
            return true;

        } catch (Exception e) {
            log.println("💥 Критична помилка в OlxController: " + e.getMessage());
            return false;
        }
    }

    /**
     * Рекурсивно або циклічно проходить по сторінках пагінації вибраного URL,
     * збираючи загальну інформацію про оголошення зі списку (картки пошуку).
     *
     * @param baseUrl  початкова адреса пошукового запиту категорії на OLX
     * @param city     об'єкт поточної локації
     * @param category об'єкт поточної категорії
     * @return список зведених оголошень, знайдених на всіх сторінках
     * @throws Exception при помилках мережі або збоях парсингу HTML
     */
    private static List<Announcement> collectAllPages(String baseUrl, City city, AnnouncementCategory category) throws Exception {
        List<Announcement> results = new ArrayList<>();
        int page = 1;
        while (true) {
            // Формуємо URL для поточної сторінки пагінації
            String url = (page == 1) ? baseUrl : baseUrl + "?page=" + page;
            log.printf("    [стор. %d] %s%n", page, url);

            Document doc = OlxHtmlParser.getDocument(url);
            // Отримуємо список первинних карток на поточній сторінці
            List<Announcement> cards = OlxHtmlParser.parsePageCards(doc, city, category);
            if (cards.isEmpty()) break; // Зупиняємося, якщо сторінка не містить оголошень

            results.addAll(cards);

            // Перевіряємо за допомогою парсера наявність кнопки "Наступна сторінка"
            if (!OlxHtmlParser.hasNextPage(doc, page)) break;

            page++;
            if (DELAY_PAGES > 0) Thread.sleep(DELAY_PAGES);
        }
        return results;
    }
}