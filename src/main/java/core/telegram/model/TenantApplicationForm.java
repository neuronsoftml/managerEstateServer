package core.telegram.model;

import java.time.LocalDateTime;

/**
 * Модель даних (DTO / Entity), що представляє анкету потенційного орендаря житла.
 * <p>
 * Цей клас використовується для акумуляції відповідей користувача під час проходження
 * покрокового опитування (Wizard) в Telegram-боті. Зібрані дані дозволяють сформувати
 * детальний портрет клієнта та автоматично підбирати або пропонувати відповідні варіанти нерухомості.
 * </p>
 * * @author Mykola
 */
public class TenantApplicationForm {

    /** Унікальний ідентифікатор користувача в Telegram, що виступає ключем зв'язку */
    private long telegramId;

    /** Ім'я або нікнейм заявника, вказане при заповненні анкети */
    private String userName;

    /** Контактний номер телефону для зв'язку ріелтора чи власника з клієнтом */
    private String phoneNumber;

    /** Максимальний місячний бюджет на оренду житла (зазвичай в еквіваленті USD або UAH) */
    private int budget;

    /** Прапорець готовності клієнта внести заставну суму власнику (депозит за останній місяць) */
    private boolean readyForDeposit;

    /** Прапорець готовності клієнта сплатити ріелторські комісійні за підбір житла */
    private boolean readyForCommission;

    /** Бажані райони міста або локації для пошуку нерухомості (перелік через кому або текст) */
    private String preferredDistricts;

    /** Бажаний тип кімнатності (наприклад: "1-кімнатна", "2-кімнатна", "Студія") */
    private String roomsType;

    /** Планований термін оренди житла (наприклад: "від 6 місяців", "довгостроково", "рік") */
    private String rentTerm;

    /** Кількість осіб, які планують спільно проживати в орендованій нерухомості */
    private int tenantsCount;

    /** Додатковий текстовий опис складу проживаючих (наприклад: "сімейна пара", "студенти") */
    private String tenantsDescription;

    /** Прапорець наявності дітей у майбутніх мешканців */
    private boolean hasChildren;

    /** Детальна інформація про дітей (кількість, вік тощо) */
    private String childrenInfo;

    /** Прапорець наявності домашніх тварин */
    private boolean hasPets;

    /** Детальніша інформація про тварин (наприклад: "дрібний тер'єр", "стерилізований кіт") */
    private String petsInfo;

    /** Сфера професійної зайнятості заявника (наприклад: "IT", "Маркетинг", "Будівництво") */
    private String employmentSphere;

    /** Формат роботи орендаря (наприклад: "дистанційно", "в офісі", "позмінно") */
    private String workFormat;

    /** Статус ставлення до паління всередині квартири або на балконі */
    private String smokingStatus;

    /** Прапорець наявності власного автомобіля (впливає на потребу в паркомісці/гаражі) */
    private boolean hasCar;

    /** Особливі та критично важливі вимоги до житла (наприклад: "автономне опалення", "не вище 3-го поверху") */
    private String criticalRequirements;

    /** Дата та час створення/останнього збереження цієї анкети в системі */
    private LocalDateTime createdAt;

    /**
     * Дефолтний конструктор без параметрів.
     * Необхідний для коректної роботи бібліотек серіалізації/десеріалізації (наприклад, Jackson) або ORM.
     */
    public TenantApplicationForm() {
    }

    /**
     * Конструктор з повним набором параметрів для швидкої ініціалізації заповненої анкети.
     *
     * @param telegramId           ID користувача в Telegram
     * @param budget               місячний бюджет
     * @param readyForDeposit      готовність платити заставу
     * @param readyForCommission   готовність сплатити комісію
     * @param preferredDistricts   бажані райони пошуку
     * @param roomsType            тип/кількість кімнат
     * @param rentTerm             планований термін оренди
     * @param tenantsCount         загальна кількість мешканців
     * @param tenantsDescription   детальний опис мешканців
     * @param hasChildren          наявність дітей
     * @param childrenInfo         інформація про дітей (вік)
     * @param hasPets              наявність тварин
     * @param petsInfo             інформація про тварин
     * @param employmentSphere     сфера працевлаштування
     * @param workFormat           формат роботи
     * @param smokingStatus        ставлення до куріння
     * @param hasCar               наявність автомобіля
     * @param criticalRequirements критичні вимоги до житла
     * @param createdAt            дата створення анкети
     */
    public TenantApplicationForm(long telegramId, int budget, boolean readyForDeposit, boolean readyForCommission,
                                 String preferredDistricts, String roomsType, String rentTerm, int tenantsCount,
                                 String tenantsDescription, boolean hasChildren, String childrenInfo, boolean hasPets,
                                 String petsInfo, String employmentSphere, String workFormat, String smokingStatus,
                                 boolean hasCar, String criticalRequirements, LocalDateTime createdAt) {
        this.telegramId = telegramId;
        this.budget = budget;
        this.readyForDeposit = readyForDeposit;
        this.readyForCommission = readyForCommission;
        this.preferredDistricts = preferredDistricts;
        this.roomsType = roomsType;
        this.rentTerm = rentTerm;
        this.tenantsCount = tenantsCount;
        this.tenantsDescription = tenantsDescription;
        this.hasChildren = hasChildren;
        this.childrenInfo = childrenInfo;
        this.hasPets = hasPets;
        this.petsInfo = petsInfo;
        this.employmentSphere = employmentSphere;
        this.workFormat = workFormat;
        this.smokingStatus = smokingStatus;
        this.hasCar = hasCar;
        this.criticalRequirements = criticalRequirements;
        this.createdAt = createdAt;
    }

    // ── Геттери та Сеттери з документацією ────────────────────────────────────

    public long getTelegramId() { return telegramId; }
    public void setTelegramId(long telegramId) { this.telegramId = telegramId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public int getBudget() { return budget; }
    public void setBudget(int budget) { this.budget = budget; }

    public boolean isReadyForDeposit() { return readyForDeposit; }
    public void setReadyForDeposit(boolean readyForDeposit) { this.readyForDeposit = readyForDeposit; }

    public boolean isReadyForCommission() { return readyForCommission; }
    public void setReadyForCommission(boolean readyForCommission) { this.readyForCommission = readyForCommission; }

    public String getPreferredDistricts() { return preferredDistricts; }
    public void setPreferredDistricts(String preferredDistricts) { this.preferredDistricts = preferredDistricts; }

    public String getRoomsType() { return roomsType; }
    public void setRoomsType(String roomsType) { this.roomsType = roomsType; }

    public String getRentTerm() { return rentTerm; }
    public void setRentTerm(String rentTerm) { this.rentTerm = rentTerm; }

    public int getTenantsCount() { return tenantsCount; }
    public void setTenantsCount(int tenantsCount) { this.tenantsCount = tenantsCount; }

    public String getTenantsDescription() { return tenantsDescription; }
    public void setTenantsDescription(String tenantsDescription) { this.tenantsDescription = tenantsDescription; }

    public boolean isHasChildren() { return hasChildren; }
    public void setHasChildren(boolean hasChildren) { this.hasChildren = hasChildren; }

    public String getChildrenInfo() { return childrenInfo; }
    public void setChildrenInfo(String childrenInfo) { this.childrenInfo = childrenInfo; }

    public boolean isHasPets() { return hasPets; }
    public void setHasPets(boolean hasPets) { this.hasPets = hasPets; }

    public String getPetsInfo() { return petsInfo; }
    public void setPetsInfo(String petsInfo) { this.petsInfo = petsInfo; }

    public String getEmploymentSphere() { return employmentSphere; }
    public void setEmploymentSphere(String employmentSphere) { this.employmentSphere = employmentSphere; }

    public String getWorkFormat() { return workFormat; }
    public void setWorkFormat(String workFormat) { this.workFormat = workFormat; }

    public String getSmokingStatus() { return smokingStatus; }
    public void setSmokingStatus(String smokingStatus) { this.smokingStatus = smokingStatus; }

    public boolean isHasCar() { return hasCar; }
    public void setHasCar(boolean hasCar) { this.hasCar = hasCar; }

    public String getCriticalRequirements() { return criticalRequirements; }
    public void setCriticalRequirements(String criticalRequirements) { this.criticalRequirements = criticalRequirements; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /**
     * Повертає строкове представлення об'єкта для полегшення процесу дебагу,
     * логування роботи системи та перевірки наповненості профілю у консолі.
     *
     * @return зліпок усіх значень полів анкети у вигляді рядка
     */
    @Override
    public String toString() {
        return "TenantApplicationForm{" +
                "telegramId=" + telegramId +
                ", userName='" + userName + '\'' +
                ", phoneNumber='" + phoneNumber + '\'' +
                ", budget=" + budget +
                ", readyForDeposit=" + readyForDeposit +
                ", readyForCommission=" + readyForCommission +
                ", preferredDistricts='" + preferredDistricts + '\'' +
                ", roomsType='" + roomsType + '\'' +
                ", rentTerm='" + rentTerm + '\'' +
                ", tenantsCount=" + tenantsCount +
                ", tenantsDescription='" + tenantsDescription + '\'' +
                ", hasChildren=" + hasChildren +
                ", childrenInfo='" + childrenInfo + '\'' +
                ", hasPets=" + hasPets +
                ", petsInfo='" + petsInfo + '\'' +
                ", employmentSphere='" + employmentSphere + '\'' +
                ", workFormat='" + workFormat + '\'' +
                ", smokingStatus='" + smokingStatus + '\'' +
                ", hasCar=" + hasCar +
                ", criticalRequirements='" + criticalRequirements + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}

