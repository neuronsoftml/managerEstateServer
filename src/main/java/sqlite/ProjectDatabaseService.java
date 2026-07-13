package sqlite;
import core.tools.PrivatBankRateService;
import model.Announcement;
import model.CategoryLocation;
import model.City;
import model.telegram.UserSession;
import model.telegram.agent.TelegramGroup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class ProjectDatabaseService {

    public static void initTables() {
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS olx_announcements (
                    id                  TEXT PRIMARY KEY,
                    city                TEXT,
                    category            TEXT,
                    url                 TEXT,
                    title               TEXT,
                    price_value         REAL,
                    price_currency      TEXT,
                    location            TEXT,
                    date_published      TEXT,
                    seller              TEXT,
                    phone               TEXT,
                    description         TEXT,
                    rooms_count         INTEGER DEFAULT -1,
                    total_area          REAL    DEFAULT -1,
                    floor               INTEGER DEFAULT -1,
                    sent_to_telegram    INTEGER DEFAULT 0,
                    created_at          TEXT    DEFAULT (datetime('now'))
                )
            """);

            //TODO Потім треба буде видалити
            // ── Міграція: додаємо колонку якщо таблиця вже існує без неї ────────
            // ALTER TABLE ігнорується якщо колонка вже є через try-catch
            try {
                stmt.execute(
                        "ALTER TABLE olx_announcements ADD COLUMN sent_to_telegram INTEGER DEFAULT 0"
                );
                System.out.println("✅ [SQLite] Міграція: колонка sent_to_telegram додана.");
            } catch (SQLException ignored) {
                // Колонка вже існує — це нормально, ігноруємо
            }

            //TODO Відповідає за спісок груп телеграм для мотітору
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS monitored_groups_telegram (
                    id                          INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id                     TEXT NOT NULL UNIQUE,
                    group_name                  TEXT,
                    last_processed_message_id   INTEGER DEFAULT 0,
                    is_active                   INTEGER DEFAULT 1
                )
            """);


            stmt.execute("""
                CREATE TABLE IF NOT EXISTS olx_photos (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    announcement_id TEXT NOT NULL,
                    photo_url       TEXT NOT NULL,
                    sort_order      INTEGER DEFAULT 0,
                    FOREIGN KEY (announcement_id) REFERENCES olx_announcements(id)
                        ON DELETE CASCADE
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS olx_params (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    announcement_id TEXT NOT NULL,
                    param_text      TEXT NOT NULL,
                    FOREIGN KEY (announcement_id) REFERENCES olx_announcements(id)
                        ON DELETE CASCADE
                )
            """);


            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_city          ON olx_announcements(city)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_category      ON olx_announcements(category)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_price         ON olx_announcements(price_value)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_rooms         ON olx_announcements(rooms_count)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_sent_telegram ON olx_announcements(sent_to_telegram)");

        } catch (SQLException e) {
            throw new RuntimeException("Помилка створення таблиць: " + e.getMessage(), e);
        }
    }

    // ── Запис оголошення ──────────────────────────────────────────────────────

    public static boolean saveAnnouncement(Announcement a) {
        // INSERT OR IGNORE — якщо ID вже є, НЕ перезаписуємо
        // (щоб не скидати sent_to_telegram = 0 у вже відправлених)
        String sql = """
            INSERT OR IGNORE INTO olx_announcements
                (id, city, category, url, title, price_value, price_currency,
                 location, date_published, seller, phone, description,
                 rooms_count, total_area, floor, sent_to_telegram)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
        """;

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1,  a.getId());
                ps.setString(2,  a.getCity()     != null ? a.getCity().getLabel()     : "");
                ps.setString(3,  a.getCategory() != null ? a.getCategory().getLabel() : "");
                ps.setString(4,  a.getUrl());
                ps.setString(5,  a.getTitle());
                BigDecimal pv = a.getPriceValue();
                if (pv != null) ps.setDouble(6, pv.doubleValue());
                else            ps.setNull(6, Types.REAL);
                ps.setString(7,  a.getPriceCurrency());
                ps.setString(8,  a.getLocation());
                ps.setString(9,  a.getDatePublished());
                ps.setString(10, a.getSeller());
                ps.setString(11, a.getPhone());
                ps.setString(12, a.getDescription());
                ps.setInt   (13, a.getRoomsCount());
                ps.setDouble(14, a.getTotalArea());
                ps.setInt   (15, a.getFloor());
                ps.executeUpdate();
            }

            // Фото і параметри — тільки якщо запис новий (INSERT OR IGNORE)
            // Перевіряємо через changes()
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT changes()")) {
                if (rs.next() && rs.getInt(1) > 0) {
                    savePhotos(a, conn);
                    saveParams(a, conn);
                }
            }

            conn.commit();
            return true;

        } catch (SQLException e) {
            System.err.println("❌ Помилка запису ID=" + a.getId() + ": " + e.getMessage());
            return false;
        }
    }

    private static void savePhotos(Announcement a, Connection conn) throws SQLException {
        if (a.getPhotos() == null || a.getPhotos().isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO olx_photos (announcement_id, photo_url, sort_order) VALUES (?,?,?)")) {
            for (int i = 0; i < a.getPhotos().size(); i++) {
                ps.setString(1, a.getId());
                ps.setString(2, a.getPhotos().get(i));
                ps.setInt   (3, i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void saveParams(Announcement a, Connection conn) throws SQLException {
        if (a.getParams() == null || a.getParams().isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO olx_params (announcement_id, param_text) VALUES (?,?)")) {
            for (String param : a.getParams()) {
                ps.setString(1, a.getId());
                ps.setString(2, param);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── Telegram статус ───────────────────────────────────────────────────────

    /**
     * Позначає оголошення як відправлене в Telegram.
     * Викликати тільки після успішної відправки.
     */
    public static void markAsSentToTelegram(String id) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE olx_announcements SET sent_to_telegram = 1 WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("❌ markAsSentToTelegram ID=" + id + ": " + e.getMessage());
        }
    }

    /**
     * Повертає всі оголошення які ще не були відправлені в Telegram.
     * Використовується при старті програми для дозакидання пропущених.
     */
    public static List<Announcement> getUnsentAnnouncements() {
        List<Announcement> list = new ArrayList<>();
        String sql = """
            SELECT a.id, a.city, a.category, a.url, a.title,
                   a.price_value, a.price_currency, a.location,
                   a.date_published, a.seller, a.phone, a.description
            FROM olx_announcements a
            WHERE a.sent_to_telegram = 0
            ORDER BY a.created_at ASC
        """;

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String id       = rs.getString("id");
                String cityStr  = rs.getString("city");
                String catStr   = rs.getString("category");

                // Зворотний маппінг рядків → enum
                City city = City.CHERNIVTSI;
                for (City c : City.values()) {
                    if (c.getLabel().equalsIgnoreCase(cityStr)) { city = c; break; }
                }
                CategoryLocation cat = CategoryLocation.RENT_LONG;
                for (CategoryLocation cl : CategoryLocation.values()) {
                    if (cl.getLabel().equalsIgnoreCase(catStr)) { cat = cl; break; }
                }

                Announcement ad = new Announcement(
                        id, rs.getString("url"), rs.getString("title"),
                        "", rs.getString("location"), city, cat
                );
                double pv = rs.getDouble("price_value");
                if (!rs.wasNull()) ad.setPriceValue(BigDecimal.valueOf(pv));
                ad.setPriceCurrency(rs.getString("price_currency"));
                ad.setDatePublished(rs.getString("date_published"));
                ad.setSeller(rs.getString("seller"));
                ad.setPhone(rs.getString("phone"));
                ad.setDescription(rs.getString("description"));

                // Завантажуємо фото і параметри
                ad.setPhotos(getPhotosForAnnouncement(id, conn));
                ad.setParams(getParamsForAnnouncement(id, conn));

                list.add(ad);
            }
        } catch (SQLException e) {
            System.err.println("❌ getUnsentAnnouncements: " + e.getMessage());
        }
        return list;
    }

    // ── Перевірка існування ───────────────────────────────────────────────────

    public static boolean exists(String id) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT 1 FROM olx_announcements WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            return false;
        }
    }

    public static int countAll() {
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM olx_announcements")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return -1;
        }
    }

    // ── Фільтрація для Telegram бота ─────────────────────────────────────────

    public static List<Announcement> getAnnouncementsByFilter(UserSession session) {
        List<Announcement> results = new ArrayList<>();
        String sql = """
            SELECT id, city, category, url, title, price_value, price_currency,
                   location, date_published, seller, phone, description
            FROM olx_announcements
            WHERE city = ? AND category = ?
            ORDER BY created_at DESC
        """;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, session.getSelectedCity().getLabel());
            ps.setString(2, session.getSelectedCategory().getLabel());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id  = rs.getString("id");
                    City city  = session.getSelectedCity();
                    CategoryLocation cat = session.getSelectedCategory();

                    Announcement ad = new Announcement(
                            id, rs.getString("url"), rs.getString("title"),
                            "", rs.getString("location"), city, cat
                    );
                    double pv = rs.getDouble("price_value");
                    String currency = rs.getString("price_currency");
                    if (!rs.wasNull()) ad.setPriceValue(BigDecimal.valueOf(pv));
                    ad.setPriceCurrency(currency);
                    ad.setDatePublished(rs.getString("date_published"));
                    ad.setSeller(rs.getString("seller"));
                    ad.setPhone(rs.getString("phone"));
                    ad.setDescription(rs.getString("description"));
                    ad.setPhotos(getPhotosForAnnouncement(id, conn));
                    ad.setParams(getParamsForAnnouncement(id, conn));

                    // Фільтр по кімнатах
                    if (session.getSelectedRooms() != null &&
                            ad.getRoomsCount() != session.getSelectedRooms()) continue;

                    // Фільтр по ціні (конвертація через ПриватБанк)
                    if (session.getMinPriceUsd() != null && ad.getPriceValue() != null) {
                        int priceUsd;
                        if (currency != null && (currency.equalsIgnoreCase("usd") || currency.equalsIgnoreCase("$"))) {
                            priceUsd = ad.getPriceValue().intValue();
                        } else {
                            priceUsd = PrivatBankRateService.convertUahToUsd(ad.getPriceValue()).intValue();
                        }
                        if (priceUsd < session.getMinPriceUsd() || priceUsd > session.getMaxPriceUsd()) continue;
                    }

                    results.add(ad);
                }
            }
        } catch (SQLException e) {
            System.err.println("❌ getAnnouncementsByFilter: " + e.getMessage());
        }
        return results;
    }

    // ── Приватні хелпери ──────────────────────────────────────────────────────

    private static List<String> getPhotosForAnnouncement(String id, Connection conn) {
        List<String> photos = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT photo_url FROM olx_photos WHERE announcement_id = ? ORDER BY sort_order ASC")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) photos.add(rs.getString("photo_url"));
            }
        } catch (SQLException e) {
            System.err.println("⚠️ Фото для ID=" + id + ": " + e.getMessage());
        }
        return photos;
    }

    private static List<String> getParamsForAnnouncement(String id, Connection conn) {
        List<String> params = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT param_text FROM olx_params WHERE announcement_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) params.add(rs.getString("param_text"));
            }
        } catch (SQLException e) {
            System.err.println("⚠️ Параметри для ID=" + id + ": " + e.getMessage());
        }
        return params;
    }


    // ── МЕТОДИ ДЛЯ ТАБЛИЦІ monitored_groups_telegram ──────────────────────────

    /**
     * Отримує список усіх активних груп Telegram для фонового сканера.
     */
    public static List<TelegramGroup> getActiveMonitoredGroups() {
        List<TelegramGroup> groups = new ArrayList<>();
        String sql = "SELECT id, chat_id, group_name, last_processed_message_id, is_active FROM monitored_groups_telegram WHERE is_active = 1";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                groups.add(new TelegramGroup(
                        rs.getInt("id"),
                        rs.getString("chat_id"),
                        rs.getString("group_name"),
                        rs.getLong("last_processed_message_id"),
                        rs.getInt("is_active") == 1
                ));
            }
        } catch (SQLException e) {
            System.err.println("❌ Помилка зчитування активних груп Telegram: " + e.getMessage());
        }
        return groups;
    }

    /**
     * Оновлює ID останнього обробленого повідомлення у конкретній групі.
     * Це гарантує, що після перезапуску бот не буде надсилати старі дублікати.
     */
    public static void updateGroupLastMessageId(String chatId, long lastMessageId) {
        String sql = "UPDATE monitored_groups_telegram SET last_processed_message_id = ? WHERE chat_id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, lastMessageId);
            ps.setString(2, chatId);
            ps.executeUpdate();

        } catch (SQLException e) {
            System.err.println("❌ Помилка оновлення last_processed_message_id для " + chatId + ": " + e.getMessage());
        }
    }

    /**
     * Динамічно додає нову Telegram групу/канал на моніторинг.
     * Якщо група з таким chat_id вже існує, база даних проігнорує запис без помилок (завдяки INSERT OR IGNORE).
     */
    public static boolean addGroupToMonitor(String chatId, String groupName) {
        String sql = "INSERT OR IGNORE INTO monitored_groups_telegram (chat_id, group_name) VALUES (?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, chatId);
            ps.setString(2, groupName);
            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            System.err.println("❌ Помилка додавання групи " + groupName + " на моніторинг: " + e.getMessage());
            return false;
        }
    }
}
