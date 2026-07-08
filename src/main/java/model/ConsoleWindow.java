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
 * Окреме вікно-консоль для виведення логів конкретного контролера.
 * Використання:
 *   ConsoleWindow window = new ConsoleWindow("OLX Controller", Color.BLACK);
 *   window.getPrintStream() → передати в контролер замість System.out
 */
public class ConsoleWindow {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JFrame     frame;
    private final JTextArea  textArea;
    private final PrintStream printStream;
    private final String     prefix;

    /**
     * @param title  Заголовок вікна
     * @param bgColor Колір фону (різний для кожного вікна, щоб не плутати)
     */
    public ConsoleWindow(String title, Color bgColor) {
        this.prefix = "[" + title + "]";

        // ── UI ────────────────────────────────────────────────────────────────
        textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        textArea.setBackground(bgColor);
        textArea.setForeground(Color.LIGHT_GRAY);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(false);

        // Автоскрол вниз
        DefaultCaret caret = (DefaultCaret) textArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);

        // Кнопка очистити
        JButton clearBtn = new JButton("Очистити");
        clearBtn.addActionListener(e -> textArea.setText(""));

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.add(clearBtn);

        frame = new JFrame("🖥 " + title);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // не закривати — сервер працює
        frame.setLayout(new BorderLayout());
        frame.add(scrollPane, BorderLayout.CENTER);
        frame.add(bottomPanel, BorderLayout.SOUTH);
        frame.setSize(800, 500);
        frame.setVisible(true);

        // ── PrintStream що пише у textArea ───────────────────────────────────
        OutputStream os = new OutputStream() {
            private final StringBuilder lineBuf = new StringBuilder();

            @Override
            public void write(int b) throws IOException {
                char c = (char) b;
                if (c == '\n') {
                    flushLine();
                } else {
                    lineBuf.append(c);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                String s = new String(b, off, len);
                for (char c : s.toCharArray()) {
                    if (c == '\n') flushLine();
                    else lineBuf.append(c);
                }
            }

            private void flushLine() {
                String line = lineBuf.toString();
                lineBuf.setLength(0);
                String timestamp = LocalTime.now().format(TIME_FMT);
                String formatted = timestamp + " " + line + "\n";
                // UI оновлення тільки з EDT потоку
                SwingUtilities.invokeLater(() -> textArea.append(formatted));
            }
        };

        this.printStream = new PrintStream(os, true, java.nio.charset.StandardCharsets.UTF_8);

        // Перший запис
        printStream.println("=== Консоль запущена: " + title + " ===");
    }

    /** PrintStream для передачі в контролер */
    public PrintStream getPrintStream() {
        return printStream;
    }

    /** Розмістити вікно на екрані (x, y) */
    public void setLocation(int x, int y) {
        frame.setLocation(x, y);
    }
}