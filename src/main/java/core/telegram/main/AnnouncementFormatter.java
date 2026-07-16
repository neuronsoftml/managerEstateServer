package core.telegram.main;

import core.tools.PrivatBankRateService;
import core.tools.parser.olx.AnnouncementParamParser;
import model.Announcement;
import model.AnnouncementCategory;
import model.Currency;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.text.DecimalFormat;

/**
 * Утилітний клас для форматування об'єктів оголошень нерухомості в HTML-довідки.
 * <p>
 * Клас генерує структуровані текстові повідомлення, готові для надсилання в Telegram за допомогою
 * {@nlink parse_mode=HTML}. Містить окремі шаблони для довгострокової оренди, подобової оренди та продажу.
 * </p>
 * <p>
 * Усі методи автоматично здійснюють перерахунок базової ціни в гривні в еквіваленти USD та EUR
 * на основі актуальних даних PrivatBankRateService, а також забезпечують безпечне кодування текстових даних
 * для запобігання збоїв парсингу на стороні Telegram API.
 * </p>
 * * @author Mykola
 */
public class AnnouncementFormatter {

    /** Форматувальник числових значень для красивого відображення цін із розділювачами тисяч */
    private static final DecimalFormat df = new DecimalFormat("#,###.##");

    /**
     * Формує рядок технічних параметрів залежно від категорії оголошення:
     * - Квартира (RENT_LONG/RENT_SHORT/SALE_APARTMENTS): кімнати | площа м² | поверх
     * - Будинок (RENT_HOUSE/SALE_HOUSE): площа будинку м² | поверховість | (+ ділянка в сотках, якщо вказана)
     * - Комерція (RENT_COMMERCIAL/SALE_COMMERCIAL): площа м² | поверх (без кімнат — це не житло)
     * - Земля (SALE_LAND_PARCEL): лише площа ділянки в сотках — кімнат/поверхів у землі не буває
     */
    private static String buildParamsLine(Announcement ad) {
        AnnouncementCategory cat = ad.getCategory();
        boolean isLand       = cat == AnnouncementCategory.SALE_LAND_PARCEL;
        boolean isHouse      = cat == AnnouncementCategory.SALE_HOUSE || cat == AnnouncementCategory.RENT_HOUSE;
        boolean isCommercial = cat == AnnouncementCategory.SALE_COMMERCIAL || cat == AnnouncementCategory.RENT_COMMERCIAL;

        double landSotka = AnnouncementParamParser.getLandAreaSotka(ad.getParams());

        if (isLand) {
            return "🌳 <b>Площа ділянки:</b> " + (landSotka > 0 ? df.format(landSotka) + " сот." : "-");
        }

        StringBuilder sb = new StringBuilder("📊 <b>Параметри:</b> ");
        if (!isCommercial) {
            // Кімнати мають сенс лише для житла (квартира/будинок), не для комерції
            sb.append(ad.getRoomsCount() > 0 ? ad.getRoomsCount() + " кімн." : "-").append(" | ");
        }
        sb.append(ad.getTotalArea() > 0 ? ad.getTotalArea() + " м²" : "-").append(" | ");
        sb.append(ad.getFloor() > 0 ? ad.getFloor() + " пов." : "-");

        if (isHouse && landSotka > 0) {
            // У будинку площа будинку (getTotalArea) і площа ділянки — це РІЗНІ числа, показуємо обидва
            sb.append(" | 🌳 ділянка: ").append(df.format(landSotka)).append(" сот.");
        }

        return sb.toString();
    }

    /**
     * Формує текстове HTML-повідомлення для оголошень довготривалої оренди.
     * Включає детальний блок параметрів житла (кімнатність, площа, поверх).
     *
     * @param ad об'єкт оголошення {@link Announcement}
     * @return відформатований рядок з HTML-розміткою
     */
    public static String toHtmlLongTermLease(Announcement ad) {
        StringBuilder sb = new StringBuilder();
        sb.append("🆕 <b>Оренда (Довготривала)!</b>\n\n");
        sb.append("📌 <b>Заголовок:</b> ").append(escapeHtml(ad.getTitle())).append("\n");

        // Форматування фінансового блоку
        if (ad.getPriceValue() != null) {
            appendPriceInfo(sb,ad);
        } else {
            sb.append("💰 <b>Ціна:</b> Договірна\n");
        }

        // Перевірка на null локації та безпечне екранування спеціальних символів HTML
        sb.append("📍 <b>Локація:</b> ").append(ad.getLocation() != null ? escapeHtml(ad.getLocation()) : ad.getLocation()).append("\n");
        sb.append("📅 <b>Дата:</b> ").append(ad.getDatePublished() != null ? ad.getDatePublished() : "-").append("\n");

        // Технічні параметри — категорійно-залежні (див. buildParamsLine)
        sb.append(buildParamsLine(ad)).append("\n");

        // Обрізання тексту опису до 200 символів, щоб пост не займав увесь екран в каналі
        sb.append("📝 <b>Опис:</b> ").append(escapeHtml(truncateDescription(ad.getDescription(), 200))).append("\n\n");
        sb.append("<a href=\"").append(ad.getUrl()).append("\">🔗 Відкрити на OLX</a>\n");

        return sb.toString();
    }

    /**
     * Формує текстове HTML-повідомлення для оголошень подобової оренди.
     * Спрощений шаблон, орієнтований на швидкий перегляд ціни за добу та локації.
     *
     * @param ad об'єкт оголошення {@link Announcement}
     * @return відформатований рядок з HTML-розміткою
     */
    public static String toHtmlDailyRental(Announcement ad) {
        StringBuilder sb = new StringBuilder();
        sb.append("🆕 <b>Оренда (подобова)!</b>\n\n");
        sb.append("📌 <b>Заголовок:</b> ").append(escapeHtml(ad.getTitle())).append("\n");

        // Форматування фінансового блоку
        if (ad.getPriceValue() != null) {
            appendPriceInfo(sb,ad);
        } else {
            sb.append("💰 <b>Ціна:</b> Договірна\n");
        }

        sb.append("📍 <b>Локація:</b> ").append(ad.getLocation() != null ? escapeHtml(ad.getLocation()) : ad.getLocation()).append("\n");
        sb.append("📅 <b>Дата:</b> ").append(ad.getDatePublished() != null ? ad.getDatePublished() : "-").append("\n");

        sb.append("📝 <b>Опис:</b> ").append(escapeHtml(truncateDescription(ad.getDescription(), 200))).append("\n\n");
        sb.append("<a href=\"").append(ad.getUrl()).append("\">🔗 Відкрити на OLX</a>\n");

        return sb.toString();
    }

    /**
     * Формує текстове HTML-повідомлення для оголошень про продаж нерухомості.
     *
     * @param ad об'єкт оголошення {@link Announcement}
     * @return відформатований рядок з HTML-розміткою
     */
    public static String toHtmlForSale(Announcement ad) {
        StringBuilder sb = new StringBuilder();
        sb.append("🆕 <b>Продаж нерухомості</b>\n\n");
        sb.append("📌 <b>Заголовок:</b> ").append(escapeHtml(ad.getTitle())).append("\n");

        // Форматування фінансового блоку
        if (ad.getPriceValue() != null) {
            appendPriceInfo(sb,ad);
        } else {
            sb.append("💰 <b>Ціна:</b> Договірна\n");
        }

        sb.append("📍 <b>Локація:</b> ").append(ad.getLocation() != null ? escapeHtml(ad.getLocation()) : ad.getLocation()).append("\n");
        sb.append("📅 <b>Дата:</b> ").append(ad.getDatePublished() != null ? ad.getDatePublished() : "-").append("\n");

        // Технічні параметри — категорійно-залежні (кімнати лише для житла, площа ділянки для землі тощо)
        sb.append(buildParamsLine(ad)).append("\n");

        sb.append("📝 <b>Опис:</b> ").append(escapeHtml(truncateDescription(ad.getDescription(), 200))).append("\n\n");
        sb.append("<a href=\"").append(ad.getUrl()).append("\">🔗 Відкрити на OLX</a>\n");

        return sb.toString();
    }

    /**
     * Замінює службові символи HTML на їхні безпечні сутності (entities).
     * Запобігає ламанню структури повідомлення, якщо в тексті оголошення з OLX зустрічаються знаки амперсанда або дужок.
     *
     * @param text вихідний рядок
     * @return безпечний для Telegram HTML-текст
     */
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Скорочує довжину тексту до встановленої межі.
     * Додає символ закінчення "...", якщо рядок перевищив ліміт.
     *
     * @param desc      оригінальний опис об'єкта
     * @param maxLength ліміт символів
     * @return скорочений текст, або повідомлення за замовчуванням
     */
    private static String truncateDescription(String desc, int maxLength) {
        if (desc == null || desc.isEmpty()) return "Немає опису";
        if (desc.length() <= maxLength) return desc;
        return desc.substring(0, maxLength) + "...";
    }

    /**
     * Форматує та додає інформацію про ціну та її еквіваленти в інші валюти до повідомлення.
     * * @param sb StringBuilder повідомлення
     * @param ad Оголошення
     */
    private static void appendPriceInfo(StringBuilder sb, Announcement ad){
        BigDecimal price = ad.getPriceValue();
        String currency = ad.getPriceCurrency();
        DecimalFormat df = new DecimalFormat("#,###");

        sb.append("💰 <b>Ціна:</b> ").append(df.format(price) +" ").append(ad.getPriceCurrency()).append("\n");

        // Використовуємо наш покращений PrivatBankRateService
        // Конвертуємо все через UAH для максимальної точності
        if (currency.equals(Currency.USD.getLabel())) {
            BigDecimal uah = PrivatBankRateService.convertUsdToUah(price);
            BigDecimal eur = PrivatBankRateService.convertUsdToEur(price);
            sb.append("💵 <b>Еквівалент: \n </b> EUR ").append(df.format(eur)).append(" || UAH ").append(df.format(uah)).append("\n");

        } else if (currency.equals(Currency.EUR.getLabel())) {
            BigDecimal uah = PrivatBankRateService.convertEurToUah(price);
            BigDecimal usd = PrivatBankRateService.convertEurToUsd(price);
            sb.append("💵 <b>Еквівалент: \n </b> USD ").append(df.format(usd)).append(" || UAH ").append(df.format(uah)).append("\n");

        } else if (currency.equals(Currency.UAH.getLabel())) {
            BigDecimal usd = PrivatBankRateService.convertUahToUsd(price);
            BigDecimal eur = PrivatBankRateService.convertUahToEur(price);
            sb.append("💵 <b>Еквівалент: \n  </b> USD ").append(df.format(usd)).append(" || EUR ").append(df.format(eur)).append("\n");
        }
    }
}

