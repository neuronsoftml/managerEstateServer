package core.telegram.main;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


/**
 * Реєстр відповідності повідомлень каналу та їхніх копій у чаті обговорення (коментарях).
 * <p>
 * <b>Проблема:</b> Коли бот публікує пост у канал, Telegram автоматично пересилає (робить репост)
 * цього повідомлення у пов'язану супергрупу (чат обговорення). Проте цей процес є асинхронним.
 * Потік відправки фотографій у коментарі не знає заздалегідь, який ID отримає це повідомлення у групі.
 * </p>
 * <p>
 * <b>Рішення:</b> Цей клас реалізує механізм синхронізації (миттєвого очікування) між двома подіями:
 * <ol>
 * <li>Головний потік опублікував пост і хоче надіслати фото (викликає {@link #getDiscussionMessageId(int)}).</li>
 * <li>Webhook/Update-лісенер бота ловить подію репосту у групі та реєструє зв'язок (викликає {@link #registerMapping(int, int)}).</li>
 * </ol>
 * Для цього використовуються потокобезпечні карти {@link ConcurrentHashMap} та засувки {@link CountDownLatch}.
 * </p>
 * * @author Mykola
 */
public class ChannelDiscussionRegistry {

    /**
     * Карта зв'язків ідентифікаторів повідомлень.
     * Ключ: ID повідомлення в каналі (channelMessageId).
     * Значення: ID повідомлення в чаті обговорення (discussionMessageId).
     */
    private static final ConcurrentHashMap<Integer, Integer> msgMap = new ConcurrentHashMap<>();

    /**
     * Карта блокувальників (засувок) для синхронізації потоків.
     * Ключ: ID повідомлення в каналі.
     * Значення: об'єкт {@link CountDownLatch}, який призупиняє потік відправки фото до моменту отримання апдейту.
     */
    private static final ConcurrentHashMap<Integer, CountDownLatch> latches = new ConcurrentHashMap<>();

    /**
     * Реєструє зв'язок між постом у каналі та його репостом у групі.
     * Викликається з обробника подій (Update Listener), коли бот фіксує переслане повідомлення.
     *
     * @param channelMessageId    ID оригінального повідомлення в каналі
     * @param discussionMessageId ID скопійованого повідомлення в групі обговорення
     */
    public static void registerMapping(int channelMessageId, int discussionMessageId) {
        // Зберігаємо мапінг у загальну базу
        msgMap.put(channelMessageId, discussionMessageId);

        // Шукаємо, чи чекає якийсь потік на появу цього мапінгу
        CountDownLatch latch = latches.remove(channelMessageId);
        if (latch != null) {
            // Розблоковуємо потік, який очікує в методі getDiscussionMessageId
            latch.countDown();
        }
    }

    /**
     * Повертає ID повідомлення в чаті обговорення для відповідного поста в каналі.
     * <p>
     * Якщо мапінг ще не зареєстрований (подія репосту від Telegram ще не прийшла),
     * метод створює {@link CountDownLatch} і блокує поточний потік максимум на 5 секунд.
     * Як тільки лісенер викличе {@link #registerMapping}, потік миттєво прокинеться і поверне ID.
     * </p>
     *
     * @param channelMessageId ID оригінального повідомлення в каналі
     * @return ID повідомлення в групі (коментарях), або {@code -1}, якщо за 5 секунд репост не відбувся (таймаут)
     */
    public static int getDiscussionMessageId(int channelMessageId) {
        // Якщо мапінгу ще немає, створюємо засувку для очікування
        if (!msgMap.containsKey(channelMessageId)) {
            CountDownLatch latch = new CountDownLatch(1);
            latches.put(channelMessageId, latch);
            try {
                // Блокуємо потік максимум на 5 секунд. Цього цілком достатньо для внутрішньої черги Telegram.
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // Відновлюємо статус переривання потоку
                Thread.currentThread().interrupt();
            }
        }
        // Повертаємо знайдений ID, або -1, якщо репост так і не було зафіксовано за час таймауту
        return msgMap.getOrDefault(channelMessageId, -1);
    }
}
