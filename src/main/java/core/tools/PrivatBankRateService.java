package core.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PrivatBankRateService {

    // Свіже безкоштовне API ПриватБанку (Готівковий курс у відділеннях)
    private static final String API_URL = "https://api.privatbank.ua/p24api/pubinfo?json&exchange&coursid=5";

    private static BigDecimal usdRate = BigDecimal.ZERO;
    private static BigDecimal eurRate = BigDecimal.ZERO;
    private static long lastFetchedTime = 0;

    /**
     * Оновлює курси валют, якщо минуло більше 1 години з моменту останнього запиту
     */
    public static void updateRatesIfNeeded() {
        long currentTime = System.currentTimeMillis();
        // Якщо курс ще не брали АБО минула 1 година (3600000 мс) — оновлюємо
        if (usdRate.equals(BigDecimal.ZERO) || (currentTime - lastFetchedTime > 3_600_000)) {
            fetchRates();
        }
    }

    private static void fetchRates() {
        try {
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder json = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) json.append(line);

                    // Парсимо JSON регулярками (шукаємо блоки з USD та EUR)
                    usdRate = extractSaleRate(json.toString(), "USD");
                    eurRate = extractSaleRate(json.toString(), "EUR");
                    lastFetchedTime = System.currentTimeMillis();

                    System.out.printf("📊 Курси ПриватБанку оновлено: USD: %s | EUR: %s%n", usdRate, eurRate);
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Не вдалося отримати курс валют від ПриватБанку: " + e.getMessage());
            // Дефолтні фолбек-курси на випадок, якщо ліг інтернет/API
            if (usdRate.equals(BigDecimal.ZERO)) usdRate = new BigDecimal("41.50");
            if (eurRate.equals(BigDecimal.ZERO)) eurRate = new BigDecimal("44.80");
        }
    }

    private static BigDecimal extractSaleRate(String json, String currency) {
        // Шукаємо структуру типу {"ccy":"USD","base_ccy":"UAH","buy":"41.00","sale":"41.60"}
        Pattern p = Pattern.compile("\\{\"ccy\":\"" + currency + "\",\"base_ccy\":\"UAH\",\"buy\":\"[^\"]+\",\"sale\":\"([^\"]+)\"\\}");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return new BigDecimal(m.group(1));
        }
        return BigDecimal.ZERO;
    }

    public static BigDecimal convertUahToUsd(BigDecimal uahAmount) {
        updateRatesIfNeeded();
        if (uahAmount == null || usdRate.equals(BigDecimal.ZERO)) return BigDecimal.ZERO;
        return uahAmount.divide(usdRate, 0, RoundingMode.HALF_UP); // Округляємо до цілого долара
    }

    public static BigDecimal convertUahToEur(BigDecimal uahAmount) {
        updateRatesIfNeeded();
        if (uahAmount == null || eurRate.equals(BigDecimal.ZERO)) return BigDecimal.ZERO;
        return uahAmount.divide(eurRate, 0, RoundingMode.HALF_UP); // Округляємо до цілого євро
    }
}
