package controllers;

import controllers.olx.OlxController;
import materials.Emoji;
import model.ConsoleWindow;

import java.io.PrintStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Клас для організації регулярного щоденного обнови бази даних.
 */
public class UpdateBaseController {

    /**
     * Ініціалізує планувальник задач (ScheduledExecutorService).
     * Розраховує початкову затримку до найближчої півночі та запускає
     * циклічне виконання цільового методу кожні 24 години.
     */
    public void startDailyTask(ConsoleWindow consoleWindowOlx) {
        startOlxScheduler(consoleWindowOlx);
    }


    /**
     * Запускає поток збору данних з олх.
     * @param consoleWindowOlx
     */
    private static void startOlxScheduler(ConsoleWindow consoleWindowOlx){
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OLX-Thread");
            t.setDaemon(false);
            return t;
        });

        executor.scheduleAtFixedRate(() -> {
            PrintStream olxOut = consoleWindowOlx.getPrintStream();
            olxOut.println("▶ Запуск OlxController...");
            try {
                OlxController.start(olxOut);
            } catch (Exception e) {
                olxOut.println(Emoji.ERROR.view()+" Помилка OlxController: " + e.getMessage());
                e.printStackTrace(olxOut);
            }
        }, 0, 24, TimeUnit.HOURS); // ← period мінімум 1, не 0

        System.out.println(Emoji.CALENDAR.view()+" OLX планувальник запущено (кожні 6 год)");
    }

}
