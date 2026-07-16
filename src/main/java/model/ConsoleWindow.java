package model;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
/**
 * Окреме графічне вікно-консоль (на базі Swing) для виведення логів конкретного контролера в реальному часі.
 * <p>
 * Дозволяє ізолювати логування різних процесів (наприклад, роботу парсера OLX) у власні вікна.
 * Перенаправляє символи, записані в {@link PrintStream}, у текстове поле {@link JTextArea}
 * із додаванням часового штампу (timestamp) та автоматичним прокручуванням вниз.
 * </p>
 * <p><b>Приклад використання:</b></p>
 * <pre>{@code
 * ConsoleWindow olxConsole = new ConsoleWindow("OLX Parser", Color.BLACK);
 * olxConsole.setLocation(100, 100);
 * PrintStream log = olxConsole.getPrintStream();
 * log.println("Запуск парсингу..."); // Текст з'явиться у вікні з міткою часу
 * }</pre>
 * * @author Mykola
 */
public class ConsoleWindow {

    // Формат часу для логування (години:хвилини:секунди)
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JFrame     frame;
    private final JTextArea  textArea;
    private final PrintStream printStream;
    private final String     prefix;

    /**
     * Конструктор, який створює та відображає вікно консолі з індивідуальними налаштуваннями.
     *
     * @param title   заголовок вікна (також використовується як префікс)
     * @param bgColor колір фону текстової області (корисно задавати різні кольори для різних процесів)
     */
    public ConsoleWindow(String title, Color bgColor) {
        this.prefix = "[" + title + "]";

        // ── Ініціалізація та налаштування компонентів UI ───────────────────────
        textArea = new JTextArea();
        setParamTextArea(textArea, bgColor);

        // Створення панелі прокрутки з активованим автоскролом
        JScrollPane scrollPane = autoScrollBottom();

        // Панель з кнопкою очищення вікна
        JPanel bottomPanel = buttonClear();

        // Налаштування головного фрейму (вікна)
        frame = new JFrame("🖥 " + title);
        setParamFrame(scrollPane, bottomPanel);

        // ── Створення кастомного PrintStream, який пише безпосередньо у JTextArea ──
        OutputStream os = new OutputStream() {
            // Буфер для накопичення символів поточного рядка перед його виведенням
            private final StringBuilder lineBuf = new StringBuilder();

            /**
             * Записує один байт (символ) у потік.
             * Якщо прилітає символ перенесення рядка ('\n'), буфер скидається (flush) на екран.
             */
            @Override
            public void write(int b) throws IOException {
                char c = (char) b;
                if (c == '\n') {
                    flushLine();
                } else {
                    lineBuf.append(c);
                }
            }

            /**
             * Оптимізований запис масиву байтів. Розбирає вхідний потік на символи.
             */
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                String s = new String(b, off, len);
                for (char c : s.toCharArray()) {
                    if (c == '\n') {
                        flushLine();
                    } else {
                        lineBuf.append(c);
                    }
                }
            }

            /**
             * Формує кінцевий рядок логу: додає поточний час, очищує буфер
             * та безпечно оновлює Swing UI з потоку EDT (Event Dispatch Thread).
             */
            private void flushLine() {
                String line = lineBuf.toString();
                lineBuf.setLength(0); // Очищення буфера для наступного рядка

                String timestamp = LocalTime.now().format(TIME_FMT);
                String formatted = timestamp + " " + line + "\n";

                // Swing не є потокобезпечним, тому оновлюємо текстову область через invokeLater
                SwingUtilities.invokeLater(() -> textArea.append(formatted));
            }
        };

        // Загортаємо наш кастомний OutputStream у PrintStream з авто-флашем (auto-flush)
        this.printStream = new PrintStream(os, true, java.nio.charset.StandardCharsets.UTF_8);

        // Початкове вітальне повідомлення в консолі
        printStream.println("=== Консоль запущена: " + title + " ===");
    }

    /**
     * Повертає налаштований потік виведення {@link PrintStream}.
     * Його слід передавати у ваші контролери чи парсери (замість {@code System.out}).
     *
     * @return об'єкт {@link PrintStream} для виведення тексту у вікно
     */
    public PrintStream getPrintStream() {
        return printStream;
    }

    /**
     * Встановлює позицію вікна консолі на екрані монітора.
     * Корисно для розташування кількох вікон поруч без перекриття.
     *
     * @param x координата X
     * @param y координата Y
     */
    public void setLocation(int x, int y) {
        frame.setLocation(x, y);
    }

    /**
     * Створює панель прокрутки (Scroll Pane) та налаштовує каретку
     * для автоматичного прокручування тексту до самого низу при надходженні нових логів.
     */
    private JScrollPane autoScrollBottom() {
        DefaultCaret caret = (DefaultCaret) textArea.getCaret();
        // Завжди зміщувати фокус вниз за текстом
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        return scrollPane;
    }

    /**
     * Створює нижню панель керування із кнопкою «Очистити» для швидкого очищення консолі.
     */
    private JPanel buttonClear() {
        JButton clearBtn = new JButton("Очистити");
        // При натисканні занулюємо вміст текстового поля
        clearBtn.addActionListener(e -> textArea.setText(""));

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.add(clearBtn);
        return bottomPanel;
    }

    /**
     * Налаштовує параметри візуального стилю текстового поля (шрифти, кольори, перенесення слів).
     */
    private void setParamTextArea(JTextArea textArea, Color bgColor) {
        textArea.setEditable(false); // Заборона редагування тексту користувачем вручну
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 13)); // Моноширинний шрифт (як у класичній IDE)
        textArea.setBackground(bgColor);
        textArea.setForeground(Color.LIGHT_GRAY);
        textArea.setLineWrap(true);       // Вмикаємо автоматичне перенесення занадто довгих рядків
        textArea.setWrapStyleWord(false);  // Переносимо жорстко по символах, щоб зберегти табличний вигляд
    }

    /**
     * Налаштовує параметри головного вікна (JFrame): розмір, менеджери компонування, поведінку закриття.
     */
    private void setParamFrame(JScrollPane scrollPane, JPanel bottomPanel) {
        // ВАЖЛИВО: DO_NOTHING_ON_CLOSE. Оскільки консоль працює паралельно з демонами/сервером,
        // випадковий клік на хрестик не повинен ламати або закривати фоновий процес.
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(scrollPane, BorderLayout.CENTER);
        frame.add(bottomPanel, BorderLayout.SOUTH);
        frame.setSize(800, 500);
        frame.setVisible(true); // Показуємо вікно на екрані
    }
}