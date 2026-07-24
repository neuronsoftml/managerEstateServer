package core.serverDB.sqlite;
import core.telegram.model.TenantApplicationForm;
import core.tools.PrivatBankRateService;
import model.Announcement;
import model.AnnouncementCategory;
import model.City;
import model.DealType;
import model.PropertyType;
import core.telegram.model.UserSession;
import model.CreatePostDraft;
import model.SavedSearchFilter;
import model.NotificationBatch;


import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Головний сервіс доступу до даних (DAO) для роботи з локальною базою даних SQLite.
 * <p>
 * Відповідає за ініціалізацію схем таблиць, міграцію структури бази даних при оновленні додатку,
 * збереження оголошень (зв'язаних фото та параметрів) за допомогою транзакцій, роботу з чернетками анкет
 * користувачів (Wizard-опитувальник) та керування списком моніторингу Telegram-груп.
 * </p>
 * <p>
 * Ключові особливості реалізації:
 * <ul>
 * <li>Оптимізація запису великої кількості пов'язаних сутностей через {@code Batch Processing}.</li>
 * <li>Автоматична міграція колонок "on-the-fly" без руйнування існуючих даних.</li>
 * <li>Використання {@code INSERT OR IGNORE} та {@code CHANGES()} для уникнення затирання статусу відправки в Telegram.</li>
 * </ul>
 * </p>
 * * @author Mykola
 */
public class ProjectDatabaseService {

    /**
     * Ініціалізує структуру бази даних SQLite: створює необхідні таблиці та індекси, якщо вони не існують.
     * <p>
     * Метод також містить блоки міграцій для безпечного додавання нових колонок у таблиці
     * {@code olx_announcements} та {@code user_profiles} у разі оновлення схеми на «живій» базі даних.
     * </p>
     *
     * @throws RuntimeException якщо виникає критична помилка під час виконання SQL-запитів
     */
    public static void initTables() {
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Таблиця оголошень OLX
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

            // ── МІГРАЦІЯ: Додавання колонки sent_to_telegram ──────────────────
            // try-catch запобігає падінню ініціалізації, якщо колонка вже була створена раніше
            try {
                stmt.execute(
                        "ALTER TABLE olx_announcements ADD COLUMN sent_to_telegram INTEGER DEFAULT 0"
                );
                System.out.println("✅ [SQLite] Міграція: колонка sent_to_telegram додана.");
            } catch (SQLException ignored) {
                // Колонка вже існує — ігноруємо помилку
            }

            // 2. Таблиця моніторингу груп Telegram
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS monitored_groups_telegram (
                    id                          INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id                     TEXT NOT NULL UNIQUE,
                    group_name                  TEXT,
                    last_processed_message_id   INTEGER DEFAULT 0,
                    is_active                   INTEGER DEFAULT 1
                )
            """);

            // 3. Таблиця фотографій оголошень (Зв'язок 1:N з каскадним видаленням)
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

            // 4. Таблиця параметрів оголошень (Зв'язок 1:N з каскадним видаленням)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS olx_params (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    announcement_id TEXT NOT NULL,
                    param_text      TEXT NOT NULL,
                    FOREIGN KEY (announcement_id) REFERENCES olx_announcements(id)
                        ON DELETE CASCADE
                )
            """);

            // 5. Таблиця профілів/анкет користувачів (Wizard-опитувальник "Шукаю житло")
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_profiles (
                    telegram_id             INTEGER PRIMARY KEY,
                    user_name               TEXT NOT NULL DEFAULT '',
                    phone_number            TEXT NOT NULL DEFAULT '',
                    budget                  INTEGER NOT NULL,
                    ready_for_deposit       BOOLEAN NOT NULL,
                    ready_for_commission    BOOLEAN NOT NULL,
                    preferred_districts     TEXT NOT NULL,
                    rooms_type              TEXT NOT NULL,
                    rent_term               TEXT NOT NULL,
                    tenants_count           INTEGER NOT NULL,
                    tenants_description     TEXT NOT NULL,
                    has_children            BOOLEAN NOT NULL,
                    children_info           TEXT,
                    has_pets                BOOLEAN NOT NULL,
                    pets_info               TEXT,
                    employment_sphere       TEXT NOT NULL,
                    work_format             TEXT NOT NULL,
                    smoking_status          TEXT NOT NULL,
                    has_car                 BOOLEAN NOT NULL,
                    critical_requirements   TEXT,
                    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            // ── МІГРАЦІЯ: Додавання нових колонок у профілі користувачів ──────
            try {
                stmt.execute("ALTER TABLE user_profiles ADD COLUMN user_name TEXT NOT NULL DEFAULT ''");
            } catch (SQLException ignored) { /* колонка вже існує */ }
            try {
                stmt.execute("ALTER TABLE user_profiles ADD COLUMN phone_number TEXT NOT NULL DEFAULT ''");
            } catch (SQLException ignored) { /* колонка вже існує */ }

            // 5.1 Таблиця оголошень, створених самими користувачами через CreatePostWizardController
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_created_posts (
                    payment_code        TEXT PRIMARY KEY,
                    telegram_id         INTEGER NOT NULL,
                    deal_type           TEXT,
                    property_type       TEXT,
                    market_type         TEXT,
                    title               TEXT,
                    price_value         REAL,
                    price_currency      TEXT,
                    location            TEXT,
                    land_area           REAL,
                    rooms               INTEGER,
                    total_area          REAL,
                    floor               INTEGER,
                    total_floors        INTEGER,
                    seller_type         TEXT,
                    duration_days       INTEGER,
                    description         TEXT,
                    phone_number        TEXT,
                    listing_fee_uah     INTEGER,
                    payment_status      TEXT NOT NULL DEFAULT 'PENDING',
                    created_at          TEXT    DEFAULT (datetime('now'))
                )
            """);

            // 5.2 Фотографії, прикріплені до створеного користувачем оголошення (1:N)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_created_post_photos (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    payment_code    TEXT NOT NULL,
                    photo_file_id   TEXT NOT NULL,
                    sort_order      INTEGER DEFAULT 0,
                    FOREIGN KEY (payment_code) REFERENCES user_created_posts(payment_code)
                        ON DELETE CASCADE
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_user_posts_status ON user_created_posts(payment_status)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_user_posts_telegram ON user_created_posts(telegram_id)");

            // 6. Створення індексів для оптимізації вибірок за основними фільтрами пошуку
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_city          ON olx_announcements(city)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_category      ON olx_announcements(category)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_price         ON olx_announcements(price_value)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_rooms         ON olx_announcements(rooms_count)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_sent_telegram ON olx_announcements(sent_to_telegram)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS telegram_users (
                    telegram_id INTEGER PRIMARY KEY,
                    is_subscribed INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT DEFAULT (datetime('now'))
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS saved_search_filters (
                    telegram_id INTEGER PRIMARY KEY,
                    deal_type TEXT, property_type TEXT, city TEXT, category TEXT,
                    rooms INTEGER, rooms_minimum INTEGER NOT NULL DEFAULT 0,
                    min_price_usd INTEGER, max_price_usd INTEGER,
                    notifications_enabled INTEGER NOT NULL DEFAULT 1,
                    last_notified_at TEXT,
                    updated_at TEXT DEFAULT (datetime('now')),
                    FOREIGN KEY (telegram_id) REFERENCES telegram_users(telegram_id) ON DELETE CASCADE
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_filters_notifications ON saved_search_filters(notifications_enabled)");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS pending_notification_batches (
                    telegram_id INTEGER PRIMARY KEY,
                    announcement_ids TEXT NOT NULL,
                    current_offset INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT DEFAULT (datetime('now')),
                    FOREIGN KEY (telegram_id) REFERENCES telegram_users(telegram_id) ON DELETE CASCADE
                )
            """);

        } catch (SQLException e) {
            throw new RuntimeException("Помилка створення таблиць: " + e.getMessage(), e);
        }
    }

    // ── РОБОТА З ОГОЛОШЕННЯМИ ─────────────────────────────────────────────────

    /**
     * Зберігає оголошення в базі даних у межах однієї транзакції.
     * <p>
     * Використовує механізм {@code INSERT OR IGNORE}, щоб не перезаписувати існуючі оголошення
     * й не занулювати статус надсилання в Telegram ({@code sent_to_telegram = 0}).
     * Додаткові дані (фото та параметри) записуються лише тоді, коли запис дійсно новий
     * (перевіряється функцією {@code changes()}).
     * </p>
     *
     * @param a об'єкт оголошення {@link Announcement}
     * @return {@code true}, якщо запит виконано без помилок, інакше {@code false}
     */
    public static boolean saveAnnouncement(Announcement a) {
        String sql = """
            INSERT OR IGNORE INTO olx_announcements
                (id, city, category, url, title, price_value, price_currency,
                 location, date_published, seller, phone, description,
                 rooms_count, total_area, floor, sent_to_telegram)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
        """;

        try (Connection conn = DatabaseManager.getConnection()) {
            // Вмикаємо ручне керування транзакцією
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1,  a.getId());
                ps.setString(2,  a.getCity()     != null ? a.getCity().getLabel()     : "");
                ps.setString(3,  a.getCategory() != null ? a.getCategory().getLabel() : "");
                ps.setString(4,  a.getUrl());
                ps.setString(5,  a.getTitle());

                BigDecimal pv = a.getPriceValue();
                if (pv != null) {
                    ps.setDouble(6, pv.doubleValue());
                } else {
                    ps.setNull(6, Types.REAL);
                }

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

            // Додаткові сутності (фото та параметри) пишемо тільки тоді, коли рядок дійсно вставився.
            // Функція changes() у SQLite повертає кількість рядків, змінених останнім запитом.
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT changes()")) {
                if (rs.next() && rs.getInt(1) > 0) {
                    savePhotos(a, conn);
                    saveParams(a, conn);
                }
            }

            conn.commit(); // Фіксуємо транзакцію
            return true;

        } catch (SQLException e) {
            System.err.println("❌ Помилка запису ID=" + a.getId() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Пакетно зберігає список URL фотографій для конкретного оголошення.
     */
    private static void savePhotos(Announcement a, Connection conn) throws SQLException {
        if (a.getPhotos() == null || a.getPhotos().isEmpty()) return;

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO olx_photos (announcement_id, photo_url, sort_order) VALUES (?,?,?)")) {
            for (int i = 0; i < a.getPhotos().size(); i++) {
                ps.setString(1, a.getId());
                ps.setString(2, a.getPhotos().get(i));
                ps.setInt   (3, i);
                ps.addBatch(); // Додаємо в пакет
            }
            ps.executeBatch(); // Виконуємо пакетно
        }
    }

    /**
     * Пакетно зберігає текстові параметри (характеристики) для конкретного оголошення.
     */
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

    // ── TELEGRAM СТАТУСИ ТА ВІДПРАВКА ─────────────────────────────────────────

    /**
     * Позначає оголошення як відправлене у Telegram-канал (встановлює флаг {@code sent_to_telegram = 1}).
     * Викликається лише після підтвердження успішної відправки через Telegram API.
     *
     * @param id унікальний ідентифікатор оголошення OLX
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
     * Видаляє оголошення OLX пакетом у межах однієї транзакції.
     * Пов'язані фото й параметри прибираються каскадно через зовнішні ключі.
     *
     * @return {@code true}, якщо транзакція успішно зафіксована
     */
    public static synchronized boolean deleteOlxAnnouncements(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return true;

        Connection conn = DatabaseManager.getConnection();
        try {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM olx_announcements WHERE id = ?")) {
                for (String id : ids) {
                    ps.setString(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            conn.setAutoCommit(autoCommit);
            return true;
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ignored) { }
            System.err.println("❌ deleteOlxAnnouncements: " + e.getMessage());
            return false;
        }
    }

    /**
     * Повертає список оголошень, які ще не були опубліковані у Telegram-каналі.
     * Використовується демоном розсилки для відправки накопичених нових записів.
     *
     * @return список невідправлених об'єктів {@link Announcement}, відсортованих від старіших до новіших
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

                // Зворотний мапінг рядків з БД у відповідні Enum об'єкти системи
                City city = City.CHERNIVTSI;
                for (City c : City.values()) {
                    if (c.getLabel().equalsIgnoreCase(cityStr)) {
                        city = c;
                        break;
                    }
                }

                AnnouncementCategory cat = AnnouncementCategory.RENT_LONG;
                for (AnnouncementCategory cl : AnnouncementCategory.values()) {
                    if (cl.getLabel().equalsIgnoreCase(catStr)) {
                        cat = cl;
                        break;
                    }
                }

                Announcement ad = new Announcement(
                        id, rs.getString("url"), rs.getString("title"),
                        "", rs.getString("location"), city, cat
                );

                double pv = rs.getDouble("price_value");
                if (!rs.wasNull()) {
                    ad.setPriceValue(BigDecimal.valueOf(pv));
                }
                ad.setPriceCurrency(rs.getString("price_currency"));
                ad.setDatePublished(rs.getString("date_published"));
                ad.setSeller(rs.getString("seller"));
                ad.setPhone(rs.getString("phone"));
                ad.setDescription(rs.getString("description"));

                // Завантаження пов'язаних колекцій
                ad.setPhotos(getPhotosForAnnouncement(id, conn));
                ad.setParams(getParamsForAnnouncement(id, conn));

                list.add(ad);
            }
        } catch (SQLException e) {
            System.err.println("❌ getUnsentAnnouncements: " + e.getMessage());
        }
        return list;
    }

    // ── РОБОТА З ПРОФІЛЯМИ КОРИСТУВАЧІВ (WIZARD-АНКЕТИ) ───────────────────────

    /**
     * Зберігає або повністю перезаписує анкету клієнта на пошук нерухомості.
     * <p>
     * Оскільки один користувач може мати лише одну активну анкету, використовується конструкція
     * {@code INSERT OR REPLACE} на базі унікального {@code telegram_id} (первинний ключ).
     * </p>
     *
     * @param form заповнений об'єкт анкети {@link TenantApplicationForm}
     * @return {@code true}, якщо запис пройшов успішно, інакше {@code false}
     */
    public static boolean saveOrUpdateProfile(TenantApplicationForm form) {
        String sql = """
            INSERT OR REPLACE INTO user_profiles
                (telegram_id, user_name, phone_number, budget, ready_for_deposit, ready_for_commission,
                 preferred_districts, rooms_type, rent_term, tenants_count,
                 tenants_description, has_children, children_info, has_pets,
                 pets_info, employment_sphere, work_format, smoking_status,
                 has_car, critical_requirements)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """;

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong   (1,  form.getTelegramId());
            ps.setString (2,  form.getUserName());
            ps.setString (3,  form.getPhoneNumber());
            ps.setInt    (4,  form.getBudget());
            ps.setBoolean(5,  form.isReadyForDeposit());
            ps.setBoolean(6,  form.isReadyForCommission());
            ps.setString (7,  form.getPreferredDistricts());
            ps.setString (8,  form.getRoomsType());
            ps.setString (9,  form.getRentTerm());
            ps.setInt    (10, form.getTenantsCount());
            ps.setString (11, form.getTenantsDescription());
            ps.setBoolean(12, form.isHasChildren());
            ps.setString (13, form.getChildrenInfo());
            ps.setBoolean(14, form.isHasPets());
            ps.setString (15, form.getPetsInfo());
            ps.setString (16, form.getEmploymentSphere());
            ps.setString (17, form.getWorkFormat());
            ps.setString (18, form.getSmokingStatus());
            ps.setBoolean(19, form.isHasCar());
            ps.setString (20, form.getCriticalRequirements());

            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            System.err.println("❌ Помилка збереження анкети telegram_id=" + form.getTelegramId() + ": " + e.getMessage());
            return false;
        }
    }

    // ── РОБОТА З ОГОЛОШЕННЯМИ, СТВОРЕНИМИ КОРИСТУВАЧАМИ (CreatePostWizardController) ──

    /**
     * Зберігає завершену анкету майстра "Створити оголошення" у таблицю {@code user_created_posts}
     * разом із прикріпленими фотографіями, у межах однієї транзакції.
     * <p>
     * Первинним ключем виступає унікальний {@code paymentCode} чернетки (6-символьний код),
     * що згенерований на кроці формування екрану резюме — саме цей код користувач має вказати
     * в призначенні платежу, і саме за ним надалі звіряється надходження оплати.
     * </p>
     *
     * @param telegramId telegram-ідентифікатор користувача, який створив оголошення
     * @param draft      заповнена чернетка {@link CreatePostDraft} з кодом платежу та статусом {@code PENDING}
     * @return {@code true}, якщо запис і фото збережено без помилок, інакше {@code false}
     */
    public static boolean saveUserCreatedPost(long telegramId, CreatePostDraft draft) {
        if (draft == null || draft.getPaymentCode() == null || draft.getPaymentCode().isBlank()) {
            System.err.println("❌ saveUserCreatedPost: відсутній paymentCode у чернетці.");
            return false;
        }

        String sql = """
            INSERT OR REPLACE INTO user_created_posts
                (payment_code, telegram_id, deal_type, property_type, market_type, title,
                 price_value, price_currency, location, land_area, rooms,
                 total_area, floor, total_floors, seller_type, duration_days, description,
                 phone_number, listing_fee_uah, payment_status)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """;

        try (Connection conn = DatabaseManager.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, draft.getPaymentCode());
                ps.setLong  (2, telegramId);
                ps.setString(3, draft.getDealType()     != null ? draft.getDealType().name()     : null);
                ps.setString(4, draft.getPropertyType()  != null ? draft.getPropertyType().name() : null);
                ps.setString(5, draft.getMarketType()    != null ? draft.getMarketType().name()   : null);
                ps.setString(6, draft.getTitle());

                if (draft.getPrice() != null) {
                    ps.setDouble(7, draft.getPrice().doubleValue());
                } else {
                    ps.setNull(7, Types.REAL);
                }
                ps.setString(8, draft.getCurrency() != null ? draft.getCurrency().getLabel() : null);
                ps.setString(9, draft.getLocation());

                if (draft.getLandArea() != null) ps.setDouble(10, draft.getLandArea()); else ps.setNull(10, Types.REAL);
                if (draft.getRooms()    != null) ps.setInt   (11, draft.getRooms());   else ps.setNull(11, Types.INTEGER);
                if (draft.getTotalArea()!= null) ps.setDouble(12, draft.getTotalArea()); else ps.setNull(12, Types.REAL);
                if (draft.getFloor()    != null) ps.setInt   (13, draft.getFloor());   else ps.setNull(13, Types.INTEGER);
                if (draft.getTotalFloors() != null) ps.setInt(14, draft.getTotalFloors()); else ps.setNull(14, Types.INTEGER);

                ps.setString(15, draft.getSellerType() != null ? draft.getSellerType().name() : null);
                if (draft.getDurationDays() != null) ps.setInt(16, draft.getDurationDays()); else ps.setNull(16, Types.INTEGER);
                ps.setString(17, draft.getDescription());
                ps.setString(18, draft.getPhoneNumber());
                ps.setInt   (19, draft.calculateListingFeeUah());
                ps.setString(20, draft.getPaymentStatus() != null ? draft.getPaymentStatus().name() : "PENDING");

                ps.executeUpdate();
            }

            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM user_created_post_photos WHERE payment_code = ?")) {
                del.setString(1, draft.getPaymentCode());
                del.executeUpdate();
            }
            saveUserCreatedPostPhotos(draft, conn);

            conn.commit();
            return true;

        } catch (SQLException e) {
            System.err.println("❌ Помилка збереження оголошення користувача code=" + draft.getPaymentCode() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Пакетно зберігає фотографії (Telegram {@code file_id}), прикріплені до створеного користувачем оголошення.
     */
    private static void saveUserCreatedPostPhotos(CreatePostDraft draft, Connection conn) throws SQLException {
        if (draft.getPhotoFileIds() == null || draft.getPhotoFileIds().isEmpty()) return;

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO user_created_post_photos (payment_code, photo_file_id, sort_order) VALUES (?,?,?)")) {
            for (int i = 0; i < draft.getPhotoFileIds().size(); i++) {
                ps.setString(1, draft.getPaymentCode());
                ps.setString(2, draft.getPhotoFileIds().get(i));
                ps.setInt   (3, i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Позначає оголошення користувача (за кодом платежу) як оплачене.
     * Викликається адміністратором вручну (наприклад, з окремої адмін-команди) після
     * звірки надходження коштів з призначенням платежу, що містить {@code paymentCode}.
     *
     * @param paymentCode 6-символьний код платежу, згенерований для чернетки
     * @return {@code true}, якщо рядок знайдено й оновлено
     */
    public static boolean markUserPostAsPaid(String paymentCode) {
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE user_created_posts SET payment_status = 'PAID' WHERE payment_code = ?")) {
            ps.setString(1, paymentCode);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ markUserPostAsPaid code=" + paymentCode + ": " + e.getMessage());
            return false;
        }
    }

    // ── СТАТИСТИКА ТА ПЕРЕВІРКА ІСНУВАННЯ ─────────────────────────────────────

    /**
     * Перевіряє наявність оголошення в локальній базі за його ID.
     *
     * @param id унікальний ідентифікатор оголошення (наприклад, "841234123")
     * @return {@code true}, якщо оголошення вже є в базі, інакше {@code false}
     */
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

    /**
     * Повертає загальну кількість оголошень, збережених у системі.
     *
     * @return загальна кількість записів, або {@code -1} у разі помилки
     */
    public static int countAll() {
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM olx_announcements")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return -1;
        }
    }

    // ── ФІЛЬТРАЦІЯ ДЛЯ ТЕЛЕГРАМ БОТА ─────────────────────────────────────────

    /**
     * Пошук оголошень за складною системою фільтрації (місто, категорія, кількість кімнат, ціновий діапазон).
     * <p>
     * Фільтрація за кімнатами та ціною здійснюється динамічно в оперативній пам'яті:
     * <ul>
     * <li>Підтримується фільтр "точний збіг" або "мінімум N кімнат" (для вибору 3+).</li>
     * <li>Підтримується автоматична конвертація гривневих цін у USD за актуальним курсом ПриватБанку для коректної фільтрації діапазонів.</li>
     * </ul>
     * </p>
     *
     * @param session поточна активна сесія користувача {@link UserSession} з налаштованими фільтрами
     * @return список знайдених та відфільтрованих оголошень {@link Announcement}
     */
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
                    AnnouncementCategory cat = session.getSelectedCategory();

                    Announcement ad = new Announcement(
                            id, rs.getString("url"), rs.getString("title"),
                            "", rs.getString("location"), city, cat
                    );

                    double pv = rs.getDouble("price_value");
                    String currency = rs.getString("price_currency");
                    if (!rs.wasNull()) {
                        ad.setPriceValue(BigDecimal.valueOf(pv));
                    }
                    ad.setPriceCurrency(currency);
                    ad.setDatePublished(rs.getString("date_published"));
                    ad.setSeller(rs.getString("seller"));
                    ad.setPhone(rs.getString("phone"));
                    ad.setDescription(rs.getString("description"));

                    // Завантаження фото та характеристик
                    ad.setPhotos(getPhotosForAnnouncement(id, conn));
                    ad.setParams(getParamsForAnnouncement(id, conn));

                    // 1. Фільтр по кімнатах
                    if (session.getSelectedRooms() != null) {
                        if (session.isRoomsIsMinimum()) {
                            if (ad.getRoomsCount() < session.getSelectedRooms()) continue;
                        } else {
                            if (ad.getRoomsCount() != session.getSelectedRooms()) continue;
                        }
                    }

                    // 2. Фільтр по бюджету в доларах (з урахуванням конвертації грн -> usd)
                    if (session.getMinPriceUsd() != null && ad.getPriceValue() != null) {
                        int priceUsd;
                        if (currency != null && (currency.equalsIgnoreCase("usd") || currency.equalsIgnoreCase("$"))) {
                            priceUsd = ad.getPriceValue().intValue();
                        } else {
                            // Конвертуємо гривневу ціну в еквівалент USD через API ПриватБанку
                            priceUsd = PrivatBankRateService.convertUahToUsd(ad.getPriceValue()).intValue();
                        }
                        // Перевіряємо вхід у задані межі бюджету
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

    // ── ЗБЕРЕЖЕНІ ФІЛЬТРИ ТА ПЕРСОНАЛЬНІ СПОВІЩЕННЯ ─────────────────────────

    public static void upsertTelegramUser(long telegramId, boolean subscribed) {
        String sql = """
            INSERT INTO telegram_users (telegram_id, is_subscribed, updated_at) VALUES (?, ?, datetime('now'))
            ON CONFLICT(telegram_id) DO UPDATE SET is_subscribed = excluded.is_subscribed, updated_at = datetime('now')
        """;
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            ps.setBoolean(2, subscribed);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("❌ upsertTelegramUser: " + e.getMessage());
        }
    }

    /** Зберігає поточний пошук як єдиний активний фільтр користувача та вмикає сповіщення. */
    public static boolean saveSearchFilter(long telegramId, UserSession session) {
        if (session.getSelectedCity() == null || session.getSelectedCategory() == null) return false;
        upsertTelegramUser(telegramId, true);
        String sql = """
            INSERT INTO saved_search_filters
                (telegram_id, deal_type, property_type, city, category, rooms, rooms_minimum,
                 min_price_usd, max_price_usd, notifications_enabled, last_notified_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, datetime('now'), datetime('now'))
            ON CONFLICT(telegram_id) DO UPDATE SET
                deal_type=excluded.deal_type, property_type=excluded.property_type, city=excluded.city,
                category=excluded.category, rooms=excluded.rooms, rooms_minimum=excluded.rooms_minimum,
                min_price_usd=excluded.min_price_usd, max_price_usd=excluded.max_price_usd,
                notifications_enabled=1, last_notified_at=datetime('now'), updated_at=datetime('now')
        """;
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            ps.setString(2, nameOf(session.getSelectedDealType()));
            ps.setString(3, nameOf(session.getSelectedPropertyType()));
            ps.setString(4, session.getSelectedCity().name());
            ps.setString(5, session.getSelectedCategory().name());
            setNullableInt(ps, 6, session.getSelectedRooms());
            ps.setBoolean(7, session.isRoomsIsMinimum());
            setNullableInt(ps, 8, session.getMinPriceUsd());
            setNullableInt(ps, 9, session.getMaxPriceUsd());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ saveSearchFilter: " + e.getMessage());
            return false;
        }
    }

    public static Optional<SavedSearchFilter> getSavedSearchFilter(long telegramId) {
        String sql = "SELECT * FROM saved_search_filters WHERE telegram_id = ?";
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readSavedFilter(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            System.err.println("❌ getSavedSearchFilter: " + e.getMessage());
            return Optional.empty();
        }
    }

    public static List<SavedSearchFilter> getEnabledSubscribedFilters() {
        List<SavedSearchFilter> filters = new ArrayList<>();
        String sql = """
            SELECT f.* FROM saved_search_filters f
            JOIN telegram_users u ON u.telegram_id = f.telegram_id
            WHERE f.notifications_enabled = 1 AND u.is_subscribed = 1
        """;
        try (Connection conn = DatabaseManager.getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) filters.add(readSavedFilter(rs));
        } catch (SQLException e) {
            System.err.println("❌ getEnabledSubscribedFilters: " + e.getMessage());
        }
        return filters;
    }

    public static boolean setNotificationsEnabled(long telegramId, boolean enabled) {
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE saved_search_filters SET notifications_enabled = ?, " +
                        "last_notified_at = CASE WHEN ? = 1 THEN datetime('now') ELSE last_notified_at END, " +
                        "updated_at = datetime('now') WHERE telegram_id = ?")) {
            ps.setBoolean(1, enabled);
            ps.setBoolean(2, enabled);
            ps.setLong(3, telegramId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ setNotificationsEnabled: " + e.getMessage());
            return false;
        }
    }

    public static boolean deleteSavedSearchFilter(long telegramId) {
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM saved_search_filters WHERE telegram_id = ?")) {
            ps.setLong(1, telegramId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ deleteSavedSearchFilter: " + e.getMessage());
            return false;
        }
    }

    public static void markFilterChecked(long telegramId) {
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE saved_search_filters SET last_notified_at = datetime('now') WHERE telegram_id = ?")) {
            ps.setLong(1, telegramId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("❌ markFilterChecked: " + e.getMessage());
        }
    }

    /** Повертає лише оголошення, що з'явилися після останньої успішної перевірки фільтра. */
    public static List<Announcement> getNewAnnouncementsForFilter(SavedSearchFilter filter) {
        List<Announcement> results = new ArrayList<>();
        if (filter.getCity() == null || filter.getCategory() == null) return results;
        String sql = """
            SELECT id, url, title, price_value, price_currency, location, date_published, seller, phone, description
            FROM olx_announcements
            WHERE city = ? AND category = ?
              AND created_at > COALESCE(
                    (SELECT last_notified_at FROM saved_search_filters WHERE telegram_id = ?),
                    datetime('now', '-1 hour'))
            ORDER BY created_at ASC
        """;
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, filter.getCity().getLabel());
            ps.setString(2, filter.getCategory().getLabel());
            ps.setLong(3, filter.getTelegramId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Announcement ad = new Announcement(rs.getString("id"), rs.getString("url"), rs.getString("title"), "",
                            rs.getString("location"), filter.getCity(), filter.getCategory());
                    double price = rs.getDouble("price_value");
                    if (!rs.wasNull()) ad.setPriceValue(BigDecimal.valueOf(price));
                    ad.setPriceCurrency(rs.getString("price_currency"));
                    ad.setDatePublished(rs.getString("date_published"));
                    ad.setSeller(rs.getString("seller"));
                    ad.setPhone(rs.getString("phone"));
                    ad.setDescription(rs.getString("description"));
                    ad.setPhotos(getPhotosForAnnouncement(ad.getId(), conn));
                    ad.setParams(getParamsForAnnouncement(ad.getId(), conn));
                    if (matchesFilter(ad, filter)) results.add(ad);
                }
            }
        } catch (SQLException e) {
            System.err.println("❌ getNewAnnouncementsForFilter: " + e.getMessage());
        }
        return results;
    }

    /** Замінює попередню непроглянуту чергу новим набором знайдених оголошень. */
    public static boolean saveNotificationBatch(long telegramId, List<Announcement> announcements) {
        if (announcements == null || announcements.isEmpty()) return false;
        String ids = announcements.stream().map(Announcement::getId).collect(java.util.stream.Collectors.joining("\n"));
        String sql = """
            INSERT INTO pending_notification_batches (telegram_id, announcement_ids, current_offset, created_at)
            VALUES (?, ?, 0, datetime('now'))
            ON CONFLICT(telegram_id) DO UPDATE SET announcement_ids=excluded.announcement_ids, current_offset=0, created_at=datetime('now')
        """;
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, telegramId);
            ps.setString(2, ids);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("❌ saveNotificationBatch: " + e.getMessage());
            return false;
        }
    }

    public static Optional<NotificationBatch> getNotificationBatch(long telegramId) {
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "SELECT announcement_ids, current_offset FROM pending_notification_batches WHERE telegram_id = ?")) {
            ps.setLong(1, telegramId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String raw = rs.getString("announcement_ids");
                List<String> ids = raw == null || raw.isBlank() ? List.of() : List.of(raw.split("\\R"));
                return Optional.of(new NotificationBatch(ids, rs.getInt("current_offset")));
            }
        } catch (SQLException e) {
            System.err.println("❌ getNotificationBatch: " + e.getMessage());
            return Optional.empty();
        }
    }

    public static void updateNotificationBatchOffset(long telegramId, int offset) {
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE pending_notification_batches SET current_offset = ? WHERE telegram_id = ?")) {
            ps.setInt(1, offset);
            ps.setLong(2, telegramId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("❌ updateNotificationBatchOffset: " + e.getMessage());
        }
    }

    public static void deleteNotificationBatch(long telegramId) {
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM pending_notification_batches WHERE telegram_id = ?")) {
            ps.setLong(1, telegramId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("❌ deleteNotificationBatch: " + e.getMessage());
        }
    }

    /** Завантажує оголошення за збереженими ID у точно тому самому порядку. */
    public static List<Announcement> getAnnouncementsByIds(List<String> ids) {
        List<Announcement> results = new ArrayList<>();
        if (ids == null || ids.isEmpty()) return results;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT id, city, category, url, title, price_value, price_currency, location, date_published, seller, phone, description "
                + "FROM olx_announcements WHERE id IN (" + placeholders + ")";
        java.util.Map<String, Announcement> byId = new java.util.HashMap<>();
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    City city = cityFromDatabase(rs.getString("city"));
                    AnnouncementCategory category = categoryFromDatabase(rs.getString("category"));
                    Announcement ad = new Announcement(rs.getString("id"), rs.getString("url"), rs.getString("title"), "",
                            rs.getString("location"), city, category);
                    double price = rs.getDouble("price_value");
                    if (!rs.wasNull()) ad.setPriceValue(BigDecimal.valueOf(price));
                    ad.setPriceCurrency(rs.getString("price_currency"));
                    ad.setDatePublished(rs.getString("date_published"));
                    ad.setSeller(rs.getString("seller"));
                    ad.setPhone(rs.getString("phone"));
                    ad.setDescription(rs.getString("description"));
                    ad.setPhotos(getPhotosForAnnouncement(ad.getId(), conn));
                    ad.setParams(getParamsForAnnouncement(ad.getId(), conn));
                    byId.put(ad.getId(), ad);
                }
            }
        } catch (SQLException e) {
            System.err.println("❌ getAnnouncementsByIds: " + e.getMessage());
        }
        for (String id : ids) if (byId.containsKey(id)) results.add(byId.get(id));
        return results;
    }

    private static boolean matchesFilter(Announcement ad, SavedSearchFilter filter) {
        if (filter.getRooms() != null) {
            int rooms = ad.getRoomsCount();
            if (filter.isRoomsMinimum() ? rooms < filter.getRooms() : rooms != filter.getRooms()) return false;
        }
        if (filter.getMinPriceUsd() == null || ad.getPriceValue() == null) return true;
        BigDecimal usd = ad.getPriceCurrency() != null && (ad.getPriceCurrency().equalsIgnoreCase("usd") || ad.getPriceCurrency().equals("$"))
                ? ad.getPriceValue() : PrivatBankRateService.convertUahToUsd(ad.getPriceValue());
        return usd.intValue() >= filter.getMinPriceUsd() && (filter.getMaxPriceUsd() == null || usd.intValue() <= filter.getMaxPriceUsd());
    }

    private static SavedSearchFilter readSavedFilter(ResultSet rs) throws SQLException {
        SavedSearchFilter filter = new SavedSearchFilter();
        filter.setTelegramId(rs.getLong("telegram_id"));
        filter.setDealType(enumValue(DealType.class, rs.getString("deal_type")));
        filter.setPropertyType(enumValue(PropertyType.class, rs.getString("property_type")));
        filter.setCity(enumValue(City.class, rs.getString("city")));
        filter.setCategory(enumValue(AnnouncementCategory.class, rs.getString("category")));
        int rooms = rs.getInt("rooms"); filter.setRooms(rs.wasNull() ? null : rooms);
        filter.setRoomsMinimum(rs.getBoolean("rooms_minimum"));
        int min = rs.getInt("min_price_usd"); filter.setMinPriceUsd(rs.wasNull() ? null : min);
        int max = rs.getInt("max_price_usd"); filter.setMaxPriceUsd(rs.wasNull() ? null : max);
        filter.setNotificationsEnabled(rs.getBoolean("notifications_enabled"));
        return filter;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try { return value == null ? null : Enum.valueOf(type, value); } catch (IllegalArgumentException e) { return null; }
    }

    private static City cityFromDatabase(String value) {
        for (City city : City.values()) if (city.getLabel().equalsIgnoreCase(value)) return city;
        return City.CHERNIVTSI;
    }

    private static AnnouncementCategory categoryFromDatabase(String value) {
        for (AnnouncementCategory category : AnnouncementCategory.values()) {
            if (category.getLabel().equalsIgnoreCase(value)) return category;
        }
        return AnnouncementCategory.RENT_LONG;
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER); else ps.setInt(index, value);
    }

    private static String nameOf(Enum<?> value) { return value == null ? null : value.name(); }

    // ── ПРИВАТНІ ДОПОМІЖНІ МЕТОДИ (HELPER METHODS) ───────────────────────────

    /**
     * Отримує список фотографій для конкретного оголошення.
     */
    private static List<String> getPhotosForAnnouncement(String id, Connection conn) {
        List<String> photos = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT photo_url FROM olx_photos WHERE announcement_id = ? ORDER BY sort_order ASC")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    photos.add(rs.getString("photo_url"));
                }
            }
        } catch (SQLException e) {
            System.err.println("⚠️ Фото для ID=" + id + ": " + e.getMessage());
        }
        return photos;
    }

    /**
     * Отримує список текстових параметрів для конкретного оголошення.
     */
    private static List<String> getParamsForAnnouncement(String id, Connection conn) {
        List<String> params = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT param_text FROM olx_params WHERE announcement_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    params.add(rs.getString("param_text"));
                }
            }
        } catch (SQLException e) {
            System.err.println("⚠️ Параметри для ID=" + id + ": " + e.getMessage());
        }
        return params;
    }

    // ── МЕТОДИ ДЛЯ ТАБЛИЦІ monitored_groups_telegram ──────────────────────────


    /**
     * Оновлює ID останнього успішно обробленого повідомлення у конкретній групі.
     * <p>
     * Запобігає повторному парсингу та відправці дублікатів повідомлень після перезапуску боту.
     * </p>
     *
     * @param chatId        унікальний ідентифікатор чату групи Telegram
     * @param lastMessageId унікальний ID останнього повідомлення
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
     * Додає нову Telegram групу чи канал до списку моніторингу системи.
     * <p>
     * Якщо група з таким {@code chat_id} вже присутня в системі, запис буде проігноровано
     * завдяки {@code INSERT OR IGNORE}.
     * </p>
     *
     * @param chatId    ідентифікатор чату Telegram (зазвичай починається з мінуса для груп)
     * @param groupName зрозуміла назва групи/каналу для адміністратора
     * @return {@code true}, якщо додано новий рядок, або {@code false}, якщо групу проігноровано чи виникла помилка
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

    /**
     * Виконує повне сканування таблиці оголошень та видаляє записи, посилання на які стали неактивними (повертають 404).
     * <p>
     * Метод ітерує по всій таблиці {@code olx_announcements}, перевіряє доступність кожного URL та
     * формує пакетний запит на видалення. Використовує каскадне видалення через зовнішні ключі,
     * тому фото та параметри оголошення видаляються автоматично.
     * </p>
     *
     * @return звіт про виконання у вигляді рядка з кількістю перевірених та видалених записів,
     *         або повідомлення про помилку у разі виникнення {@link SQLException}
     */
    public static String cleanupBrokenLinks() {
        int checkedCount = 0;
        int deletedCount = 0;
        String sqlSelect = "SELECT id, url FROM olx_announcements";
        String sqlDelete = "DELETE FROM olx_announcements WHERE id = ?";

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sqlSelect)) {

            List<String> idsToDelete = new ArrayList<>();

            while (rs.next()) {
                checkedCount++;
                String id = rs.getString("id");
                String url = rs.getString("url");

                if (!isUrlAlive(url)) {
                    idsToDelete.add(id);
                    deletedCount++;
                }
            }

            // Видалення знайдених "битих" записів
            try (PreparedStatement ps = conn.prepareStatement(sqlDelete)) {
                for (String id : idsToDelete) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
            }

        } catch (SQLException e) {
            return "Помилка при очищенні БД: " + e.getMessage();
        }

        return String.format("Перевірено елементів: %d. Видалено битих посилань: %d.", checkedCount, deletedCount);
    }

    /**
     * Перевіряє актуальність посилання за допомогою HTTP GET-запиту.
     * <p>
     * Встановлює ліміт часу (timeout) 5 секунд для з'єднання та читання відповіді,
     * щоб уникнути зависання потоку при тривалих відповідях сервера.
     * </p>
     *
     * @param urlString повний URL оголошення для перевірки
     * @return {@code true}, якщо сервер відповів кодом відмінним від 404;
     *         {@code false}, якщо отримано 404 або виникла мережева помилка (таймаут, DNS тощо)
     */
    private static boolean isUrlAlive(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000); // 5 секунд на з'єднання
            connection.setReadTimeout(5000);
            int responseCode = connection.getResponseCode();
            return responseCode != 404;
        } catch (Exception e) {
            // Вважаємо посилання "битим" у разі виникнення будь-яких мережевих виключень
            return false;
        }
    }
}
