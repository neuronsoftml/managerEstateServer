package sqlite;
import core.telegram.model.TenantApplicationForm;
import core.tools.PrivatBankRateService;
import model.Announcement;
import model.AnnouncementCategory;
import model.City;
import core.telegram.model.UserSession;


import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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

            // 6. Створення індексів для оптимізації вибірок за основними фільтрами пошуку
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_city          ON olx_announcements(city)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_category      ON olx_announcements(category)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_price         ON olx_announcements(price_value)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_rooms         ON olx_announcements(rooms_count)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_olx_sent_telegram ON olx_announcements(sent_to_telegram)");

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
}