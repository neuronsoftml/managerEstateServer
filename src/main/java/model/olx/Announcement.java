package model.olx;

import model.City;
import model.CategoryLocation;
import model.Currency;

import java.math.BigDecimal;
import java.util.List;

/**
 * Модель оголошення з платформи OLX.
 * Відповідає структурі JSON файлу з деталями оголошення.
 */
public class Announcement {

    // Унікальний ідентифікатор оголошення (рядковий, напр. "10C65g")
    private String id;

    // Місто де розміщено оголошення
    private City city;

    // Категорія оголошення (напр. "Оренда (довгострокова)", "Продаж")
    private CategoryLocation category;

    // Посилання на сторінку оголошення
    private String url;

    // Назва оголошення
    private String title;

    // Ціна (рядок бо може бути "450 €", "12 000 грн", "Договірна")
    private String priceRaw;

    // Ціна як число для порівняння та сортування (може бути null якщо "Договірна")
    private BigDecimal priceValue;

    // Валюта ціни (UAH, USD, EUR)
    private String priceCurrency;

    // Місцезнаходження (район, вулиця)
    private String location;

    // Дата публікації оголошення
    private String datePublished;

    // Ім'я або нікнейм продавця
    private String seller;

    // Номер телефону (якщо вдалося витягти з опису)
    private String phone;

    // Опис оголошення
    private String description;

    // Параметри: поверх, площа, кількість кімнат тощо
    private List<String> params;

    // Список URL фотографій
    private List<String> photos;

    // ── Конструктор ───────────────────────────────────────────────────────────

    public Announcement(String id, String city, String category, String url,
                           String title, String priceRaw, String location,
                           String datePublished, String seller, String phone,
                           String description, List<String> params, List<String> photos) {
        this.id            = id;
        this.city          = City.findByRawString(city);
        this.category      = CategoryLocation.findByRawString(category);
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

        // Автоматично парсимо ціну при створенні
        parsePrice(priceRaw);
    }

    public Announcement(String id, String url, String title, String price, String location,
                        City city, CategoryLocation category){
        this.id = id;
        this.url = url;
        this.title = title;
        this.priceRaw = price;
        this.location = location;
        this.city = city;
        this.category = category;
    }


    // ── Парсинг ціни ──────────────────────────────────────────────────────────

    /**
     * Витягує числове значення і валюту з рядка ціни.
     * Приклади: "450 €" → 450 / EUR
     *           "12 000 грн." → 12000 / UAH
     *           "Договірна" → null / ""
     */
    private void parsePrice(String raw) {
        if (raw == null || raw.isBlank()) return;

        // Визначаємо валюту
        this.priceCurrency = Currency.parsePrice(raw);

        // Витягуємо числа (прибираємо пробіли між цифрами, крапки, коми)
        String digits = raw.replaceAll("[^\\d,.]", "")
                .replaceAll("\\s", "")
                .replace(",", ".");
        // Якщо декілька крапок — залишаємо тільки першу
        int firstDot = digits.indexOf('.');
        if (firstDot != -1) {
            digits = digits.substring(0, firstDot + 1)
                    + digits.substring(firstDot + 1).replace(".", "");
        }

        try {
            if (!digits.isEmpty()) this.priceValue = new BigDecimal(digits);
        } catch (NumberFormatException e) {
            this.priceValue = null; // "Договірна" або невідомий формат
        }
    }

    /**
     * Повертає перше фото або порожній рядок якщо фото немає.
     * Зручно для відображення обкладинки.
     */
    public String getMainPhoto() {
        return (photos != null && !photos.isEmpty()) ? photos.get(0) : "";
    }

    /**
     * Повертає кількість кімнат з params або -1 якщо не знайдено.
     */
    public int getRoomsCount() {
        if (params == null) return -1;
        for (String param : params) {
            if (param.startsWith("Кількість кімнат:")) {
                try {
                    String num = param.replaceAll("[^\\d]", "");
                    return Integer.parseInt(num);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * Повертає загальну площу з params або -1 якщо не знайдено.
     */
    public double getTotalArea() {
        if (params == null) return -1;
        for (String param : params) {
            if (param.startsWith("Загальна площа:")) {
                try {
                    String num = param.replaceAll("[^\\d.]", "");
                    return Double.parseDouble(num);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * Повертає поверх з params або -1 якщо не знайдено.
     */
    public int getFloor() {
        if (params == null) return -1;
        for (String param : params) {
            if (param.startsWith("Поверх:")) {
                try {
                    String num = param.replaceAll("[^\\d]", "");
                    return Integer.parseInt(num);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    // ── Getters and Setters ───────────────────────────────────────────────────

    public String getId()                    { return id; }
    public void setId(String id)             { this.id = id; }

    public City getCity()                  { return city; }
    public void setCity(City city)         { this.city = city;}

    public CategoryLocation getCategory()              { return category; }
    public void setCategory(CategoryLocation category) { this.category = category; }

    public String getUrl()                   { return url; }
    public void setUrl(String url)           { this.url = url; }

    public String getTitle()                 { return title; }
    public void setTitle(String title)       { this.title = title; }

    public String getPriceRaw()              { return priceRaw; }
    public void setPriceRaw(String priceRaw) {
        this.priceRaw = priceRaw;
        parsePrice(priceRaw);
    }

    public BigDecimal getPriceValue()                    { return priceValue; }
    public void setPriceValue(BigDecimal priceValue)     { this.priceValue = priceValue; }

    public String getPriceCurrency()                     { return priceCurrency; }
    public void setPriceCurrency(String priceCurrency)   { this.priceCurrency = priceCurrency; }

    public String getLocation()                          { return location; }
    public void setLocation(String location)             { this.location = location; }

    public String getDatePublished()                     { return datePublished; }
    public void setDatePublished(String datePublished)   { this.datePublished = datePublished; }

    public String getSeller()                            { return seller; }
    public void setSeller(String seller)                 { this.seller = seller; }

    public String getPhone()                             { return phone; }
    public void setPhone(String phone)                   { this.phone = phone; }

    public String getDescription()                       { return description; }
    public void setDescription(String description)       { this.description = description; }

    public List<String> getParams()                      { return params; }
    public void setParams(List<String> params)           { this.params = params; }

    public List<String> getPhotos()                      { return photos; }
    public void setPhotos(List<String> photos)           { this.photos = photos; }

    @Override
    public String toString() {
        return "OlxAnnouncement{" +
                "id='" + id + '\'' +
                ", city='" + city + '\'' +
                ", category='" + category + '\'' +
                ", title='" + title + '\'' +
                ", price=" + priceRaw +
                ", rooms=" + getRoomsCount() +
                ", area=" + getTotalArea() +
                ", floor=" + getFloor() +
                ", photos=" + (photos != null ? photos.size() : 0) +
                '}';
    }
}