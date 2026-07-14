package core.telegram.model;

public enum Config {
    TOKEN("8689197100:AAGGoRNUWrMd2w-XPE9xvA9-NLd0S2w4Vkk"),
    NAME("RealEstateManager_chernovtsy_bot"),
    ID_CHAT("-1003741489061");

    private final String key;

    Config(String key){{
        this.key = key;}
    }

    public String getKey() {
        return key;
    }
}
