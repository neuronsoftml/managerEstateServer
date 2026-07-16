package core.telegram.controllers;

import core.telegram.model.UserSession;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.PrintStream;

public class SearchAdsController implements BotController {
    @Override
    public void handle(Update update, UserSession session, PrintStream botOut) {
        // Тут вся ваша логіка пошуку, яка раніше була в MainBot
        // ...
    }
}
