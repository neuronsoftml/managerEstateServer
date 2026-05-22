package controllers;

import model.dimRia.Announcement;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

public class DimRiaController {
    //8TjEB8XOphWtyXTNzJ4aO0aKoymu4xVbU4Y9Ps6e
    //PB4dhkYRNhmWG1XQjvx0vFBHTEVe58fn3b7RYL6D
    private static final String SEARCH_URL = "https://developers.ria.com/dom/search";
    private static final String INFO_URL = "https://developers.ria.com/dom/info/";
    private static final String API_KEY = "PB4dhkYRNhmWG1XQjvx0vFBHTEVe58fn3b7RYL6D";

    // НАЛАШТУВАННЯ БЕЗПЕКИ API (захист від помилки 429)
    private static final int DELAY_BETWEEN_REQUESTS_MS = 2000; // Пауза 2 секунди між запитами деталей
    private static final int MAX_ITEMS_TO_FETCH = 10;          // Скільки максимум об'єктів парсити за один запуск

    private final ParseAndFetchController parser = new ParseAndFetchController();
    private final HttpClient client = HttpClient.newHttpClient();

    public List<Announcement> fetchAllAnnouncements() {
        List<Announcement> fullList = new ArrayList<>();

        String urlWithParams = String.format(
                "%s?api_key=%s&category=1&realty_type=0&state_id=24&operation_type=1",
                SEARCH_URL, API_KEY
        );

        HttpRequest searchRequest = HttpRequest.newBuilder().uri(URI.create(urlWithParams)).GET().build();

        try {
            System.out.println("Крок 1: Завантаження списку ID з Чернівецької області...");
            HttpResponse<String> searchResponse = client.send(searchRequest, HttpResponse.BodyHandlers.ofString());

            if (searchResponse.statusCode() != 200) {
                System.err.println("Помилка пошуку. Код відповiдi сервера: " + searchResponse.statusCode());
                if (searchResponse.statusCode() == 429) {
                    System.err.println("Ключ API заблоковано на сьогодні через вичерпання добового ліміту.");
                }
                return fullList;
            }

            // Дістаємо чисті ID
            List<Long> foundIds = parser.parseSearchIds(searchResponse.body());
            System.out.println("Всього знайдено " + foundIds.size() + " оголошень на сервері.");

            // ОБМЕЖЕННЯ: беремо лише безпечну кількість для тестування
            List<Long> safeIds = foundIds.stream().limit(MAX_ITEMS_TO_FETCH).toList();
            System.out.println("Для захисту від блокування буде оброблено перші " + safeIds.size() + " об'єктів.");
            System.out.println("Крок 2: Починаємо детальне завантаження...");

            // 2. Ітеруємося по безпечному списку ID
            for (int i = 0; i < safeIds.size(); i++) {
                Long id = safeIds.get(i);

                String detailUrl = String.format("%s%d?api_key=%s", INFO_URL, id, API_KEY);
                HttpRequest infoRequest = HttpRequest.newBuilder().uri(URI.create(detailUrl)).GET().build();

                HttpResponse<String> infoResponse = client.send(infoRequest, HttpResponse.BodyHandlers.ofString());

                if (infoResponse.statusCode() == 200) {
                    Announcement ad = parser.parseSingleAnnouncement(id, infoResponse.body());
                    if (ad != null) {
                        fullList.add(ad);
                        System.out.println(String.format("[%d/%d] Успішно додано: [ID: %d] %s",
                                (i + 1), safeIds.size(), id, ad.getTitle()));
                    }
                } else if (infoResponse.statusCode() == 429) {
                    // Якщо під час циклу ми все ж зловили ліміт, негайно зупиняємося
                    System.err.println("\n[!] Охоу! Сервер повернув код 429 на ID " + id);
                    System.err.println("[!] Ми перевищили ліміт запитів. Рятуємо зібрані дані та завершуємо роботу.");
                    break;
                } else {
                    System.err.println("Не вдалося завантажити деталі для ID " + id + ". Код: " + infoResponse.statusCode());
                }

                // Робимо паузу ТІЛЬКИ якщо це не останній елемент у списку
                if (i < safeIds.size() - 1) {
                    System.out.println("Очікування " + (DELAY_BETWEEN_REQUESTS_MS / 1000.0) + " сек для захисту API...");
                    Thread.sleep(DELAY_BETWEEN_REQUESTS_MS);
                }
            }

        } catch (Exception e) {
            System.err.println("Сталася критична помилка під час виконання: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\nРоботу завершено. Успішно зібрано об'єктів: " + fullList.size());
        return fullList;
    }
}


