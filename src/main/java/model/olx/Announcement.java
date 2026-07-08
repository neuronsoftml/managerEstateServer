package model.olx;

import core.tools.olx.AnnouncementParamParser;
import core.tools.olx.ParserPrice;
import model.City;
import model.CategoryLocation;
import model.Currency;

import java.math.BigDecimal;
import java.util.List;

/**
 * Модель оголошення з платформи OLX.
 * Чиста DTO сутність.
 */
public class Announcement {

    private String id;
    private City city;
    private CategoryLocation category;
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

    // Головний конструктор (для сирих рядків з парсера)
    public Announcement(String id, String cityRaw, String categoryRaw, String url,
                        String title, String priceRaw, String location,
                        String datePublished, String seller, String phone,
                        String description, List<String> params, List<String> photos) {
        this.id            = id;
        this.city          = City.findByRawString(cityRaw);
        this.category      = CategoryLocation.findByRawString(categoryRaw);
        this.url           = url;
        this.title         = title;
        this.priceRaw      = priceRaw;
        this.location      = location;
        this.datePublished = datePublished;
        this.seller        = seller;
        this.phone         = phone;
        this.description   = description;
        this.params        = params;
        this.photos        = photos;

        // Викликаємо винесений парсер ціни
        updatePriceFields(priceRaw);
    }

    // Другий конструктор (якщо об'єкти Enum вже готові)
    public Announcement(String id, String url, String title, String priceRaw, String location,
                        City city, CategoryLocation category){
        this.id = id;
        this.url = url;
        this.title = title;
        this.priceRaw = priceRaw;
        this.location = location;
        this.city = city;
        this.category = category;
        updatePriceFields(priceRaw);
    }

    private void updatePriceFields(String rawPrice) {
        ParserPrice.PriceParser.PriceResult result = ParserPrice.PriceParser.parse(rawPrice);
        this.priceValue = result.getValue();
        this.priceCurrency = result.getCurrency();
    }

    // ── Делеговані методи (Логіка винесена, тут тільки виклики) ─────────────────

    public String getMainPhoto() {
        return (photos != null && !photos.isEmpty()) ? photos.get(0) : "";
    }

    public int getRoomsCount() {
        return AnnouncementParamParser.getRoomsCount(this.params);
    }

    public double getTotalArea() {
        return AnnouncementParamParser.getTotalArea(this.params);
    }

    public int getFloor() {
        return AnnouncementParamParser.getFloor(this.params);
    }

    // ── Getters and Setters ───────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public City getCity() { return city; }
    public void setCity(City city) { this.city = city; }

    public CategoryLocation getCategory() { return category; }
    public void setCategory(CategoryLocation category) { this.category = category; }

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
    public void setParams(List<String> params) { this.params = params; }

    public List<String> getPhotos() { return photos; }
    public void setPhotos(List<String> photos) { this.photos = photos; }

    @Override
    public String toString() {
        return "OlxAnnouncement{" +
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