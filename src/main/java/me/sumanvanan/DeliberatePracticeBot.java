package me.sumanvanan;

import com.jakewharton.fliptables.FlipTable;
import me.sumanvanan.entity.Session;
import me.sumanvanan.entity.TelegramUser;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiValidationException;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static me.sumanvanan.BotCommands.*;

public class DeliberatePracticeBot extends TelegramLongPollingBot {

    private static EntityManagerFactory entityManagerFactory = null;
    private Map<TelegramUser, LocalDateTime> activeUsers;

    DeliberatePracticeBot() {
        this.activeUsers = new HashMap<>();
    }

    @Override
    public void onUpdateReceived(Update update) {

        if (update.hasMessage() && update.getMessage().hasText()) {

            Message receivedMessage = update.getMessage();
            String text = receivedMessage.getText();
            AtomicReference<String> reply = new AtomicReference<>("");

            if (text.startsWith(HELP)) {
                reply.set(handleHelp());
            } else if (text.startsWith(START)) {
                TelegramUser user = insertUserIfNeeded(receivedMessage.getFrom());
                startSession(receivedMessage, user);
                reply.set(handleStart(receivedMessage));
            } else if (text.startsWith(END)) {
                Session session = endSession(receivedMessage);
                insertSession(session);
                reply.set(handleStop(session));
            } else if (text.startsWith(STATUS)) {
                reply.set(handleStatus());
            } else if (text.startsWith(CHEAT_DAY)) {
                Session session = createCheatDaySession(receivedMessage);
                insertSession(session);
                reply.set(handleCheatDay());
            } else if (text.startsWith(PAY_FINE)) {
                payFine(receivedMessage);
                reply.set(handlePayFine());
            } else if (text.startsWith(USERS)) {
                reply.set(handleUsers());
            } else if (text.startsWith("/")) {
                reply.set("Sorry, I couldn't understand your command. Please try '/help' to see available commands.");
            }

            if (reply.get().isEmpty()) return;

            SendMessage sendMessage = new SendMessage() // Create a SendMessage object with mandatory fields
                    .setChatId(receivedMessage.getChatId())
                    .enableMarkdown(true)
                    .setText("```\n" + reply.get() + "```");

            try {
                execute(sendMessage); // Call method to send the message
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
        }
    }

    private String handleUsers() {
        List<TelegramUser> users = getAllUsers();

        String[] headers = { "Name", "Fine" };
        String[][] data = new String[users.size()][2];
        for (int i = 0; i < users.size(); i++) {
            String[] row = new String[2];
            TelegramUser user = users.get(i);
            row[0] = user.getFirstName();
            row[1] = "$" + user.getFine();
            data[i] = row;
        }

        return FlipTable.of(headers, data);
    }

    private String handleHelp() {

        String[] headers = { "Command", "Description" };
        String[][] data = {
                { "/start", "heck in a new session" },
                { "/end", "End the currently checked in session" },
                { "/status", "See which users are currently checked in" },
                { "/cheatday", "Use your cheat day (no validation added yet)" },
                { "/payfine", "Add $2 to your outstanding fine" },
                { "/users", "List all users and their data" },
        };

        return "You can use the following commands:\n" + FlipTable.of(headers, data);
    }

    private String handlePayFine() {
        return "Your $2 fine has been added.";
    }

    private void payFine(Message receivedMessage) {
        User user = receivedMessage.getFrom();
        TelegramUser telegramUser = getUser(user.getId());
        Integer currentFine = telegramUser.getFine();
        telegramUser.setFine(currentFine + 2);
        updateUser(telegramUser);
    }

    private String handleCheatDay() {
        return "Your cheat day has been recorded.";
    }

    private Session createCheatDaySession(Message receivedMessage) {
        User user = receivedMessage.getFrom();
        TelegramUser telegramUser = getUser(user.getId());

        long secondsSinceEpoch = receivedMessage.getDate().longValue();
        LocalDateTime currentDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(secondsSinceEpoch), ZoneId.systemDefault());

        Session session = new Session();
        session.setStart(currentDateTime);
        session.setEnd(currentDateTime);
        session.setUser(telegramUser);
        session.setCheatDay(true);
        return session;
    }

    private String handleStatus() {
        if (activeUsers.isEmpty())
            return "No users are currently checked in.";
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<TelegramUser, LocalDateTime> pair : activeUsers.entrySet()) {
            Duration checkedInDuration = Duration.between(pair.getValue(), LocalDateTime.now());
            builder.append(pair.getKey().getFirstName()).append(" has been checked in for ")
                    .append(humanReadableFormat(checkedInDuration))
                    .append(".\n");
        }
        return builder.toString();
    }

    private Session endSession(Message receivedMessage) {
        User user = receivedMessage.getFrom();
        TelegramUser telegramUser = getUser(user.getId());
        if (!activeUsers.containsKey(telegramUser))
            throw new RuntimeException("User tried to stop a non-existent session");

        String text = receivedMessage.getText();
        String activitiesCompleted = text.substring(END.length());

        LocalDateTime startDateTime = activeUsers.get(telegramUser);
        long secondsSinceEpoch = receivedMessage.getDate().longValue();
        LocalDateTime endDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(secondsSinceEpoch), ZoneId.systemDefault());
        Session session = new Session();
        session.setStart(startDateTime);
        session.setEnd(endDateTime);
        if (!activitiesCompleted.isEmpty())
            session.setActivitiesCompleted(activitiesCompleted.trim());
        session.setUser(telegramUser);
        return session;
    }

    private void startSession(Message receivedMessage, TelegramUser user) {
        long secondsSinceEpoch = receivedMessage.getDate().longValue();
        LocalDateTime startDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(secondsSinceEpoch), ZoneId.systemDefault());
        activeUsers.put(user, startDateTime); // fixme: Should I modifying global state here?
    }

    private String handleStart(Message receivedMessage) {
        User user = receivedMessage.getFrom();
        return "Hello, " + user.getFirstName() + "! All the best for your studying!";
    }

    private TelegramUser insertUserIfNeeded(User user) {
        TelegramUser telegramUser = getUser(user.getId());
        // if user does not exist in DB
        if (telegramUser == null) {
            TelegramUser userToBeCreated = new TelegramUser();
            userToBeCreated.setId(user.getId());
            userToBeCreated.setFirstName(user.getFirstName());
            userToBeCreated.setLastName(user.getLastName());
            userToBeCreated.setUserName(user.getUserName());
            insertUser(userToBeCreated);
            return userToBeCreated;
        }
        return telegramUser;
    }

    private String handleStop(Session session) {
        Duration sessionDuration = Duration.between(session.getStart(), session.getEnd());
        TelegramUser telegramUser = session.getUser();
        if (activeUsers.containsKey(telegramUser)) {
            activeUsers.remove(telegramUser);
            return String.format("Great job, %s! You have been checked in for %s.", telegramUser.getFirstName(), humanReadableFormat(sessionDuration));
        }
        return "You are not checked in. Please use '/start' to check in first, before checking out.";
    }

    private static String humanReadableFormat(Duration duration) {
        return duration.toString()
                .substring(2)
                .replaceAll("(\\d[HMS])(?!$)", "$1 ")
                .toLowerCase();
    }

    private static List<TelegramUser> getAllUsers() {
        EntityManager entityManager = openEntityManager();
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        CriteriaQuery<TelegramUser> criteriaQuery = criteriaBuilder.createQuery(TelegramUser.class);
        Root<TelegramUser> userRoot = criteriaQuery.from(TelegramUser.class);
        CriteriaQuery<TelegramUser> all = criteriaQuery.select(userRoot);
        TypedQuery<TelegramUser> allQuery = entityManager.createQuery(all);
        List<TelegramUser> users = allQuery.getResultList();
        entityManager.close();
        return users;
    }

    private static TelegramUser getUser(Integer id) {
        EntityManager entityManager = openEntityManager();
        TelegramUser user = entityManager.find(TelegramUser.class, id);
        entityManager.close();
        return user;
    }

    private static void updateUser(TelegramUser updatedUser) {
        EntityManager entityManager = openEntityManager();
        entityManager.getTransaction().begin();
        entityManager.merge(updatedUser);
        entityManager.getTransaction().commit();
    }

    private static void insertUser(TelegramUser user) {
        EntityManager entityManager = openEntityManager();
        entityManager.getTransaction().begin();
        entityManager.persist(user);
        entityManager.getTransaction().commit();
    }

    private static void insertSession(Session session) {
        EntityManager entityManager = openEntityManager();
        entityManager.getTransaction().begin();
        entityManager.persist(session);
        entityManager.getTransaction().commit();
    }

    private static EntityManager openEntityManager() {
        if (entityManagerFactory == null) {
            entityManagerFactory = Persistence.createEntityManagerFactory("test-persistence-unit");
        }
        return entityManagerFactory.createEntityManager();
    }

    @Override
    public String getBotUsername() {
        return "deliberate_practice_test_bot";
    }

    @Override
    public String getBotToken() {
        return System.getenv("BOT_FATHER_HTTP_API_TOKEN");
    }
}