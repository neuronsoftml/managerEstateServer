package core.telegram.main;

import model.Announcement;

import java.math.BigDecimal;
import java.text.DecimalFormat;

public class AnnouncementFormatter {
    private static final DecimalFormat df = new DecimalFormat("#,###.##");

    //TODO  Формує повідомлення оренди довготривалої.
    public static String toHtmlLongTermLease(Announcement ad) {
        StringBuilder sb = new StringBuilder();
        sb.append("🆕 <b>Оренда (Довготривала)!</b>\n\n");
        sb.append("📌 <b>Заголовок:</b> ").append(escapeHtml(ad.getTitle())).append("\n");

        if (ad.getPriceValue() != null) {
            BigDecimal priceUah = ad.getPriceValue();
            BigDecimal priceUsd = core.tools.PrivatBankRateService.convertUahToUsd(priceUah);
            BigDecimal priceEur = core.tools.PrivatBankRateService.convertUahToEur(priceUah);

            sb.append("💰 <b>Ціна:</b> ").append(df.format(priceUah)).append(" грн\n");
            sb.append("💵 <b>Еквівалент:</b> $").append(df.format(priceUsd)).append("  |  €").append(df.format(priceEur)).append("\n");
        } else {
            sb.append("💰 <b>Ціна:</b> Договірна\n");
        }

        sb.append("📍 <b>Локація:</b> ").append(ad.getLocation() != null ? escapeHtml(ad.getLocation()) : ad.getLocation()).append("\n");
        sb.append("📅 <b>Дата:</b> ").append(ad.getDatePublished() != null ? ad.getDatePublished() : "-").append("\n");

        sb.append("📊 <b>Параметри:</b> ");
        sb.append(ad.getRoomsCount() > 0 ? ad.getRoomsCount() + " кімн." : "-").append(" | ");
        sb.append(ad.getTotalArea() > 0 ? ad.getTotalArea() + " м²" : "-").append(" | ");
        sb.append(ad.getFloor() > 0 ? ad.getFloor() + " пов." : "-").append("\n");

        sb.append("📝 <b>Опис:</b> ").append(escapeHtml(truncateDescription(ad.getDescription(), 200))).append("\n\n");
        sb.append("<a href=\"").append(ad.getUrl()).append("\">🔗 Відкрити на OLX</a>\n");
        return sb.toString();
    }

    //TODO  Формує повідомлення оренди подобової.
    public static String toHtmlDailyRental(Announcement ad) {
        StringBuilder sb = new StringBuilder();
        sb.append("🆕 <b>Оренда (подобова)!</b>\n\n");
        sb.append("📌 <b>Заголовок:</b> ").append(escapeHtml(ad.getTitle())).append("\n");

        if (ad.getPriceValue() != null) {
            BigDecimal priceUah = ad.getPriceValue();
            BigDecimal priceUsd = core.tools.PrivatBankRateService.convertUahToUsd(priceUah);
            BigDecimal priceEur = core.tools.PrivatBankRateService.convertUahToEur(priceUah);

            sb.append("💰 <b>Ціна:</b> ").append(df.format(priceUah)).append(" грн\n");
            sb.append("💵 <b>Еквівалент:</b> $").append(df.format(priceUsd)).append("  |  €").append(df.format(priceEur)).append("\n");
        } else {
            sb.append("💰 <b>Ціна:</b> Договірна\n");
        }

        sb.append("📍 <b>Локація:</b> ").append(ad.getLocation() != null ? escapeHtml(ad.getLocation()) : ad.getLocation()).append("\n");
        sb.append("📅 <b>Дата:</b> ").append(ad.getDatePublished() != null ? ad.getDatePublished() : "-").append("\n");

        sb.append("📝 <b>Опис:</b> ").append(escapeHtml(truncateDescription(ad.getDescription(), 200))).append("\n\n");
        sb.append("<a href=\"").append(ad.getUrl()).append("\">🔗 Відкрити на OLX</a>\n");
        return sb.toString();
    }

    //TODO  Формує повідомлення продажи нерухомості.
    public static String toHtmlForSale(Announcement ad) {
        StringBuilder sb = new StringBuilder();
        sb.append("🆕 <b>Продаж нерухомості</b>\n\n");
        sb.append("📌 <b>Заголовок:</b> ").append(escapeHtml(ad.getTitle())).append("\n");

        if (ad.getPriceValue() != null) {
            BigDecimal priceUah = ad.getPriceValue();
            BigDecimal priceUsd = core.tools.PrivatBankRateService.convertUahToUsd(priceUah);
            BigDecimal priceEur = core.tools.PrivatBankRateService.convertUahToEur(priceUah);

            sb.append("💰 <b>Ціна:</b> ").append(df.format(priceUah)).append(" грн\n");
            sb.append("💵 <b>Еквівалент:</b> $").append(df.format(priceUsd)).append("  |  €").append(df.format(priceEur)).append("\n");
        } else {
            sb.append("💰 <b>Ціна:</b> Договірна\n");
        }

        sb.append("📍 <b>Локація:</b> ").append(ad.getLocation() != null ? escapeHtml(ad.getLocation()) : ad.getLocation()).append("\n");
        sb.append("📅 <b>Дата:</b> ").append(ad.getDatePublished() != null ? ad.getDatePublished() : "-").append("\n");


        sb.append("📝 <b>Опис:</b> ").append(escapeHtml(truncateDescription(ad.getDescription(), 200))).append("\n\n");
        sb.append("<a href=\"").append(ad.getUrl()).append("\">🔗 Відкрити на OLX</a>\n");
        return sb.toString();
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String truncateDescription(String desc, int maxLength) {
        if (desc == null || desc.isEmpty()) return "Немає опису";
        if (desc.length() <= maxLength) return desc;
        return desc.substring(0, maxLength) + "...";
    }
}

