package me.sumanvanan;

import me.sumanvanan.entity.Session;
import me.sumanvanan.entity.TelegramUser;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static me.sumanvanan.BotCommands.*;

public class DeliberatePracticeBot extends TelegramLongPollingBot {

    private static EntityManagerFactory entityManagerFactory = null;
    private Map<TelegramUser, LocalDateTime> activeUsers;

    DeliberatePracticeBot() {
        this.activeUsers = new HashMap<>();
    }

    public void onUpdateReceived(Update update) {

        if (update.hasMessage() && update.getMessage().hasText()) {

            Message receivedMessage = update.getMessage();
            String text = receivedMessage.getText();
            String reply = null;

            if (text.startsWith(HELP)) {
                reply = handleHelp();
            } else if (text.startsWith(START)) {
                TelegramUser user = insertUserIfNeeded(receivedMessage.getFrom());
                startSession(receivedMessage, user);
                reply = handleStart(receivedMessage);
            } else if (text.startsWith(END)) {
                Session session = endSession(receivedMessage);
                insertSession(session);
                reply = handleStop(session);
            } else if (text.startsWith(STATUS)) {
                reply = handleStatus();
            } else if (text.startsWith(CHEAT_DAY)) {
                Session session = createCheatDaySession(receivedMessage);
                insertSession(session);
                reply = handleCheatDay();
            } else if (text.startsWith(PAY_FINE)) {
                payFine(receivedMessage);
                reply = handlePayFine();
            }

            SendMessage sendMessage = new SendMessage() // Create a SendMessage object with mandatory fields
                    .setChatId(receivedMessage.getChatId())
                    .setText(reply);

            try {
                execute(sendMessage); // Call method to send the message
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
        }
    }

    private String handleHelp() {
        return "You can use the following commands\n" +
                "/start - Check in a new session\n" +
                "/end - End the currently checked in session\n" +
                "/status - See which users are currently checked in\n" +
                "/cheatday - Use your cheat day\n" +
                "/payfine - Add $2 to your outstanding fine";
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
        session.setActivitiesCompleted(activitiesCompleted);
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
            entityManagerFactory = Persistence.createEntityManagerFactory("Demo");
        }
        return entityManagerFactory.createEntityManager();
    }

    public String getBotUsername() {
        return "deliberate_practice_test_bot";
    }

    @Override
    public String getBotToken() {
        return System.getenv("BOT_FATHER_HTTP_API_TOKEN");
    }
}