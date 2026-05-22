package model.dimRia;

import java.math.BigDecimal;

/**
 * This is a sample listing on dimRia  "Це модель оголошення на платформі dimRia"
 */
public class Announcement {

    // Unique identifier of the ad "Унікальний ідентифікатор оголошення"
    private Long id;

    // Link to the main photo "Посилання на фотографію"
    private String urlImage;

    // Ad title "Назва оголошення"
    private String title;

    // Ad description "Опис оголошення"
    private String description;

    // Price of the item/service "Ціна об'єкта оголошення"
    private BigDecimal price;

    // Name or nickname of the publisher "Автор оголошення"
    private String author;

    // Publication date of the ad "Дата публікації оголошення"
    private String date;

    // Contact telephone number "Номер телефону для зв'язку"
    private String numberPhone;

    /**
     * Constructor to create an announcement with all characteristics.
     * Конструктор для створення оголошення з усіма характеристиками.
     */
    public Announcement(Long id, String urlImage, String title, String description, BigDecimal price, String author, String date, String numberPhone) {
        this.id = id;
        this.urlImage = urlImage;
        this.title = title;
        this.description = description;
        this.price = price;
        this.author = author;
        this.date = date;
        this.numberPhone = numberPhone;
    }


    // --- ID Getters and Setters ---

    /**
     * Gets the unique identifier of the ad. / Отримує унікальний ідентифікатор оголошення.
     */
    public Long getId() {
        return id;
    }

    /**
     * Sets the unique identifier of the ad. / Встановлює унікальний ідентифікатор оголошення.
     */
    public void setId(Long id) {
        this.id = id;
    }

    // --- Image URL Getters and Setters ---

    /**
     * Gets the link to the photo. / Отримує посилання на фотографію.
     */
    public String getUrlImage() {
        return urlImage;
    }

    /**
     * Sets the link to the photo. / Встановлює посилання на фотографію.
     */
    public void setUrlImage(String urlImage) {
        this.urlImage = urlImage;
    }

    // --- Description Getters and Setters ---

    /**
     * Gets the ad description. / Отримує опис оголошення.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the ad description. / Встановлює опис оголошення.
     */
    public void setDescription(String description) {
        this.description = description;
    }

    // --- Title Getters and Setters ---

    /**
     * Gets the ad title. / Отримує назву оголошення.
     */
    public String getTitle() {
        return title;
    }

    /**
     * Sets the ad title. / Встановлює назву оголошення.
     */
    public void setTitle(String title) {
        this.title = title;
    }

    // --- Price Getters and Setters ---

    /**
     * Gets the price of the item. / Отримує ціну об'єкта.
     */
    public BigDecimal getPrice() {
        return price;
    }

    /**
     * Sets the price of the item. / Встановлює ціну об'єкта.
     */
    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    // --- Author Getters and Setters ---

    /**
     * Gets the publisher's name. / Отримує ім'я автора оголошення.
     */
    public String getAuthor() {
        return author;
    }

    /**
     * Sets the publisher's name. / Встановлює ім'я автора оголошення.
     */
    public void setAuthor(String author) {
        this.author = author;
    }

    // --- Date Getters and Setters ---

    /**
     * Gets the publication date. / Отримує дату публікації.
     */
    public String getDate() {
        return date;
    }

    /**
     * Sets the publication date. / Встановлює дату публікації.
     */
    public void setDate(String date) {
        this.date = date;
    }

    // --- Phone Number Getters and Setters ---

    /**
     * Gets the contact phone number. / Отримує номер телефону для зв'язку.
     */
    public String getNumberPhone() {
        return numberPhone;
    }

    /**
     * Sets the contact phone number. / Встановлює номер телефону для зв'язку.
     */
    public void setNumberPhone(String numberPhone) {
        this.numberPhone = numberPhone;
    }
}
