package core.telegram.model;

import java.time.LocalDateTime;

public class TenantApplicationForm {
    private long telegramId;
    private int budget;
    private boolean readyForDeposit;
    private boolean readyForCommission;
    private String preferredDistricts;
    private String roomsType;
    private String rentTerm;
    private int tenantsCount;
    private String tenantsDescription;
    private boolean hasChildren;
    private String childrenInfo;
    private boolean hasPets;
    private String petsInfo;
    private String employmentSphere;
    private String workFormat;
    private String smokingStatus;
    private boolean hasCar;
    private String criticalRequirements;
    private LocalDateTime createdAt;

    public TenantApplicationForm() {
    }

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

    // Геттери та Сеттери
    public long getTelegramId() { return telegramId; }
    public void setTelegramId(long telegramId) { this.telegramId = telegramId; }

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

    // Зручний toString для дебагу та логування
    @Override
    public String toString() {
        return "TenantApplicationForm{" +
                "telegramId=" + telegramId +
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

