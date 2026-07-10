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
    DETAILS("apartment_details"),
    OLX_IDS("olx_id"),
    OLX_DETAILS("olx_details"),
    DIM_RIA_IDS("dim_ria_id"),
    DIM_RIA_DETAILS("dim_ria_details"),
    ROOT("data"),
    ALL_IDS_FILE("all_ids.json"),
    META_FILE("meta.json");

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
