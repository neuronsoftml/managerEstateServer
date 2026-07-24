package model;

import java.util.List;

/** Незавершена персональна видача нових оголошень із постійною пагінацією. */
public record NotificationBatch(List<String> announcementIds, int offset) {
    public boolean hasMore() { return offset < announcementIds.size(); }
}
