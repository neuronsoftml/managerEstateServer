package model;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Чернетка оголошення, яку користувач заповнює крок за кроком
 * через {@code core.telegram.controllers.CreatePostWizardController}.
 * <p>
 * На відміну від {@link Announcement} (яка є "чистою" DTO для оголошень,
 * зібраних парсером OLX), цей клас зберігає проміжний, ще не валідований
 * стан анкети власника/рієлтора просто в оперативній пам'яті сесії
 * ({@code UserSession}), доки користувач не підтвердить дані на екрані
 * резюме та не перейде до оплати.
 * </p>
 *
 * @author Mykola
 */
public class CreatePostDraft {

    private DealType dealType;
    private PropertyType propertyType;

    /** Тип ринку (Новобудова / Вторинний ринок) — обирається одразу після типу нерухомості. */
    private PropertyMarketType marketType;

    private String title;
    private Currency currency;
    private BigDecimal price;
    private String location;

    /** Площа земельної ділянки (соток/га) — лише для {@link PropertyType#HOUSE} та {@link PropertyType#LAND}. */
    private Double landArea;

    /** Кількість кімнат — лише для {@link PropertyType#APARTMENT} та {@link PropertyType#HOUSE}. */
    private Integer rooms;

    /** Загальна площа об'єкта (кв.м) — для всіх типів, крім {@link PropertyType#LAND}. */
    private Double totalArea;

    /** Поверх розташування — для всіх типів, крім {@link PropertyType#LAND}. */
    private Integer floor;

    /** Загальна поверховість будинку — для всіх типів, крім {@link PropertyType#LAND}. Йде одразу після {@link #floor}. */
    private Integer totalFloors;

    private SellerType sellerType;

    /** Обраний термін публікації оголошення в днях (3, 7, 15 або 30). */
    private Integer durationDays;

    private String description;

    /** Telegram {@code file_id} завантажених фотографій (порядок збережено). */
    private List<String> photoFileIds = new ArrayList<>();

    /** Контактний номер телефону, отриманий через кнопку "Поділитись контактом" (RequestContact). */
    private String phoneNumber;

    // ── Системні поля, що заповнюються автоматично після завершення анкети ────

    private LocalDateTime createdAt;
    private PaymentStatus paymentStatus;
    private String paymentCode;

    // ── Логіка застосовності полів залежно від типу нерухомості ───────────────

    public boolean isLandAreaApplicable() {
        return propertyType == PropertyType.HOUSE || propertyType == PropertyType.LAND;
    }

    public boolean isRoomsApplicable() {
        return propertyType == PropertyType.APARTMENT || propertyType == PropertyType.HOUSE;
    }

    public boolean isTotalAreaApplicable() {
        return propertyType != PropertyType.LAND;
    }

    public boolean isFloorApplicable() {
        return propertyType != PropertyType.LAND;
    }

    /** Поверховість запитується разом із поверхом — за тією ж умовою застосовності. */
    public boolean isTotalFloorsApplicable() {
        return isFloorApplicable();
    }

    /**
     * Заповнює системні поля, які не вводяться користувачем: дату створення,
     * початковий статус оплати та унікальний 6-символьний код платежу.
     * Викликається один раз, при формуванні екрану резюме.
     */
    public void applySystemDefaults() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.paymentStatus == null) {
            this.paymentStatus = PaymentStatus.PENDING;
        }
        if (this.paymentCode == null || this.paymentCode.isBlank()) {
            this.paymentCode = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        }
    }

    /**
     * Вартість розміщення в грн, що залежить від обраного терміну публікації.
     * Значення умовні (легко коригувати) — 3/7/15/30 днів відповідно.
     */
    public int calculateListingFeeUah() {
        if (durationDays == null) return 50;
        return switch (durationDays) {
            case 3  -> 50;
            case 7  -> 90;
            case 15 -> 150;
            case 30 -> 250;
            default -> 50;
        };
    }

    // ── Геттери та Сеттери ────────────────────────────────────────────────────

    public DealType getDealType() { return dealType; }
    public void setDealType(DealType dealType) { this.dealType = dealType; }

    public PropertyType getPropertyType() { return propertyType; }
    public void setPropertyType(PropertyType propertyType) { this.propertyType = propertyType; }

    public PropertyMarketType getMarketType() { return marketType; }
    public void setMarketType(PropertyMarketType marketType) { this.marketType = marketType; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Currency getCurrency() { return currency; }
    public void setCurrency(Currency currency) { this.currency = currency; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public Double getLandArea() { return landArea; }
    public void setLandArea(Double landArea) { this.landArea = landArea; }

    public Integer getRooms() { return rooms; }
    public void setRooms(Integer rooms) { this.rooms = rooms; }

    public Double getTotalArea() { return totalArea; }
    public void setTotalArea(Double totalArea) { this.totalArea = totalArea; }

    public Integer getFloor() { return floor; }
    public void setFloor(Integer floor) { this.floor = floor; }

    public Integer getTotalFloors() { return totalFloors; }
    public void setTotalFloors(Integer totalFloors) { this.totalFloors = totalFloors; }

    public SellerType getSellerType() { return sellerType; }
    public void setSellerType(SellerType sellerType) { this.sellerType = sellerType; }

    public Integer getDurationDays() { return durationDays; }
    public void setDurationDays(Integer durationDays) { this.durationDays = durationDays; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getPhotoFileIds() { return photoFileIds; }
    public void setPhotoFileIds(List<String> photoFileIds) {
        this.photoFileIds = photoFileIds != null ? photoFileIds : new ArrayList<>();
    }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(PaymentStatus paymentStatus) { this.paymentStatus = paymentStatus; }

    public String getPaymentCode() { return paymentCode; }
    public void setPaymentCode(String paymentCode) { this.paymentCode = paymentCode; }
}
