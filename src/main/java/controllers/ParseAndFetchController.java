package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.dimRia.Announcement;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class ParseAndFetchController {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Парсить першу відповідь від /search і дістає звідти масив ідентифікаторів (ID)
     */
    public List<Long> parseSearchIds(String jsonResponse) {
        List<Long> idsList = new ArrayList<>();

        try {
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            JsonNode itemsNode = rootNode.get("items");

            if (itemsNode != null && itemsNode.isArray()) {
                for (JsonNode idNode : itemsNode) {
                    idsList.add(idNode.asLong());
                }
            }
        } catch (Exception e) {
            System.err.println("Помилка парсингу JSON пошуку: " + e.getMessage());
        }

        return idsList;
    }

    /**
     * Парсить JSON детальної інформації від /info/{id} і створює об'єкт Announcement.
     * Враховує реальну динамічну структуру характеристик та цін DIM.RIA.
     */
    public Announcement parseSingleAnnouncement(Long id, String jsonDetailResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonDetailResponse);

            // 1. Назва оголошення (DIM.RIA часто віддає сформований тайтл у "title" або "user_title")
            String title = root.has("title") ? root.path("title").asText() : root.path("user_title").asText("Нерухомість");

            // 2. Опис оголошення (зазвичай в "description" або "text")
            String description = root.has("description") ? root.path("description").asText() : root.path("text").asText("Опис відсутній");

            // 3. Ціна (price)
            // В DIM.RIA ціна зазвичай лежить в об'єкті "price_details" або прямо в "price_usd" / "price"
            // Пріоритет віддаємо доларовому еквіваленту або базовому полю price
            double priceValue = 0.0;
            if (root.has("price_usd")) {
                priceValue = root.path("price_usd").asDouble();
            } else if (root.has("price")) {
                priceValue = root.path("price").asDouble();
            }
            BigDecimal price = BigDecimal.valueOf(priceValue);

            // 4. Автор оголошення
            String author = root.path("user_name").asText("Приватна особа");

            // 5. Дата публікації (DIM.RIA часто віддає "created_at" або "publish_date")
            String date = root.has("publish_date") ? root.path("publish_date").asText() : root.path("created_at").asText("Не вказано");

            // 6. Номер телефону
            // Телефони часто лежать або в "user_phone", або масивом у "beautiful_phones" / "phones"
            String numberPhone = "Не вказано";
            if (root.has("user_phone")) {
                numberPhone = root.path("user_phone").asText();
            } else if (root.path("phones").isArray() && root.path("phones").size() > 0) {
                numberPhone = root.path("phones").get(0).path("number").asText("Не вказано");
            }

            // 7. Посилання на головну фотографію
            String urlImage = "Фото відсутнє";
            JsonNode photosNode = root.path("photos");
            if (photosNode.isObject() && photosNode.size() > 0) {
                // Іноді фото приходять об'єктом, де ключі - це ID фото
                JsonNode firstPhoto = photosNode.elements().next();
                urlImage = extractPhotoUrl(firstPhoto);
            } else if (photosNode.isArray() && photosNode.size() > 0) {
                // Або класичним масивом
                urlImage = extractPhotoUrl(photosNode.get(0));
            }

            // 8. ДИНАМІЧНІ ХАРАКТЕРИСТИКИ (Збагачуємо опис або назву на основі конфігу)
            // В DIM.RIA характеристики об'єкта лежать у вузлі "characteristics" або "features"
            // Вони приходять як масив об'єктів з id, де id відповідає characteristic_id з твого конфігу
            JsonNode characteristics = root.path("characteristics");
            if (characteristics.isArray()) {
                int rooms = 0;
                double square = 0.0;

                for (JsonNode charNode : characteristics) {
                    int charId = charNode.path("characteristic_id").asInt();

                    // Звіряємося з ID з твого конфігу:
                    switch (charId) {
                        case 442: // Кількість кімнат (characteristic_id: 442 у твоєму файлі)
                            rooms = charNode.path("value").asInt();
                            break;
                        case 440: // Загальна площа (characteristic_id: 440)
                            square = charNode.path("value").asDouble();
                            break;
                        // Тут за потреби можна витягнути стіни (1434), рік побудови (443) тощо
                    }
                }

                // Якщо вдалося розпарсити кімнати та площу, красиво допишемо їх на початок опису або тайтлу
                if (rooms > 0 || square > 0) {
                    String metaDetails = String.format("[%d-кімн., площа: %.1f м²] ", rooms, square);
                    description = metaDetails + description;

                    // Якщо дефолтний тайтл надто сухий, додамо конкретики
                    if (title.equals("Нерухомість") || title.contains("Квартира")) {
                        title = String.format("%d-кімнатна квартира, %.1f м²", rooms, square);
                    }
                }
            }

            return new Announcement(id, urlImage, title, description, price, author, date, numberPhone);

        } catch (Exception e) {
            System.err.println("Помилка під час парсингу деталей оголошення ID " + id + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Допоміжний метод для витягування правильного URL фотографії
     */
    private String extractPhotoUrl(JsonNode photoNode) {
        if (photoNode == null) return "Фото відсутнє";

        // В DIM.RIA у фото зазвичай є шаблони імен або рівні якості: "file", "main", "secure"
        if (photoNode.has("file")) {
            return photoNode.path("file").asText();
        } else if (photoNode.has("main")) {
            return photoNode.path("main").asText();
        }

        // Якщо прийшов просто рядок (URL)
        if (photoNode.isTextual()) {
            return photoNode.asText();
        }

        return "Фото відсутнє";
    }
}
