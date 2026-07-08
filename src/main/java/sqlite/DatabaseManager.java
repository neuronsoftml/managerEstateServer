package sqlite;

import model.dimRia.Announcement;

import java.sql.*;
import java.util.List;

public class DatabaseManager {

    private static final String DB_URL = "jdbc:sqlite:apartments_base.db";

    // Винесли SQL-запит створення таблиці в окрему константу для зручності
    private static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS dimria_apartments (" +
            "realty_id INTEGER PRIMARY KEY," +
            "title TEXT," +
            "description TEXT," +
            "price REAL," +
            "currency TEXT," +
            "author TEXT," +
            "date_published TEXT," +
            "phone_number TEXT," +
            "url_image TEXT," +
            "created_at TEXT DEFAULT CURRENT_TIMESTAMP" +
            ");";

    /**
     * Ініціалізація бази даних
     */
    public static void initializeDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {

            stmt.execute(CREATE_TABLE_SQL);
            System.out.println("[SQLite] База даних та таблиця успішно перевірені.");

        } catch (SQLException e) {
            System.err.println("[SQLite] Помилка ініціалізації бази: " + e.getMessage());
        }
    }

    /**
     * Пакетне збереження квартир із автоматичним захистом від відсутності таблиці
     */
    public static void saveApartments(List<Announcement> apartments) {
        String insertSQL = "INSERT OR IGNORE INTO dimria_apartments " +
                "(realty_id, title, description, price, currency, author, date_published, phone_number, url_image) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?,?)";

        try (Connection conn = DriverManager.getConnection(DB_URL)) {

            // ГАРАНТІЯ: Перед будь-яким записом ще раз переконуємося, що таблиця існує
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(CREATE_TABLE_SQL);
            }

            // Переходимо до пакетного запису
            try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                conn.setAutoCommit(false); // Вимикаємо авто-комміт для швидкості

                for (Announcement a : apartments) {
                    pstmt.setLong(1, a.getId());
                    pstmt.setString(2, a.getTitle());
                    pstmt.setString(3, a.getDescription());
                    pstmt.setDouble(4, a.getPrice().doubleValue());
                    pstmt.setString(5, a.getCurrency());
                    pstmt.setString(6, a.getAuthor());
                    pstmt.setString(7, a.getDate());
                    pstmt.setString(8, a.getNumberPhone());
                    pstmt.setString(9, a.getUrlImage());

                    pstmt.addBatch();
                }

                int[] results = pstmt.executeBatch();
                conn.commit(); // Фіксуємо транзакцію на диску

                int addedNew = 0;
                for (int res : results) {
                    if (res > 0 || res == Statement.SUCCESS_NO_INFO) {
                        addedNew++;
                    }
                }

                System.out.println("[SQLite] Пакетний запис завершено. Додано нових квартир у базу: " + addedNew);
            }

        } catch (SQLException e) {
            System.err.println("[SQLite] Помилка пакетного запису: " + e.getMessage());
        }
    }


    public static void entryUpdatedDatabase(List<Announcement> announcements) {
        // Крок 3: Записуємо отримані дані в SQLite
        if (!announcements.isEmpty()) {
            System.out.println("\n[core.Main] Знайдено об'єкти для імпорту. Запуск запису в базу...");
            DatabaseManager.saveApartments(announcements);
        } else {
            System.out.println("\n[core.Main] Папка з деталями порожня або файли не знайдені. Нічого записувати.");
        }

        System.out.println("\n=== РОБОТУ ЗАВЕРШЕНО ===");
    }
}


