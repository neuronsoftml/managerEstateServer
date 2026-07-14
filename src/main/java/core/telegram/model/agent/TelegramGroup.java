package core.telegram.model.agent;

public class TelegramGroup {
    private int id;                 // Локальний ID в базі даних
    private String chatId;          // Реальний ID чату в Telegram (напр. "-100123456789" або юзернейм "chernivtsi_rent")
    private String groupName;       // Зручна назва для логів (напр. "Оренда Чернівці Чат")
    private long lastProcessedMessageId; // ID останнього зчитаного повідомлення, щоб не сканувати старе
    private boolean isActive;       // Чи активний моніторинг цієї групи зараз

    public TelegramGroup(int id, String chatId, String groupName, long lastProcessedMessageId, boolean isActive) {
        this.id = id;
        this.chatId = chatId;
        this.groupName = groupName;
        this.lastProcessedMessageId = lastProcessedMessageId;
        this.isActive = isActive;
    }

    // Геттери та Сеттери
    public int getId() { return id; }
    public String getChatId() { return chatId; }
    public String getGroupName() { return groupName; }
    public long getLastProcessedMessageId() { return lastProcessedMessageId; }
    public boolean isActive() { return isActive; }

    public void setLastProcessedMessageId(long lastProcessedMessageId) {
        this.lastProcessedMessageId = lastProcessedMessageId;
    }
}
