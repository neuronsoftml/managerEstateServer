package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.dimRia.Announcement;
import sqlite.DatabaseManager;


import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public  class ParseAndFetchController {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static void start(List<Announcement> announcements) {

    }

    /**
     * ЕТАП 3: Зчитування файлів з папки та парсинг у колекцію об'єктів Announcement
     */
    public static List<Announcement> parseSavedApartments() {
        System.out.println("\n=== [DimRiaController] Запуск Етапу 3: Парсинг JSON під структуру об'єкта ===");

        List<Announcement> announcementsList = new ArrayList<>();
        Path detailsFolder = Paths.get("apartment_details");

        if (!Files.exists(detailsFolder)) {
            System.out.println("Папку 'apartment_details' не знайдено! Спочатку запустіть скачування деталей.");
            return announcementsList;
        }

        try (Stream<Path> walk = Files.walk(detailsFolder)) {
            List<Path> jsonFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .toList();

            System.out.println("Знайдено " + jsonFiles.size() + " файлів для парсингу.");

            for (Path file : jsonFiles) {
                try {
                    JsonNode root = OBJECT_MAPPER.readTree(file.toFile());

                    Long id = root.has("realty_id") ? root.get("realty_id").asLong() : null;
                    if (id == null) {
                        System.out.println("-> Пропущено файл " + file.getFileName() + ", бо відсутній realty_id");
                        continue;
                    }

                    String type = root.has("realty_type_name_uk") ? root.get("realty_type_name_uk").asText() : "Нерухомість";
                    int rooms = root.has("rooms_count") ? root.get("rooms_count").asInt() : 0;
                    String street = root.has("street_name_uk") ? root.get("street_name_uk").asText() : "";
                    String title = type + (rooms > 0 ? ", " + rooms + " кімн." : "") + (!street.isEmpty() ? ", " + street : "");

                    String description = root.has("description_uk") ? root.get("description_uk").asText() : "Опис відсутній";

                    String urlImage = "Немає фото";
                    if (root.has("main_photo") && !root.get("main_photo").asText().isEmpty()) {
                        urlImage = "https://cdn.riastatic.com/photosnewr/" + root.get("main_photo").asText();
                    }

                    BigDecimal price = BigDecimal.ZERO;
                    String currency =  root.has("currency_type_uk") ? root.get("currency_type_uk").asText() : "Не вказано";

                    if (root.has("price")) {
                        price = new BigDecimal(root.get("price").asText());
                    }

                    String author = "Приватна особа";
                    if (root.has("agency") && root.get("agency").has("name")) {
                        author = root.get("agency").get("name").asText();
                    } else if (root.has("user_newbuild_name_uk")) {
                        author = root.get("user_newbuild_name_uk").asText();
                    }

                    String date = root.has("created_at") ? root.get("created_at").asText() : "Не вказано";
                    String numberPhone = "Контакт через " + author;

                    Announcement announcement = new Announcement(id, urlImage, title, description, price, currency, author, date, numberPhone);
                    announcementsList.add(announcement);

                } catch (IOException e) {
                    System.err.println("Помилка розбору файлу " + file.getFileName() + ": " + e.getMessage());
                }
            }

        } catch (IOException e) {
            System.err.println("Помилка читання папки: " + e.getMessage());
        }

        System.out.println("Успішно запарсено " + announcementsList.size() + " об'єктів у колекцію!");
        return announcementsList;
    }


    /**
     * Зчитує JSON-файл із диска, знаходить у ньому масив ідентифікаторів та
     * трансформує його у список об'єктів Long.
     * <p>
     * Метод очікує, що JSON-структура містить кореневий вузол "items",
     * який є масивом числових значень (ID оголошень DimRia). У разі помилки
     * зчитування або невірного формату файлу, метод не перериває виконання
     * програми, а логує помилку в консоль і повертає порожній список.
     * </p>
     *
     * @param filePath шлях до цільового файлу (.json), який необхідно розпарсити
     * @return {@link List} об'єктів {@link Long}, що містить усі знайдені ID;
     * або порожній список, якщо вузол "items" відсутній, не є масивом, або виникла помилка зчитування.
     */
    public static List<Long> extractIdsFromJson(Path filePath) {
        List<Long> idList = new ArrayList<>();
        try {
            JsonNode rootNode = OBJECT_MAPPER.readTree(filePath.toFile());
            JsonNode itemsNode = rootNode.get("items");

            if (itemsNode != null && itemsNode.isArray()) {
                for (JsonNode idNode : itemsNode) {
                    idList.add(idNode.asLong());
                }
            }
        } catch (IOException e) {
            System.err.println("Не вдалося розпарсити файл " + filePath.getFileName() + ": " + e.getMessage());
        }
        return idList;
    }
}
