package model;

/** Збережені критерії пошуку та стан персональних сповіщень користувача. */
public class SavedSearchFilter {
    private long telegramId;
    private DealType dealType;
    private PropertyType propertyType;
    private City city;
    private AnnouncementCategory category;
    private Integer rooms;
    private boolean roomsMinimum;
    private Integer minPriceUsd;
    private Integer maxPriceUsd;
    private boolean notificationsEnabled;

    public long getTelegramId() { return telegramId; }
    public void setTelegramId(long telegramId) { this.telegramId = telegramId; }
    public DealType getDealType() { return dealType; }
    public void setDealType(DealType dealType) { this.dealType = dealType; }
    public PropertyType getPropertyType() { return propertyType; }
    public void setPropertyType(PropertyType propertyType) { this.propertyType = propertyType; }
    public City getCity() { return city; }
    public void setCity(City city) { this.city = city; }
    public AnnouncementCategory getCategory() { return category; }
    public void setCategory(AnnouncementCategory category) { this.category = category; }
    public Integer getRooms() { return rooms; }
    public void setRooms(Integer rooms) { this.rooms = rooms; }
    public boolean isRoomsMinimum() { return roomsMinimum; }
    public void setRoomsMinimum(boolean roomsMinimum) { this.roomsMinimum = roomsMinimum; }
    public Integer getMinPriceUsd() { return minPriceUsd; }
    public void setMinPriceUsd(Integer minPriceUsd) { this.minPriceUsd = minPriceUsd; }
    public Integer getMaxPriceUsd() { return maxPriceUsd; }
    public void setMaxPriceUsd(Integer maxPriceUsd) { this.maxPriceUsd = maxPriceUsd; }
    public boolean isNotificationsEnabled() { return notificationsEnabled; }
    public void setNotificationsEnabled(boolean notificationsEnabled) { this.notificationsEnabled = notificationsEnabled; }
}
