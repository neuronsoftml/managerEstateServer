package core.telegram.main;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ChannelDiscussionRegistry {
    // Карта зберігає: Ключ = ID повідомлення в каналі, Значення = ID повідомлення в чаті обговорення
    private static final ConcurrentHashMap<Integer, Integer> msgMap = new ConcurrentHashMap<>();

    // Блокувальники для очікування асинхронного форварду
    private static final ConcurrentHashMap<Integer, CountDownLatch> latches = new ConcurrentHashMap<>();

    public static void registerMapping(int channelMessageId, int discussionMessageId) {
        msgMap.put(channelMessageId, discussionMessageId);
        CountDownLatch latch = latches.remove(channelMessageId);
        if (latch != null) {
            latch.countDown();
        }
    }

    public static int getDiscussionMessageId(int channelMessageId) {
        // Чекаємо до 5 секунд, поки робот зловить апдейт форварду у групі
        if (!msgMap.containsKey(channelMessageId)) {
            CountDownLatch latch = new CountDownLatch(1);
            latches.put(channelMessageId, latch);
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return msgMap.getOrDefault(channelMessageId, -1);
    }
}
