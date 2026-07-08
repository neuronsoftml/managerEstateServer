package model;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Перерахування (Enum) для керування всіма робочими папками проєкту.
 * Запобігає помилкам друку (typos) та централізує роботу зі шляхами.
 */
public enum ProjectFolder {
    // Наші папки та їхні точні системні імена
    OUTPUT_DIR("output"),
    POSTS_DIR("posts"),
    ID_LISTS("apartment_id"),
    DETAILS("apartment_details"),
    OLX_IDS("olx_id"),
    OLX_DETAILS("olx_details"),
    ROOT("data"),
    ALL_IDS_FILE("all_ids.json"),
    META_FILE("meta.json"),
    ID_FROM_URL("-ID([A-Za-z0-9]+)\\\\.html\"");

    private final String folderName;


    // Конструктор enum
    ProjectFolder(String folderName) {
        this.folderName = folderName;
    }

    /**
     * Повертає назву папки як звичайний рядок.
     */
    public String getName() {
        return folderName;
    }

    /**
     * Повертає готовий об'єкт Path для роботи з java.nio.file.Files.
     */
    public Path getPath() {
        return Paths.get(folderName);
    }
}
