package controllers;

import model.ConsoleWindow;

import javax.swing.*;
import java.awt.*;

public class ConsoleWindowController {

    // Три окремі вікна-консолі
    private ConsoleWindow mainConsole;
    private ConsoleWindow olxConsole;
    private ConsoleWindow dimRiaConsole;


    public void start() {
        try {
            // invokeAndWait → чекає поки вікна створяться, тільки потім іде далі
            SwingUtilities.invokeAndWait(() -> {
                createWindowMainLog();
                createWindowOlxLog();
                createWindowDimLog();
            });
        } catch (Exception e) {
            throw new RuntimeException("Не вдалося створити вікна консолей", e);
        }
    }


    public  void createWindowOlxLog(){
        olxConsole = new ConsoleWindow("🟠 OLX Controller", new Color(20, 30, 20));
        olxConsole.setLocation(820, 50);
    }

    public  void createWindowDimLog(){
        dimRiaConsole  = new ConsoleWindow("🔵 DimRia Controller",      new Color(20, 20, 40));
        dimRiaConsole.setLocation(420, 580);

    }

    public  void createWindowMainLog(){
        mainConsole = new ConsoleWindow("🏠 Головна консоль",       new Color(30, 30, 30));
        mainConsole .setLocation(0,   50);
    }

    public ConsoleWindow getOlxConsole() {return olxConsole;}

    public ConsoleWindow getDimRiaConsole() {return dimRiaConsole;}

    public ConsoleWindow getMainConsole() {return mainConsole;}

}
