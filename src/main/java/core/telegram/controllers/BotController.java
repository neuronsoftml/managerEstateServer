package core.telegram.controllers;

import core.telegram.model.UserSession;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.PrintStream;

public interface BotController {
    void handle(Update update, UserSession session, PrintStream botOut);
}
