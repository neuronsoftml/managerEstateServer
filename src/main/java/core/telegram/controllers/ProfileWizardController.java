package core.telegram.controllers;

import core.telegram.main.InlineKeyboardFactory;
import core.telegram.model.BotState;
import core.telegram.model.TenantApplicationForm;
import core.telegram.model.UserSession;
import model.City;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import core.serverDB.sqlite.ProjectDatabaseService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Контролер Wizard-опитувальника "Анкета пошуку житла".
 * <p>
 * Реалізує покроковий сценарій збору вимог орендаря згідно з ТЗ.
 * Накопичує дані в сесійному об'єкті {@link TenantApplicationForm} (отримується через {@code UserSession.getProfileDraft()}).
 * На фінальному екрані підтвердження здійснює персистентний запис або оновлення даних
 * у таблиці {@code user_profiles} за допомогою {@link ProjectDatabaseService#saveOrUpdateProfile}.
 * </p>
 * <p>
 * Архітектурно контролер є <b>Stateless</b> (без збереження стану). Він тримає лише фінальне посилання
 * на {@link TelegramSender} для виконання мережевих запитів. Увесь стан проходження кроків
 * та проміжні дані повністю делеговані об'єкту {@link UserSession}.
 * </p>
 */
public class ProfileWizardController {

    /** Вузький інтерфейс для надсилання та редагування повідомлень (той самий, що й в інших контролерах). */
    private final TelegramSender bot;

    /**
     * Конструктор контролера опитувальника.
     *
     * @param bot об'єкт, через який виконуються запити до Telegram API
     */
    public ProfileWizardController(TelegramSender bot) {
        this.bot = bot;
    }

    // ==========================================
    // --- СТАРТ WIZARD'А ---
    // ==========================================

    /**
     * Ініціалізує новий сеанс анкетування користувача.
     * Скидає чернетки попередніх відповідей у сесії, обнуляє тимчасові прапорці
     * та переводить стан FSM у початковий крок очікування імені.
     *
     * @param chatId    ID чату користувача
     * @param messageId ID повідомлення-тригера (використовується як якір для inline-оновлень)
     * @param session   поточна сесія користувача
     * @throws Exception при помилках взаємодії з Telegram API
     */
    public void startWizard(long chatId, int messageId, UserSession session) throws Exception {
        clearTemporaryMessage(chatId, session);
        session.setProfileDraft(new TenantApplicationForm());
        session.getSelectedDistricts().clear();
        session.setAwaitingChildrenDetails(false);
        session.setAwaitingPetsDetails(false);
        session.setProfileEditMode(false);
        session.setWizardMessageId(messageId);
        session.setState(BotState.PROFILE_WAITING_NAME);
        renderStep(BotState.PROFILE_WAITING_NAME, chatId, messageId, session);
    }

    // ==========================================
    // --- ЦЕНТРАЛЬНИЙ ДИСПЕТЧЕР CALLBACK ---
    // ==========================================

    /**
     * Головний вхідний порт для обробки натискань на inline-кнопки (CallbackQuery).
     * Маршрутизує події відповідно до поточної бізнес-логіки кроку.
     *
     * @param chatId    ID чату користувача
     * @param messageId ID повідомлення, в якому відбувся клік
     * @param data      строкові дані натиснутої кнопки (callback_data)
     * @param session   поточна сесія користувача
     * @throws Exception при помилках Telegram API
     */
    public void handleCallback(long chatId, int messageId, String data, UserSession session) throws Exception {
        session.setWizardMessageId(messageId); // Завжди синхронізуємо актуальний якір для inline-редагування

        // Системні керуючі події опитувальника
        if (data.equals("PROFILE_CANCEL")) {
            cancelWizard(chatId, messageId, session);
            return;
        }
        if (data.equals("PROFILE_EDIT")) {
            renderEditMenu(chatId, messageId);
            return;
        }
        if (data.equals("EDIT_BACK_TO_CONFIRM")) {
            session.setState(BotState.PROFILE_CONFIRMATION);
            renderConfirmation(chatId, messageId, session);
            return;
        }
        if (data.equals("PROFILE_CONFIRM_SAVE")) {
            saveProfile(chatId, messageId, session);
            return;
        }

        // Перехід до редагування конкретного кроку з екрана підтвердження
        if (data.startsWith("EDIT_STEP_")) {
            BotState target = editStepToState(data.replace("EDIT_STEP_", ""));
            if (target != null) {
                session.setProfileEditMode(true);
                session.setState(target);
                renderStep(target, chatId, messageId, session);
            }
            return;
        }

        TenantApplicationForm form = session.getProfileDraft();

        // Обробка відповідей користувача на кроках опитування
        if (data.equals("PROFILE_DEPOSIT_YES") || data.equals("PROFILE_DEPOSIT_NO")) {
            form.setReadyForDeposit(data.endsWith("YES"));
            advance(session, chatId, messageId, BotState.PROFILE_WAITING_COMMISSION);

        } else if (data.equals("PROFILE_COMMISSION_YES") || data.equals("PROFILE_COMMISSION_NO")) {
            form.setReadyForCommission(data.endsWith("YES"));
            advance(session, chatId, messageId, BotState.PROFILE_WAITING_DISTRICTS);

        } else if (data.startsWith("PDISTRICT_") && !data.equals("PDISTRICT_DONE")) {
            // Реалізація логіки мультиселекту районів на базі City.values()
            String cityName = data.replace("PDISTRICT_", "");
            try {
                City city = City.valueOf(cityName);
                String label = city.getLabel();
                // Якщо район вже вибрано — прибираємо його, інакше додаємо в список
                if (!session.getSelectedDistricts().remove(label)) {
                    session.getSelectedDistricts().add(label);
                }
            } catch (IllegalArgumentException ignored) {
                // Запобігання помилкам, якщо структура енаму City змінилася
            }
            renderDistrictsStep(chatId, messageId, session); // Перерендер поточного списку з оновленими галочками

        } else if (data.equals("PDISTRICT_DONE")) {
            form.setPreferredDistricts(String.join(", ", session.getSelectedDistricts()));
            advance(session, chatId, messageId, BotState.PROFILE_WAITING_ROOMS);

        } else if (data.startsWith("PROOMS_")) {
            form.setRoomsType(roomsLabel(data));
            advance(session, chatId, messageId, BotState.PROFILE_WAITING_TERM);

        } else if (data.startsWith("PTERM_")) {
            form.setRentTerm(data.equals("PTERM_LONG") ? "Довгостроково (від 6 міс.)" : "Помісячно / Подобово");
            advance(session, chatId, messageId, BotState.PROFILE_WAITING_TENANTS);

        } else if (data.equals("PCHILDREN_YES")) {
            session.setAwaitingChildrenDetails(true); // Встановлюємо прапорець очікування вільного текстового введення
            renderChildrenDetailsPrompt(chatId, messageId);

        } else if (data.equals("PCHILDREN_NO")) {
            form.setHasChildren(false);
            form.setChildrenInfo(null);
            advance(session, chatId, messageId, BotState.PROFILE_WAITING_PETS);

        } else if (data.equals("PPETS_YES")) {
            session.setAwaitingPetsDetails(true); // Перемикаємо на режим збору текстових подробиць про тварин
            renderPetsDetailsPrompt(chatId, messageId);

        } else if (data.equals("PPETS_NO")) {
            form.setHasPets(false);
            form.setPetsInfo(null);
            advance(session, chatId, messageId, BotState.PROFILE_WAITING_EMPLOYMENT);

        } else if (data.startsWith("PEMP_")) {
            form.setEmploymentSphere(employmentLabel(data));
            advance(session, chatId, messageId, BotState.PROFILE_WAITING_WORK_FORMAT);

        } else if (data.startsWith("PWORK_")) {
            form.setWorkFormat(workFormatLabel(data));
            advance(session, chatId, messageId, BotState.PROFILE_WAITING_SMOKING);

        } else if (data.startsWith("PSMOKE_")) {
            form.setSmokingStatus(smokingLabel(data));
            advance(session, chatId, messageId, BotState.PROFILE_WAITING_CAR);

        } else if (data.equals("PCAR_YES") || data.equals("PCAR_NO")) {
            form.setHasCar(data.equals("PCAR_YES"));
            advance(session, chatId, messageId, BotState.PROFILE_WAITING_CRITICAL);

        } else if (data.equals("PCRITICAL_SKIP")) {
            form.setCriticalRequirements(null);
            advance(session, chatId, messageId, BotState.PROFILE_CONFIRMATION);
        }
    }

    // ==========================================
    // --- ЦЕНТРАЛЬНИЙ ДИСПЕТЧЕР ТЕКСТУ ---
    // ==========================================

    /**
     * Головний вхідний порт для обробки текстових відповідей користувача у чаті.
     *
     * @param chatId  ID чату користувача
     * @param text    введений текст повідомлення
     * @param session сесія користувача з даними про поточний стан
     * @return true, якщо повідомлення було спожите wizard'ом (і його варто видалити
     * з чату для охайності), false — якщо цей текст wizard'у не стосується.
     */
    public boolean handleText(long chatId, String text, UserSession session) throws Exception {
        int msgId = session.getWizardMessageId();
        TenantApplicationForm form = session.getProfileDraft();

        switch (session.getState()) {
            case PROFILE_WAITING_NAME -> {
                String name = text.trim();
                if (name.isEmpty() || name.length() > 100) {
                    renderNameStep(chatId, msgId, "⚠️ Введіть, будь ласка, коректне ім'я (до 100 символів).\n\n");
                    return true;
                }
                form.setUserName(name);
                advance(session, chatId, msgId, BotState.PROFILE_WAITING_PHONE);
                return true;
            }
            case PROFILE_WAITING_PHONE -> {
                String phone = normalizePhone(text);
                if (phone == null) {
                    renderPhoneStep(chatId, session, "⚠️ Не схоже на номер телефону. Скористайтеся кнопкою нижче.\n\n");
                    return true;
                }
                form.setPhoneNumber(phone);
                removeContactKeyboard(chatId, session);
                advance(session, chatId, msgId, BotState.PROFILE_WAITING_BUDGET);
                return true;
            }
            case PROFILE_WAITING_BUDGET -> {
                Integer budget = parsePositiveInt(text);
                if (budget == null) {
                    renderBudgetStep(chatId, msgId, "⚠️ Введіть, будь ласка, коректне позитивне число.\n\n");
                    return true;
                }
                form.setBudget(budget);
                advance(session, chatId, msgId, BotState.PROFILE_WAITING_DEPOSIT);
                return true;
            }
            case PROFILE_WAITING_TENANTS -> {
                form.setTenantsDescription(text.trim());
                form.setTenantsCount(extractFirstNumberOrDefault(text, 1));
                advance(session, chatId, msgId, BotState.PROFILE_WAITING_CHILDREN);
                return true;
            }
            case PROFILE_WAITING_CHILDREN -> {
                if (!session.isAwaitingChildrenDetails()) return false;
                form.setHasChildren(true);
                form.setChildrenInfo(text.trim());
                session.setAwaitingChildrenDetails(false);
                advance(session, chatId, msgId, BotState.PROFILE_WAITING_PETS);
                return true;
            }
            case PROFILE_WAITING_PETS -> {
                if (!session.isAwaitingPetsDetails()) return false;
                form.setHasPets(true);
                form.setPetsInfo(text.trim());
                session.setAwaitingPetsDetails(false);
                advance(session, chatId, msgId, BotState.PROFILE_WAITING_EMPLOYMENT);
                return true;
            }
            case PROFILE_WAITING_EMPLOYMENT -> {
                form.setEmploymentSphere(text.trim());
                advance(session, chatId, msgId, BotState.PROFILE_WAITING_WORK_FORMAT);
                return true;
            }
            case PROFILE_WAITING_CRITICAL -> {
                form.setCriticalRequirements(text.trim());
                advance(session, chatId, msgId, BotState.PROFILE_CONFIRMATION);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Обробляє контакт, надісланий системною кнопкою Telegram на кроці телефону. */
    public boolean handleContact(long chatId, String phoneNumber, UserSession session) throws Exception {
        if (session.getState() != BotState.PROFILE_WAITING_PHONE) return false;

        String phone = normalizePhone(phoneNumber);
        if (phone == null) {
            renderPhoneStep(chatId, session, "⚠️ Не вдалося прочитати номер. Спробуйте ще раз.\n\n");
            return true;
        }

        session.getProfileDraft().setPhoneNumber(phone);
        removeContactKeyboard(chatId, session);
        advance(session, chatId, session.getWizardMessageId(), BotState.PROFILE_WAITING_BUDGET);
        return true;
    }

    // ==========================================
    // --- ДОПОМІЖНЕ: ПЕРЕХІД МІЖ КРОКАМИ ---
    // ==========================================

    /**
     * Контролює перехід до наступного етапу опитування.
     * Якщо користувач перебував у режимі редагування конкретного пункту (profileEditMode == true),
     * то після фіксації відповіді він одразу повертається на фінальний екран підтвердження.
     * В іншому випадку — виконується стандартний крок вперед по лінійній структурі опитувальника.
     */
    private void advance(UserSession session, long chatId, int messageId, BotState next) throws Exception {
        if (session.isProfileEditMode()) {
            session.setProfileEditMode(false);
            session.setState(BotState.PROFILE_CONFIRMATION);
            renderConfirmation(chatId, messageId, session);
        } else {
            session.setState(next);
            renderStep(next, chatId, messageId, session);
        }
    }

    /**
     * Центральний маршрутизатор рендерингу кроків. Визначає метод візуалізації
     * інтерфейсу бота відповідно до заданого стану FSM.
     */
    private void renderStep(BotState state, long chatId, int messageId, UserSession session) throws Exception {
        switch (state) {
            case PROFILE_WAITING_NAME -> renderNameStep(chatId, messageId, "");
            case PROFILE_WAITING_PHONE -> renderPhoneStep(chatId, session, "");
            case PROFILE_WAITING_BUDGET -> renderBudgetStep(chatId, messageId, "");
            case PROFILE_WAITING_DEPOSIT -> renderDepositStep(chatId, messageId);
            case PROFILE_WAITING_COMMISSION -> renderCommissionStep(chatId, messageId);
            case PROFILE_WAITING_DISTRICTS -> renderDistrictsStep(chatId, messageId, session);
            case PROFILE_WAITING_ROOMS -> renderRoomsStep(chatId, messageId);
            case PROFILE_WAITING_TERM -> renderTermStep(chatId, messageId);
            case PROFILE_WAITING_TENANTS -> renderTenantsStep(chatId, messageId);
            case PROFILE_WAITING_CHILDREN -> renderChildrenStep(chatId, messageId);
            case PROFILE_WAITING_PETS -> renderPetsStep(chatId, messageId);
            case PROFILE_WAITING_EMPLOYMENT -> renderEmploymentStep(chatId, messageId);
            case PROFILE_WAITING_WORK_FORMAT -> renderWorkFormatStep(chatId, messageId);
            case PROFILE_WAITING_SMOKING -> renderSmokingStep(chatId, messageId);
            case PROFILE_WAITING_CAR -> renderCarStep(chatId, messageId);
            case PROFILE_WAITING_CRITICAL -> renderCriticalStep(chatId, messageId);
            case PROFILE_CONFIRMATION -> renderConfirmation(chatId, messageId, session);
            default -> { /* не стосується wizard'а */ }
        }
    }

    // ==========================================
    // --- РЕНДЕР КОЖНОГО КРОКУ ---
    // ==========================================

    private void renderNameStep(long chatId, int messageId, String prefix) throws Exception {
        edit(chatId, messageId, prefix + "👋 Як до вас звертатися? Введіть ваше ім'я:", cancelOnlyKeyboard());
    }

    private void renderPhoneStep(long chatId, UserSession session, String prefix) throws Exception {
        try { bot.clearInlineKeyboard(chatId, session.getWizardMessageId()); } catch (Exception ignored) { }
        sendPhoneRequest(chatId, prefix + "📱 Надішліть, будь ласка, ваш контактний номер телефону кнопкою нижче.", session);
    }

    private void renderBudgetStep(long chatId, int messageId, String prefix) throws Exception {
        edit(chatId, messageId, prefix + "💰 Введіть ваш максимальний місячний бюджет на оренду (в USD $):",
                cancelOnlyKeyboard());
    }

    private void renderDepositStep(long chatId, int messageId) throws Exception {
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"Так, готовий(а)", "PROFILE_DEPOSIT_YES"}, {"Ні, шукаю без застави", "PROFILE_DEPOSIT_NO"}
        }));
        kb.add(cancelRow());
        edit(chatId, messageId,
                "💰 <b>Депозит власнику:</b> Чи готові ви фінансово оплатити перший місяць + заставну суму " +
                        "(останній місяць) одразу при підписанні договору?\n\n" +
                        "<i>(Зазвичай застава є гарантією збереження майна та повертається при виїзді)</i>",
                kb);
    }

    private void renderCommissionStep(long chatId, int messageId) throws Exception {
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"Так, розглядатиму з комісією", "PROFILE_COMMISSION_YES"},
        }));
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"Ні, тільки без комісії (від власників)", "PROFILE_COMMISSION_NO"}
        }));
        kb.add(cancelRow());
        edit(chatId, messageId,
                "🤝 <b>Ріелторські послуги:</b> Чи розглядатимете ви варіанти житла, де потрібно додатково " +
                        "оплатити комісію ріелтору за підбір або супровід угоди?",
                kb);
    }

    private void renderDistrictsStep(long chatId, int messageId, UserSession session) throws Exception {
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        for (City city : City.values()) {
            boolean selected = session.getSelectedDistricts().contains(city.getLabel());
            String label = (selected ? "✅ " : "▫️ ") + city.getLabel();
            kb.add(InlineKeyboardFactory.createRow(new String[][]{{label, "PDISTRICT_" + city.name()}}));
        }
        kb.add(InlineKeyboardFactory.createRow(new String[][]{{"✅ Зберегти вибір", "PDISTRICT_DONE"}}));
        kb.add(cancelRow());
        edit(chatId, messageId, "📍 Оберіть бажані райони для проживання (можна обрати декілька):", kb);
    }

    private void renderRoomsStep(long chatId, int messageId) throws Exception {
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"1-к / Студія", "PROOMS_STUDIO"}, {"2-кімнатна", "PROOMS_2"}
        }));
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"3+ кімнатна", "PROOMS_3PLUS"}, {"Будь-яке планування", "PROOMS_ANY"}
        }));
        kb.add(cancelRow());
        edit(chatId, messageId, "🔢 Яке планування або кількість кімнат вас цікавить?", kb);
    }

    private void renderTermStep(long chatId, int messageId) throws Exception {
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"Довгостроково (від 6 міс.)", "PTERM_LONG"}
        }));
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"Помісячно / Подобово", "PTERM_SHORT"}
        }));
        kb.add(cancelRow());
        edit(chatId, messageId, "📅 Оберіть планований термін оренди житла:", kb);
    }

    private void renderTenantsStep(long chatId, int messageId) throws Exception {
        edit(chatId, messageId,
                "👥 Опишіть, будь ласка, хто саме проживатиме в квартирі?\n" +
                        "<i>(Наприклад: один хлопець, сімейна пара, дві дівчини-студентки тощо)</i>",
                cancelOnlyKeyboard());
    }

    private void renderChildrenStep(long chatId, int messageId) throws Exception {
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"Ні, без дітей", "PCHILDREN_NO"}, {"Так, є діти", "PCHILDREN_YES"}
        }));
        kb.add(cancelRow());
        edit(chatId, messageId, "👶 Чи будуть з вами проживати діти?", kb);
    }

    private void renderChildrenDetailsPrompt(long chatId, int messageId) throws Exception {
        edit(chatId, messageId, "✏️ Вкажіть, будь ласка, вік та кількість дітей:", cancelOnlyKeyboard());
    }

    private void renderPetsStep(long chatId, int messageId) throws Exception {
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"Ні, без тварин", "PPETS_NO"}, {"Так, є тварини", "PPETS_YES"}
        }));
        kb.add(cancelRow());
        edit(chatId, messageId, "🐾 Чи є у вас домашні улюбленці?", kb);
    }

    private void renderPetsDetailsPrompt(long chatId, int messageId) throws Exception {
        edit(chatId, messageId,
                "✏️ Вкажіть деталі про тварину\n<i>(наприклад: котик кастрований, або песик породи шпіц)</i>:",
                cancelOnlyKeyboard());
    }

    private void renderEmploymentStep(long chatId, int messageId) throws Exception {
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"IT", "PEMP_IT"}, {"Будівництво", "PEMP_CONSTRUCTION"}
        }));
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"Власна справа", "PEMP_BUSINESS"}, {"Сфера послуг", "PEMP_SERVICES"}
        }));
        kb.add(InlineKeyboardFactory.createRow(new String[][]{{"Студент", "PEMP_STUDENT"}}));
        kb.add(cancelRow());
        edit(chatId, messageId,
                "💼 Вкажіть сферу вашої зайнятості або професію:\n" +
                        "<i>(оберіть кнопку або надішліть свій варіант текстом)</i>",
                kb);
    }

    private void renderWorkFormatStep(long chatId, int messageId) throws Exception {
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"В офісі", "PWORK_OFFICE"}, {"Віддалено (з дому)", "PWORK_REMOTE"}, {"Змішаний формат", "PWORK_MIXED"}
        }));
        kb.add(cancelRow());
        edit(chatId, messageId,
                "💻 Який ваш формат роботи?\n" +
                        "<i>(Для тих, хто працює з дому, зазвичай важлива тиша та стабільний інтернет)</i>",
                kb);
    }

    private void renderSmokingStep(long chatId, int messageId) throws Exception {
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{{"Не палю", "PSMOKE_NO"}}));
        kb.add(InlineKeyboardFactory.createRow(new String[][]{{"Палю тільки на балконі", "PSMOKE_BALCONY"}}));
        kb.add(InlineKeyboardFactory.createRow(new String[][]{{"Палю електронні сигарети/vape", "PSMOKE_VAPE"}}));
        kb.add(cancelRow());
        edit(chatId, messageId, "🚬 Яке ваше ставлення до паління у помешканні?", kb);
    }

    private void renderCarStep(long chatId, int messageId) throws Exception {
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"Так, потрібне паркомісце", "PCAR_YES"}, {"Ні, авто немає", "PCAR_NO"}
        }));
        kb.add(cancelRow());
        edit(chatId, messageId, "🚗 Чи є у вас автомобіль і чи потрібна парковка/стоянка поруч із будинком?", kb);
    }

    private void renderCriticalStep(long chatId, int messageId) throws Exception {
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{{"⏩ Пропустити", "PCRITICAL_SKIP"}}));
        kb.add(cancelRow());
        edit(chatId, messageId,
                "🛠 Вкажіть ваші критичні побажання до квартири\n" +
                        "<i>(наприклад: обов'язково індивідуальне опалення, кондиціонер, посудомийка чи ванна замість душу)</i>:",
                kb);
    }

    // ==========================================
    // --- ЕКРАН ПІДТВЕРДЖЕННЯ ---
    // ==========================================

    /**
     * Будує та відображає зведене резюме анкети для фінальної перевірки користувачем.
     */
    private void renderConfirmation(long chatId, int messageId, UserSession session) throws Exception {
        TenantApplicationForm f = session.getProfileDraft();

        String districts = f.getPreferredDistricts() == null || f.getPreferredDistricts().isBlank()
                ? "Будь-які" : f.getPreferredDistricts();

        StringBuilder sb = new StringBuilder();
        sb.append("📋 <b>Ваша анкета пошуку житла:</b>\n\n");
        sb.append("👤 <b>Контактні дані:</b>\n");
        sb.append("• Ім'я: ").append(f.getUserName()).append("\n");
        sb.append("• Телефон: ").append(f.getPhoneNumber()).append("\n\n");
        sb.append("🔹 <b>Критерії пошуку:</b>\n");
        sb.append("• Бюджет: ").append(f.getBudget()).append(" $ / міс.\n");
        sb.append("• Застава (власнику): ").append(f.isReadyForDeposit() ? "Так, готовий(а)" : "Ні, шукаю без застави").append("\n");
        sb.append("• Комісія (ріелтору): ").append(f.isReadyForCommission() ? "Так, розглядатиму з комісією" : "Шукаю тільки без комісії").append("\n");
        sb.append("• Райони Чернівців: ").append(districts).append("\n");
        sb.append("• Планування: ").append(f.getRoomsType()).append("\n");
        sb.append("• Термін: ").append(f.getRentTerm()).append("\n\n");

        sb.append("👥 <b>Склад мешканців:</b>\n");
        sb.append("• Хто житиме: ").append(f.getTenantsDescription()).append("\n");
        sb.append("• Діти: ").append(f.isHasChildren() ? "Так (" + f.getChildrenInfo() + ")" : "Ні").append("\n");
        sb.append("• Тварини: ").append(f.isHasPets() ? "Так (" + f.getPetsInfo() + ")" : "Ні").append("\n\n");

        sb.append("💼 <b>Професійний профіль:</b>\n");
        sb.append("• Зайнятість: ").append(f.getEmploymentSphere()).append("\n");
        sb.append("• Формат: ").append(f.getWorkFormat()).append("\n\n");

        sb.append("🚗 <b>Побутові деталі:</b>\n");
        sb.append("• Паління: ").append(f.getSmokingStatus()).append("\n");
        sb.append("• Парковка: ").append(f.isHasCar() ? "Потрібна біля будинку" : "Не потрібна").append("\n");
        sb.append("• Критичні вимоги: ").append(
                f.getCriticalRequirements() == null || f.getCriticalRequirements().isBlank()
                        ? "Не вказано" : f.getCriticalRequirements()
        ).append("\n\n");

        sb.append("❓ Все вказано правильно? Ви можете відредагувати дані або підтвердити збереження анкети.");

        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{{"✏️ Редагувати дані", "PROFILE_EDIT"}}));
        kb.add(InlineKeyboardFactory.createRow(new String[][]{
                {"✅ Підтвердити та відправити", "PROFILE_CONFIRM_SAVE"}, {"❌ Скасувати анкетування", "PROFILE_CANCEL"}
        }));

        edit(chatId, messageId, sb.toString(), kb);
    }

    /**
     * Відображає інтерактивну панель вибору пунктів для точкового коригування анкети.
     */
    private void renderEditMenu(long chatId, int messageId) throws Exception {
        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put("👤 Ім'я", "EDIT_STEP_NAME");
        buttons.put("📱 Телефон", "EDIT_STEP_PHONE");
        buttons.put("💰 Бюджет", "EDIT_STEP_BUDGET");
        buttons.put("💰 Застава", "EDIT_STEP_DEPOSIT");
        buttons.put("🤝 Комісія", "EDIT_STEP_COMMISSION");
        buttons.put("📍 Райони", "EDIT_STEP_DISTRICTS");
        buttons.put("🔢 Кімнати", "EDIT_STEP_ROOMS");
        buttons.put("📅 Термін оренди", "EDIT_STEP_TERM");
        buttons.put("👥 Склад мешканців", "EDIT_STEP_TENANTS");
        buttons.put("👶 Діти", "EDIT_STEP_CHILDREN");
        buttons.put("🐾 Тварини", "EDIT_STEP_PETS");
        buttons.put("💼 Зайнятість", "EDIT_STEP_EMPLOYMENT");
        buttons.put("💻 Формат роботи", "EDIT_STEP_WORK_FORMAT");
        buttons.put("🚬 Паління", "EDIT_STEP_SMOKING");
        buttons.put("🚗 Авто", "EDIT_STEP_CAR");
        buttons.put("🛠 Критичні вимоги", "EDIT_STEP_CRITICAL");

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setParseMode("HTML");
        edit.setText("✏️ Який пункт анкети бажаєте змінити?");
        edit.setReplyMarkup(InlineKeyboardFactory.createVertical(buttons, "⬅️ Назад до анкети", "EDIT_BACK_TO_CONFIRM"));
        bot.executeMethod(edit);
    }

    // ==========================================
    // --- ЗБЕРЕЖЕННЯ ТА СКАСУВАННЯ ---
    // ==========================================

    /**
     * Завершальний крок анкетування. Зберігає заповнений об'єкт у БД
     * та очищує кнопки під резюме анкетних даних для запобігання подвійних кліків.
     */
    private void saveProfile(long chatId, int messageId, UserSession session) throws Exception {
        clearTemporaryMessage(chatId, session);
        TenantApplicationForm f = session.getProfileDraft();
        f.setTelegramId(chatId);

        // Спроба зберегти або оновити запис у БД
        boolean ok = ProjectDatabaseService.saveOrUpdateProfile(f);

        // Затираємо inline-клавіатуру під анкетним повідомленням
        EditMessageReplyMarkup clear = new EditMessageReplyMarkup();
        clear.setChatId(String.valueOf(chatId));
        clear.setMessageId(messageId);
        clear.setReplyMarkup(null);
        try { bot.executeMethod(clear); } catch (Exception ignored) {}

        SendMessage result = new SendMessage();
        result.setChatId(String.valueOf(chatId));
        result.setParseMode("HTML");

        if (ok) {
            result.setText("🎉 <b>Анкету успішно збережено в системі!</b>\n\n" +
                    "Тепер орендодавці та ріелтори нашої спільноти зможуть бачити ваш запит у базі даних. " +
                    "Як тільки з'явиться квартира, що ідеально підпадає під ваші критерії, ми надішлемо вам " +
                    "автоматичне сповіщення прямо в цей чат!\n\n" +
                    "🚪 Для повернення в головне меню бота скористайтеся кнопкою нижче.");
        } else {
            result.setText("⚠️ Не вдалося зберегти анкету через технічну помилку. Спробуйте, будь ласка, ще раз пізніше.");
        }

        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{{"⬅️ Назад в меню", "BACK_TO_MENU"}}));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(kb);
        result.setReplyMarkup(markup);

        bot.executeMethod(result);

        session.setState(BotState.START);
        session.setProfileEditMode(false);
    }

    /**
     * Повністю перериває опитування користувача. Очищує сесію від чернеток даних
     * та повертає статус користувача на головне меню.
     */
    private void cancelWizard(long chatId, int messageId, UserSession session) throws Exception {
        removeContactKeyboard(chatId, session);
        session.setProfileDraft(new TenantApplicationForm());
        session.getSelectedDistricts().clear();
        session.setAwaitingChildrenDetails(false);
        session.setAwaitingPetsDetails(false);
        session.setProfileEditMode(false);
        session.setState(BotState.START);

        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(InlineKeyboardFactory.createRow(new String[][]{{"⬅️ Назад в меню", "BACK_TO_MENU"}}));

        edit(chatId, messageId, "❌ Анкетування скасовано. Введені дані не збережено.", kb);
    }

    // ==========================================
    // --- ДРІБНІ ХЕЛПЕРИ ---
    // ==========================================

    /**
     * Скорочений внутрішній обгортковий метод для виконання редагування текстових блоків повідомлень у Telegram.
     */
    private void edit(long chatId, int messageId, String text, List<List<InlineKeyboardButton>> keyboardRows) throws Exception {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setParseMode("HTML");
        edit.setText(text);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(keyboardRows);
        edit.setReplyMarkup(markup);

        bot.executeMethod(edit);
    }

    /**
     * ReplyKeyboardMarkup не можна додати через editMessageText, тому запит контакту
     * завжди надсилається новим повідомленням, а його ID зберігається в сесії.
     */
    private void sendPhoneRequest(long chatId, String text, UserSession session) throws Exception {
        clearTemporaryMessage(chatId, session);

        KeyboardButton contactButton = new KeyboardButton("📱 Надіслати номер телефону");
        contactButton.setRequestContact(true);
        KeyboardRow row = new KeyboardRow();
        row.add(contactButton);

        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row));
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(true);

        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        message.setReplyMarkup(keyboard);
        Message sent = bot.executeMethod(message);
        if (sent != null) session.setLastTemporaryMessageId(sent.getMessageId());
    }

    /** Прибирає системну клавіатуру та службове повідомлення після завершення кроку. */
    private void removeContactKeyboard(long chatId, UserSession session) throws Exception {
        if (session.getLastTemporaryMessageId() == 0) return;

        SendMessage remove = new SendMessage(String.valueOf(chatId), "✅ Номер телефону отримано.");
        ReplyKeyboardRemove markup = new ReplyKeyboardRemove();
        markup.setRemoveKeyboard(true);
        remove.setReplyMarkup(markup);
        Message sent = bot.executeMethod(remove);
        clearTemporaryMessage(chatId, session);
        if (sent != null) {
            try { bot.deleteMessage(chatId, sent.getMessageId()); } catch (Exception ignored) { }
        }
    }

    private void clearTemporaryMessage(long chatId, UserSession session) {
        int messageId = session.getLastTemporaryMessageId();
        if (messageId == 0) return;
        try { bot.deleteMessage(chatId, messageId); } catch (Exception ignored) { }
        session.setLastTemporaryMessageId(0);
    }

    private List<List<InlineKeyboardButton>> cancelOnlyKeyboard() {
        List<List<InlineKeyboardButton>> kb = new ArrayList<>();
        kb.add(cancelRow());
        return kb;
    }

    private List<InlineKeyboardButton> cancelRow() {
        return InlineKeyboardFactory.createRow(new String[][]{{"❌ Скасувати", "PROFILE_CANCEL"}});
    }

    /**
     * Фільтрує та парсить вхідний рядок, повертаючи позитивне ціле число.
     * Запобігає крашу програми у випадку некоректного текстового вводу від користувача.
     */
    private Integer parsePositiveInt(String text) {
        try {
            int val = Integer.parseInt(text.trim().replaceAll("[^0-9]", ""));
            return val > 0 ? val : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Нормалізує номер телефону користувача: видаляє всі нечислові символи за винятком
     * лідируючого "+". Забезпечує базову перевірку довжини для запобігання завантаженню фейкових даних.
     */
    private String normalizePhone(String text) {
        String trimmed = text.trim();
        boolean hasPlus = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.length() < 9 || digits.length() > 15) return null;
        return (hasPlus ? "+" : "") + digits;
    }

    /**
     * Спроба автоматично виділити перше число з довільного опису мешканців.
     * Якщо число не знайдене в описі, повертається значення за замовчуванням (defaultValue = 1).
     */
    private int extractFirstNumberOrDefault(String text, int defaultValue) {
        Matcher m = Pattern.compile("\\d+").matcher(text);
        if (m.find()) {
            try { return Integer.parseInt(m.group()); } catch (Exception ignored) {}
        }
        return defaultValue;
    }

    private String roomsLabel(String data) {
        return switch (data) {
            case "PROOMS_STUDIO" -> "1-к / Студія";
            case "PROOMS_2" -> "2-кімнатна";
            case "PROOMS_3PLUS" -> "3+ кімнатна";
            default -> "Будь-яке планування";
        };
    }

    private String employmentLabel(String data) {
        return switch (data) {
            case "PEMP_IT" -> "IT";
            case "PEMP_CONSTRUCTION" -> "Будівництво";
            case "PEMP_BUSINESS" -> "Власна справа";
            case "PEMP_SERVICES" -> "Сфера послуг";
            case "PEMP_STUDENT" -> "Студент";
            default -> "Не вказано";
        };
    }

    private String workFormatLabel(String data) {
        return switch (data) {
            case "PWORK_OFFICE" -> "В офісі";
            case "PWORK_REMOTE" -> "Віддалено (з дому)";
            default -> "Змішаний формат";
        };
    }

    private String smokingLabel(String data) {
        return switch (data) {
            case "PSMOKE_NO" -> "Не палю";
            case "PSMOKE_BALCONY" -> "Палю тільки на балконі";
            case "PSMOKE_VAPE" -> "Палю електронні сигарети/vape";
            default -> "Не вказано";
        };
    }

    /**
     * Перетворює текстовий ідентифікатор поля у відповідний стан FSM бота.
     * Використовується для переходу на потрібний крок при покроковому редагуванні анкети.
     */
    private BotState editStepToState(String suffix) {
        return switch (suffix) {
            case "NAME" -> BotState.PROFILE_WAITING_NAME;
            case "PHONE" -> BotState.PROFILE_WAITING_PHONE;
            case "BUDGET" -> BotState.PROFILE_WAITING_BUDGET;
            case "DEPOSIT" -> BotState.PROFILE_WAITING_DEPOSIT;
            case "COMMISSION" -> BotState.PROFILE_WAITING_COMMISSION;
            case "DISTRICTS" -> BotState.PROFILE_WAITING_DISTRICTS;
            case "ROOMS" -> BotState.PROFILE_WAITING_ROOMS;
            case "TERM" -> BotState.PROFILE_WAITING_TERM;
            case "TENANTS" -> BotState.PROFILE_WAITING_TENANTS;
            case "CHILDREN" -> BotState.PROFILE_WAITING_CHILDREN;
            case "PETS" -> BotState.PROFILE_WAITING_PETS;
            case "EMPLOYMENT" -> BotState.PROFILE_WAITING_EMPLOYMENT;
            case "WORK_FORMAT" -> BotState.PROFILE_WAITING_WORK_FORMAT;
            case "SMOKING" -> BotState.PROFILE_WAITING_SMOKING;
            case "CAR" -> BotState.PROFILE_WAITING_CAR;
            case "CRITICAL" -> BotState.PROFILE_WAITING_CRITICAL;
            default -> null;
        };
    }

    /** Чи стосується даного стану цей wizard (для маршрутизації тексту з PrivateMainBot). */
    public static boolean isWizardState(BotState state) {
        return state != null && state.name().startsWith("PROFILE_");
    }

    /** Чи стосується даний callback-префікс цього wizard'а (для маршрутизації з PrivateMainBot). */
    public static boolean isWizardCallback(String data) {
        return data.startsWith("PROFILE_") || data.startsWith("PDISTRICT_") || data.startsWith("PROOMS_")
                || data.startsWith("PTERM_") || data.startsWith("PCHILDREN_") || data.startsWith("PPETS_")
                || data.startsWith("PEMP_") || data.startsWith("PWORK_") || data.startsWith("PSMOKE_")
                || data.startsWith("PCAR_") || data.startsWith("PCRITICAL_") || data.startsWith("EDIT_STEP_")
                || data.equals("EDIT_BACK_TO_CONFIRM");
    }
}
