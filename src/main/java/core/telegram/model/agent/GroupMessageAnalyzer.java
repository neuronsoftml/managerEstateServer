package core.telegram.model.agent;

import java.util.Arrays;
import java.util.List;

public class GroupMessageAnalyzer {
    // Слова-стопери: якщо людина сама шукає, нам це оголошення не треба
    private static final List<String> STOP_WORDS = Arrays.asList("шукаю", "куплю", "винайму", "шукає");

    // Цільові слова для оренди та продажу
    private static final List<String> TARGET_WORDS = Arrays.asList(
            "оренда", "здам", "власник", "кімнат", "квартир", "посуточно", "довготривало", "продаж", "продам"
    );

    public static boolean isRealEstateOffer(String text) {
        if (text == null || text.isBlank()) return false;

        String lowerText = text.toLowerCase();

        // 1. Відсіюємо тих, хто шукає сам
        for (String stopWord : STOP_WORDS) {
            if (lowerText.contains(stopWord)) {
                return false;
            }
        }

        // 2. Рахуємо кількість збігів ключових слів
        long matchCount = TARGET_WORDS.stream()
                .filter(lowerText::contains)
                .count();

        // Якщо є хоча б 2 ключових слова (напр. "здам" + "квартиру"), вважаємо це за пропозицію
        return matchCount >= 2;
    }
}
