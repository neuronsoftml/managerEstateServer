
import controllers.ConsoleWindowController;
import controllers.UpdateBaseController;
import materials.Emoji;
import model.ConsoleWindow;
import sqlite.DatabaseManager;

private static UpdateBaseController updateBaseController;
private static ConsoleWindowController consoleWindowController;


void main(String[] args) {
    DatabaseManager.initializeDatabase();
    initControllers();
    startCoreServerFunctionality();
}

/** TODO Тут добовляти потокові  контролери.
 * Цей метод створює та проводить ініціалізацію об'єктів.
 */
private static void initControllers(){
    updateBaseController = new UpdateBaseController();
    consoleWindowController = new ConsoleWindowController();
}

/**
 * Цей метод запускає основний функціонал сервера
 */
private static void startCoreServerFunctionality(){
    consoleWindowController.start();
    startMainScheduler(consoleWindowController.getMainConsole());
    updateDataBse();
}

/**
 * Цей метод відповідає за оновлення бази даних, кожних 24 годин старт з 00 годин :00 хвилин.
 */
private static void updateDataBse(){
    updateBaseController.startDailyTask(
            consoleWindowController.getOlxConsole()
    );
}


/**
 * Цей метод запускає Terminal window  де буде відображатися log message загально програми.
 */

private static PrintStream mainOut;

private static void startMainScheduler(ConsoleWindow consoleWindowMain) {
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Main-Thread");
        t.setDaemon(false);
        return t;
    });

    mainOut = consoleWindowMain.getPrintStream();

    // Просто виводимо статус — парсер НЕ запускаємо тут
    executor.scheduleAtFixedRate(() -> {
        mainOut.println("___________________________________");
        mainOut.println(Emoji.SEARCH.view() + " СИСТЕМА МОНІТОРИНГУ АКТИВНА " + Emoji.SEARCH.view());
        mainOut.println("Час: " + java.time.LocalDateTime.now());
        mainOut.println("___________________________________");
    }, 0, 1, TimeUnit.HOURS); // heartbeat кожну годину

    mainOut.println(Emoji.CALENDAR.view() + " Головний планувальник запущено.");
}




