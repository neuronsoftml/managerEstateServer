package core.tools.olx;

import java.util.List;

public class AnnouncementParamParser {
    public static int getRoomsCount(List<String> params) {
        return extractInteger(params, "Кількість кімнат:");
    }

    public static int getFloor(List<String> params) {
        return extractInteger(params, "Поверх:");
    }

    public static double getTotalArea(List<String> params) {
        String value = extractString(params, "Загальна площа:");
        if (value == null) return -1;
        try {
            String num = value.replaceAll("[^\\d.]", "");
            return num.isEmpty() ? -1 : Double.parseDouble(num);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int extractInteger(List<String> params, String prefix) {
        String value = extractString(params, prefix);
        if (value == null) return -1;
        try {
            String num = value.replaceAll("[^\\d]", "");
            return num.isEmpty() ? -1 : Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String extractString(List<String> params, String prefix) {
        if (params == null) return null;
        for (String param : params) {
            if (param.startsWith(prefix)) {
                return param;
            }
        }
        return null;
    }
}
