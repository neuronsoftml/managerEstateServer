package materials;

public enum Emoji {
    /**
     * Перерахування (Enum) для керування всіма icon;
     */

    SEARCH("\uD83D\uDD0D"),
    ERROR("❌"),
    CALENDAR("\uD83D\uDCC5");


    private final String folderName;

    /**
     * Повертає назву Emoji
     */
    Emoji(String folderName) {
        this.folderName = folderName;
    }

    public String view() {return folderName;}
}
