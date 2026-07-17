package core.serverDB.sqlite;

import java.sql.SQLException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Менеджер підключення до бази даних SQLite.
 * Реалізує патерн Singleton — одне підключення на всю програму.
 */
public class DatabaseManager {

    // Шлях до файлу бази даних
    private static final String DB_PATH = "data/database.db";
    private static final String DB_URL  = "jdbc:sqlite:" + DB_PATH;

    // Єдине підключення (Singleton)
    private static Connection connection;

    // ── Приватний конструктор — клас не можна інстанціювати ──────────────────
    private DatabaseManager() {}

    // ── Отримати підключення ──────────────────────────────────────────────────

    /**
     * Повертає єдине підключення до БД.
     * Якщо підключення ще немає або воно закрите — створює нове.
     */
    public static synchronized Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(DB_URL);
                // Вмикаємо підтримку зовнішніх ключів (FOREIGN KEY)
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = ON");
                    stmt.execute("PRAGMA journal_mode = WAL"); // краща продуктивність при записі
                }
            }
            return connection;
        } catch (SQLException e) {
            throw new RuntimeException("❌ Не вдалося підключитися до БД: " + e.getMessage(), e);
        }
    }

    // ── Ініціалізація БД ──────────────────────────────────────────────────────

    /**
     * Викликається один раз при старті програми (з Main.java).
     * Створює файл БД якщо не існує та ініціалізує всі таблиці.
     */
    public static void initializeDatabase() {
        try {
            // Завантажуємо драйвер SQLite
            Class.forName("org.sqlite.JDBC");

            // Створюємо директорію data/ якщо не існує
            java.nio.file.Files.createDirectories(
                    java.nio.file.Paths.get("data"));

            // Встановлюємо підключення (файл БД створюється автоматично)
            getConnection();

            // Ініціалізуємо таблиці всіх сервісів
            ProjectDatabaseService.initTables();

            System.out.println("✅ [SQLite] База даних та таблиці успішно перевірені.");
            System.out.println("📁 [SQLite] Файл БД: " +
                    new java.io.File(DB_PATH).getAbsolutePath());

        } catch (ClassNotFoundException e) {
            throw new RuntimeException(
                    "❌ SQLite драйвер не знайдено. Додай залежність у pom.xml:\n" +
                            "<dependency>\n" +
                            "  <groupId>org.xerial</groupId>\n" +
                            "  <artifactId>sqlite-jdbc</artifactId>\n" +
                            "  <version>3.45.3.0</version>\n" +
                            "</dependency>", e);
        } catch (Exception e) {
            throw new RuntimeException("❌ Помилка ініціалізації БД: " + e.getMessage(), e);
        }
    }

    // ── Закрити підключення ───────────────────────────────────────────────────

    /**
     * Закриває підключення до БД.
     * Викликати при завершенні програми (ShutdownHook).
     */
    public static synchronized void closeConnection() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                    System.out.println("🔒 [SQLite] Підключення закрито.");
                }
            } catch (SQLException e) {
                System.err.println("⚠️ Помилка закриття підключення: " + e.getMessage());
            } finally {
                connection = null;
            }
        }
    }
}
