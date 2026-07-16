package core.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Сервіс для роботи з курсами валют ПриватБанку та конвертації грошових сум.
 * <p>
 * Клас автоматично отримує актуальний готівковий курс купівлі/продажу валют у відділеннях
 * через публічне безкоштовне JSON API ПриватБанку.
 * </p>
 * <p>
 * <b>Оптимізація та стабільність:</b>
 * <ul>
 * <li><b>Кешування курсів (Time-To-Live):</b> запити до API виконуються не частіше ніж раз на 1 годину (3 600 000 мс).</li>
 * <li><b>Захисний фолбек (Fallback):</b> у разі відсутності інтернету або збою API сервіс підставляє базовий захардкоджений курс, щоб уникнути падіння розрахунків.</li>
 * <li><b>Безпечний парсинг:</b> вилучення значень з JSON реалізовано через регулярні вирази, що усуває залежність від сторонніх JSON-парсерів.</li>
 * </ul>
 * </p>
 * * @author Mykola
 */
public class PrivatBankRateService {

    /**
     * Свіже безкоштовне API ПриватБанку (Готівковий курс у відділеннях).
     */
    private static final String API_URL = "https://api.privatbank.ua/p24api/pubinfo?json&exchange&coursid=5";

    /** Кешоване значення курсу долара США (USD) */
    private static BigDecimal usdRate = BigDecimal.ZERO;

    /** Кешоване значення курсу євро (EUR) */
    private static BigDecimal eurRate = BigDecimal.ZERO;

    /** Час останнього успішного оновлення курсів у мілісекундах (системний Unix-time) */
    private static long lastFetchedTime = 0;

    /**
     * Оновлює курси валют, якщо вони ще не були ініціалізовані або якщо минуло більше 1 години (3 600 000 мс)
     * з моменту останнього успішного запиту.
     */
    public static void updateRatesIfNeeded() {
        long currentTime = System.currentTimeMillis();
        // Якщо курс дорівнює нулю (перший запуск) АБО різниця часу перевищує 1 годину
        if (usdRate.equals(BigDecimal.ZERO) || (currentTime - lastFetchedTime > 3_600_000)) {
            fetchRates();
        }
    }

    /**
     * Виконує HTTP-запит GET до API ПриватБанку, зчитує отриманий JSON та оновлює кеш курсів.
     * <p>
     * У разі помилки підключення або таймауту (5 секунд) метод перехоплює виняток і, якщо курси ще
     * не були завантажені раніше, встановлює безпечні середньоринкові значення за замовчуванням.
     * </p>
     */
    private static void fetchRates() {
        try {
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000); // Таймаут з'єднання — 5 сек
            conn.setReadTimeout(5000);    // Таймаут читання — 5 сек

            if (conn.getResponseCode() == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder json = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        json.append(line);
                    }

                    // Парсимо JSON регулярними виразами, шукаючи продаж (sale) для USD та EUR
                    usdRate = extractSaleRate(json.toString(), "USD");
                    eurRate = extractSaleRate(json.toString(), "EUR");
                    lastFetchedTime = System.currentTimeMillis();

                    System.out.printf("📊 Курси ПриватБанку оновлено: USD: %s | EUR: %s%n", usdRate, eurRate);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Не вдалося отримати курс валют від ПриватБанку: " + e.getMessage());
            // Дефолтні фолбек-курси на випадок, якщо ліг інтернет/API або змінилася його структура
            if (usdRate.equals(BigDecimal.ZERO)) {
                usdRate = new BigDecimal("41.50");
            }
            if (eurRate.equals(BigDecimal.ZERO)) {
                eurRate = new BigDecimal("44.80");
            }
        }
    }

    /**
     * Витягує курс продажу (sale) для конкретної валюти з сирого JSON-рядка API.
     * <p>
     * Регулярний вираз націлений на структуру об'єкта типу:
     * {@code {"ccy":"USD","base_ccy":"UAH","buy":"41.00","sale":"41.60"}}
     * </p>
     *
     * @param json     повний текст відповіді від API
     * @param currency системне позначення валюти (наприклад, "USD", "EUR")
     * @return курс продажу у форматі {@link BigDecimal}, або {@link BigDecimal#ZERO}, якщо валюту не знайдено
     */
    private static BigDecimal extractSaleRate(String json, String currency) {
        Pattern p = Pattern.compile("\\{\"ccy\":\"" + currency + "\",\"base_ccy\":\"UAH\",\"buy\":\"[^\"]+\",\"sale\":\"([^\"]+)\"\\}");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return new BigDecimal(m.group(1));
        }
        return BigDecimal.ZERO;
    }

    /**
     * Конвертує грошову суму з гривні (UAH) у долари США (USD) за поточним курсом банку.
     * <p>
     * Перед обчисленням метод автоматично перевіряє необхідність актуалізації курсів.
     * Результат округляється до цілого долара за математичними правилами (HALF_UP).
     * </p>
     *
     * @param uahAmount сума в гривнях
     * @return сума в доларах США, округлена до цілого числа, або {@code BigDecimal.ZERO} у разі некоректних даних
     */
    public static BigDecimal convertUahToUsd(BigDecimal uahAmount) {
        updateRatesIfNeeded();
        if (uahAmount == null || usdRate.equals(BigDecimal.ZERO)) {
            return BigDecimal.ZERO;
        }
        // Ділимо на курс долара та округляємо до цілої частини (0 знаків після коми)
        return uahAmount.divide(usdRate, 0, RoundingMode.HALF_UP);
    }

    /**
     * Конвертує грошову суму з гривні (UAH) у євро (EUR) за поточним курсом банку.
     * <p>
     * Перед обчисленням метод автоматично перевіряє необхідність актуалізації курсів.
     * Результат округляється до цілого євро за математичними правилами (HALF_UP).
     * </p>
     *
     * @param uahAmount сума в гривнях
     * @return сума в євро, округлена до цілого числа, або {@code BigDecimal.ZERO} у разі некоректних даних
     */
    public static BigDecimal convertUahToEur(BigDecimal uahAmount) {
        updateRatesIfNeeded();
        if (uahAmount == null || eurRate.equals(BigDecimal.ZERO)) {
            return BigDecimal.ZERO;
        }
        // Ділимо на курс євро та округляємо до цілої частини (0 знаків після коми)
        return uahAmount.divide(eurRate, 0, RoundingMode.HALF_UP);
    }

    /**
     * Конвертує грошову суму з євро (EUR) у долари США (USD).
     * <p>
     * Схема: EUR -> UAH (множення) -> USD (ділення).
     * </p>
     *
     * @param eurAmount сума в євро
     * @return сума в доларах США, округлена до цілого числа
     */
    public static BigDecimal convertEurToUsd(BigDecimal eurAmount) {
        updateRatesIfNeeded();
        if (eurAmount == null || eurRate.equals(BigDecimal.ZERO) || usdRate.equals(BigDecimal.ZERO)) {
            return BigDecimal.ZERO;
        }
        // 1. Конвертуємо EUR в UAH: множимо на eurRate
        BigDecimal uahAmount = eurAmount.multiply(eurRate);
        // 2. Конвертуємо UAH в USD: ділимо на usdRate
        return uahAmount.divide(usdRate, 0, RoundingMode.HALF_UP);
    }

    /**
     * Конвертує грошову суму з доларів США (USD) у євро (EUR).
     * <p>
     * Схема: USD -> UAH (множення) -> EUR (ділення).
     * </p>
     *
     * @param usdAmount сума в доларах США
     * @return сума в євро, округлена до цілого числа
     */
    public static BigDecimal convertUsdToEur(BigDecimal usdAmount) {
        updateRatesIfNeeded();
        if (usdAmount == null || usdRate.equals(BigDecimal.ZERO) || eurRate.equals(BigDecimal.ZERO)) {
            return BigDecimal.ZERO;
        }
        // 1. Конвертуємо USD в UAH: множимо на usdRate
        BigDecimal uahAmount = usdAmount.multiply(usdRate);
        // 2. Конвертуємо UAH в EUR: ділимо на eurRate
        return uahAmount.divide(eurRate, 0, RoundingMode.HALF_UP);
    }

    /**
     * Конвертує грошову суму з доларів США (USD) у гривню (UAH).
     * <p>
     * Схема: USD -> UAH (множення на курс продажу).
     * </p>
     *
     * @param usdAmount сума в доларах США
     * @return сума в гривнях
     */
    public static BigDecimal convertUsdToUah(BigDecimal usdAmount) {
        updateRatesIfNeeded();
        if (usdAmount == null || usdRate.equals(BigDecimal.ZERO)) {
            return BigDecimal.ZERO;
        }
        // Множимо суму на курс продажу
        return usdAmount.multiply(usdRate);
    }

    /**
     * Конвертує грошову суму з євро (EUR) у гривню (UAH).
     * <p>
     * Схема: EUR -> UAH (множення на курс продажу).
     * </p>
     *
     * @param eurAmount сума в євро
     * @return сума в гривнях
     */
    public static BigDecimal convertEurToUah(BigDecimal eurAmount) {
        updateRatesIfNeeded();
        if (eurAmount == null || eurRate.equals(BigDecimal.ZERO)) {
            return BigDecimal.ZERO;
        }
        // Множимо суму на курс продажу
        return eurAmount.multiply(eurRate);
    }
}
