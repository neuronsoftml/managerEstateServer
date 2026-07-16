package model;

import core.tools.parser.olx.AnnouncementParamParser;
import core.tools.parser.olx.ParserPrice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;


/**
 * Модель оголошення з платформи OLX.
 * Чиста DTO сутність з безпечним делегуванням логіки парсингу параметрів.
 * @author Mykola
 */
public class Announcement {

    private String id;
    private City city;
    private AnnouncementCategory category;
    private String url;
    private String title;
    private String priceRaw;
    private BigDecimal priceValue;
    private String priceCurrency;
    private String location;
    private String datePublished;
    private String seller;
    private String phone;
    private String description;
    private List<String> params;
    private List<String> photos;

    // ── Конструктор 1 (для сирих рядків з парсера) ───────────────────────────
    public Announcement(String id, String cityRaw, String categoryRaw, String url,
                        String title, String priceRaw, String location,
                        String datePublished, String seller, String phone,
                        String description, List<String> params, List<String> photos) {
        this.id            = id;
        this.city          = City.findByRawString(cityRaw);
        this.category      = AnnouncementCategory.findByRawString(categoryRaw);
        this.url           = url;
        this.title         = title;
        this.priceRaw      = priceRaw;
        this.location      = location;
        this.datePublished = datePublished;
        this.seller        = seller;
        this.phone         = phone;
        this.description   = description;
        this.params        = params != null ? params : new ArrayList<>();
        this.photos        = photos != null ? photos : new ArrayList<>();

        updatePriceFields(priceRaw);
    }

    // ── Конструктор 2 (короткий варіант для прев'ю-карток) ────────────────────
    public Announcement(String id, String url, String title, String priceRaw, String location,
                        City city, AnnouncementCategory category) {
        this.id            = id;
        this.url           = url;
        this.title         = title;
        this.priceRaw      = priceRaw;
        this.location      = location;
        this.city          = city;
        this.category      = category;
        this.params        = new ArrayList<>();
        this.photos        = new ArrayList<>();

        updatePriceFields(priceRaw);
    }

    // ── Конструктор 3 (повний, з готовими об'єктами Enum без зайвого парсингу) ──
    public Announcement(String id, City city, AnnouncementCategory category, String url,
                        String title, String priceRaw, String location,
                        String datePublished, String seller, String phone,
                        String description, List<String> params, List<String> photos) {
        this.id            = id;
        this.city          = city;
        this.category      = category;
        this.url           = url;
        this.title         = title;
        this.priceRaw      = priceRaw;
        this.location      = location;
        this.datePublished = datePublished;
        this.seller        = seller;
        this.phone         = phone;
        this.description   = description;
        this.params        = params != null ? params : new ArrayList<>();
        this.photos        = photos != null ? photos : new ArrayList<>();

        updatePriceFields(priceRaw);
    }

    /**
     * Парсить сирий рядок ціни та заповнює обчислені поля ціни й валюти.
     */
    private void updatePriceFields(String rawPrice) {
        if (rawPrice == null || rawPrice.isEmpty()) {
            this.priceValue = null;
            this.priceCurrency = "UAH"; // валюта за замовчуванням
            return;
        }
        try {
            ParserPrice.PriceParser.PriceResult result = ParserPrice.PriceParser.parse(rawPrice);
            this.priceValue = result.getValue();
            this.priceCurrency = result.getCurrency();
        } catch (Exception e) {
            // Безпечний fallback на випадок неочікуваного формату ціни
            this.priceValue = null;
            this.priceCurrency = null;
        }
    }

    // ── Делеговані методи (безпечні до NullPointerException) ───────────────────

    /**
     * Повертає лінк на перше (головне) фото оголошення.
     */
    public String getMainPhoto() {
        return (photos != null && !photos.isEmpty()) ? photos.get(0) : "";
    }

    /**
     * Делегує парсинг кількості кімнат. Безпечний до null.
     */
    public int getRoomsCount() {
        return AnnouncementParamParser.getRoomsCount(this.params != null ? this.params : new ArrayList<>());
    }

    /**
     * Делегує парсинг загальної площі об'єкта. Безпечний до null.
     */
    public double getTotalArea() {
        return AnnouncementParamParser.getTotalArea(this.params != null ? this.params : new ArrayList<>());
    }

    /**
     * Делегує парсинг поверху розташування. Безпечний до null.
     */
    public int getFloor() {
        return AnnouncementParamParser.getFloor(this.params != null ? this.params : new ArrayList<>());
    }

    // ── Геттери та Сеттери ───────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public City getCity() { return city; }
    public void setCity(City city) { this.city = city; }

    public AnnouncementCategory getCategory() { return category; }
    public void setCategory(AnnouncementCategory category) { this.category = category; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getPriceRaw() { return priceRaw; }
    public void setPriceRaw(String priceRaw) {
        this.priceRaw = priceRaw;
        updatePriceFields(priceRaw);
    }

    public BigDecimal getPriceValue() { return priceValue; }
    public void setPriceValue(BigDecimal priceValue) { this.priceValue = priceValue; }

    public String getPriceCurrency() { return priceCurrency; }
    public void setPriceCurrency(String priceCurrency) { this.priceCurrency = priceCurrency; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getDatePublished() { return datePublished; }
    public void setDatePublished(String datePublished) { this.datePublished = datePublished; }

    public String getSeller() { return seller; }
    public void setSeller(String seller) { this.seller = seller; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getParams() { return params; }
    public void setParams(List<String> params) {
        this.params = params != null ? params : new ArrayList<>();
    }

    public List<String> getPhotos() { return photos; }
    public void setPhotos(List<String> photos) {
        this.photos = photos != null ? photos : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "Announcement{" +
                "id='" + id + '\'' +
                ", city=" + (city != null ? city.getLabel() : "null") +
                ", category=" + (category != null ? category.getLabel() : "null") +
                ", title='" + title + '\'' +
                ", price=" + priceRaw +
                ", rooms=" + getRoomsCount() +
                ", area=" + getTotalArea() +
                ", floor=" + getFloor() +
                ", photos=" + (photos != null ? photos.size() : 0) +
                '}';
    }
}