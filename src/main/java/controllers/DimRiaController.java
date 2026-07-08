package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.ProjectFolder;
import model.dimRia.Announcement;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public class DimRiaController {

    //8TjEB8XOphWtyXTNzJ4aO0aKoymu4xVbU4Y9Ps6e
    //PB4dhkYRNhmWG1XQjvx0vFBHTEVe58fn3b7RYL6D

    private final String SEARCH_URL = "https://developers.ria.com/dom/search";
    private final String INFO_URL = "https://developers.ria.com/dom/info/";
    private static final String API_KEY = "8TjEB8XOphWtyXTNzJ4aO0aKoymu4xVbU4Y9Ps6e";
    private static final int DELAY_MS = 3000;
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Ваш пул безкоштовних ключів (сюди пропишіть усі 30 штук через кому)
    private static final String[] API_KEYS = {
            "PB4dhkYRNhmWG1XQjvx0vFBHTEVe58fn3b7RYL6D",
            "8TjEB8XOphWtyXTNzJ4aO0aKoymu4xVbU4Y9Ps6e",
            "ki3TyF6aMw5qyFkPzPGarJsoKlpt9bdHafbnJ4wW",
            // ... додайте решту ключів сюди
    };

    // Індекс ключа, який використовується в даний момент
    private static int currentKeyIndex = 0;

    // Метод для швидкого отримання поточного ключа
    private static String getActiveKey() {
        return API_KEYS[currentKeyIndex];
    }

    // Метод для перемикання на наступний ключ, якщо зловили 429
    private static void switchToNextKey() {
        currentKeyIndex = (currentKeyIndex + 1) % API_KEYS.length;
        System.out.println("!!! [Система Ротації] Ключ вичерпано. Перемикаємося на ключ №" + (currentKeyIndex + 1));
    }

    /**
     * ЕТАП 1: Посторінкове викачування списків ID (Продаж та Оренда)
     */
    public static void fetchAllChernivtsiApartments() {
        // Передаємо назву папки з Enum
        String targetFolder = ProjectFolder.ID_LISTS.getName();

        System.out.println("=== [DimRiaController] Запуск парсингу списків: ПРОДАЖ ===");
        downloadPagesByOperation(1, "prodazh", targetFolder);

        System.out.println("\n=== [DimRiaController] Запуск парсингу списків: ОРЕНДА ===");
        downloadPagesByOperation(3, "orenda", targetFolder);
    }

    /**
     * ЕТАП 2: Поштучне завантаження деталей з гарантованою паузою.
     * Зчитує раніше завантажені ID з папки "apartament_id" та завантажує детальну інформацію
     * про кожну квартиру в папку "apartament_details".
     */
    public static void fetchDetailsForCollectedIds() {
        System.out.println("\n=== [DimRiaController] Запуск Етапу 2 ===");

        // Використовуємо Енум для папки призначення ("apartment_details")
        Path outputFolder = ProjectFolder.DETAILS.getPath();
        String folderName = ProjectFolder.DETAILS.getName();

        try {
            if (!Files.exists(outputFolder)) {
                Files.createDirectory(outputFolder);
            }
        } catch (IOException e) {
            System.err.println("Не вдалося створити папку: " + e.getMessage());
            return;
        }

        List<Long> allIds = new ArrayList<>();
        // Використовуємо Енум для вхідної папки ("apartment_id")
        Path inputFolder = ProjectFolder.ID_LISTS.getPath();

        if (!Files.exists(inputFolder)) {
            System.err.println("Помилка: Папка [" + inputFolder + "] не існує.");
            return;
        }

        try (Stream<Path> walk = Files.walk(inputFolder)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("перший 100 запит_") && p.getFileName().toString().endsWith(".json"))
                    .forEach(file -> allIds.addAll(ParseAndFetchController.extractIdsFromJson(file)));
        } catch (IOException e) {
            System.err.println("Помилка читання списків ID: " + e.getMessage());
            return;
        }

        System.out.println("Всього знайдено унікальних ID для обробки: " + allIds.size());

        // Проходимо по кожному отриманому ID та робимо запити до API
        for (int i = 0; i < allIds.size(); i++) {
            Long id = allIds.get(i);
            String targetFileName = folderName + "/apartment_id_" + id + ".json";
            Path targetFilePath = Paths.get(targetFileName);

            // Якщо файл цієї квартири вже викачаний раніше — пропускаємо
            if (Files.exists(targetFilePath)) {
                System.out.println((i + 1) + "/" + allIds.size() + ") Квартира ID " + id + " вже є локально, пропускаємо.");
                continue;
            }

            // Динамічно підставляємо АКТИВНИЙ ключ через getActiveKey()
            String url = "https://developers.ria.com/dom/info/" + id + "?api_key=" + getActiveKey();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();

            try {
                System.out.println((i + 1) + "/" + allIds.size() + ") Запит деталей для ID " + id + " за допомогою ключа №" + (currentKeyIndex + 1) + "...");
                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    Files.writeString(targetFilePath, response.body());
                    System.out.println("-> Успішно збережено: " + targetFilePath.getFileName());

                    // Пауза 200 мілісекунд (0.2 секунди) для швидкого викачування з пулом ключів
                    Thread.sleep(200);

                } else if (response.statusCode() == 429) {
                    System.err.println("!!! Ключ №" + (currentKeyIndex + 1) + " зловив ліміт 429!");

                    // Змінюємо ключ на наступний
                    switchToNextKey();

                    // Повертаємо індекс назад, щоб повторити запит цього ж ID з новим ключем
                    i--;

                    // Пауза для стабілізації перед новим запитом
                    Thread.sleep(1000);

                } else {
                    System.err.println("Помилка сервера для ID " + id + ". Код відповіді: " + response.statusCode());
                }

            } catch (IOException e) {
                System.err.println("Помилка мережі для ID " + id + ": " + e.getMessage());
            } catch (InterruptedException e) {
                System.err.println("Процес перервано.");
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.out.println("\n[DimRiaController] Усі квартири успішно скачані у папку '" + folderName + "'!");
    }

    /**
     * Внутрішній метод першого етапу з підтримкою автоматичної ротації ключів.
     * Тепер приймає параметр targetFolder для збереження файлів у конкретну директорію.
     * * @param operationType тип операції (1 - продаж, 3 - оренда)
     * @param prefix суфікс для імені файлу ("prodazh" або "orenda")
     * @param targetFolder назва папки в корені проєкту (наприклад, "apartment_id")
     */
    private static void downloadPagesByOperation(int operationType, String prefix, String targetFolder) {
        int page = 0;
        boolean hasMoreData = true;

        while (hasMoreData) {
            // Динамічно беремо АКТИВНИЙ ключ через getActiveKey()
            String url = "https://developers.ria.com/dom/search?"
                    + "api_key=" + getActiveKey()
                    + "&category_id=1"
                    + "&realty_type_id=2"
                    + "&state_id=25"
                    + "&city_id=25"
                    + "&operation_type=" + operationType
                    + "&count=100"
                    + "&page=" + page;

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            try {
                System.out.println("-> [Етап 1] Запит сторінки " + page + " за допомогою ключа №" + (currentKeyIndex + 1) + "...");
                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    String jsonResponse = response.body();

                    if (jsonResponse.contains("\"items\":[]") || !jsonResponse.contains("\"items\"")) {
                        System.out.println("-> На сторінці " + page + " немає даних або досягнуто кінця списку.");
                        hasMoreData = false;
                    } else {
                        String fileName = "перший 100 запит_" + prefix + "_page_" + page + ".json";

                        // ЗМІНА ТУТ: об'єднуємо папку та ім'я файлу. Шлях буде: apartament_id/ім'я_файлу.json
                        java.nio.file.Path outputPath = Paths.get(targetFolder, fileName);

                        // Зберігаємо файл за новим цільовим шляхом
                        Files.writeString(outputPath, jsonResponse);

                        System.out.println("-> [УСПІХ] Списковий файл збережено в " + targetFolder + ": " + fileName);
                        page++;
                        Thread.sleep(DELAY_MS); // Коротка пауза 3 секунди між сторінками
                    }
                } else if (response.statusCode() == 429) {
                    System.err.println("!!! [Етап 1] Ключ №" + (currentKeyIndex + 1) + " зловив ліміт 429!");

                    // Змінюємо ключ на наступний із нашого пулу
                    switchToNextKey();

                    // Індекс сторінки (page++) НЕ збільшуємо, цикл просто повторить запит цієї ж сторінки з новим ключем
                    Thread.sleep(1000); // Секундна пауза для стабільності

                } else {
                    System.err.println("Помилка Етапу 1. Сервер повернув код: " + response.statusCode());
                    hasMoreData = false;
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("Помилка мережі на Етапі 1: " + e.getMessage());
                hasMoreData = false;
            }
        }
    }



}


