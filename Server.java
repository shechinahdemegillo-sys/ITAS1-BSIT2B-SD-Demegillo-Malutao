import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Disaster Drill Room - server.
 * Features: group chat, drill alerts with acknowledgments, resource requests.
 *
 * Message format (fields separated by "|", text fields are Base64 encoded):
 *   Client to server: NAME, CHAT, ALERT, ACK, RESOURCE, HELP, QUIT
 *   Server to client: SYSTEM, ERROR, USERS, CHAT, ALERT, ACK, RESOURCE
 */
public class Server {
    private static final int PORT = 5000;
    private static final int MAX_NAME_LENGTH = 18;
    private static final int MAX_ALERT_LENGTH = 200;

    private static final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private static final Map<String, ClientHandler> nicknames = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> acknowledgments = new ConcurrentHashMap<>();
    private static final AtomicInteger nextAlertId = new AtomicInteger(1);


    public static void main(String[] args) {
        System.out.println("====================================");
        System.out.println("       DISASTER DRILL ROOM");
        System.out.println("          SERVER ONLINE");
        System.out.println("====================================");
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Waiting for clients on port " + PORT + "...");
            while (true) {
                Socket socket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(socket);
                clients.add(handler);
                new Thread(handler, "ClientHandler").start();
            }
        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }


    // ---------------------------------------------------------------- helpers
    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }

    private static String timeNow() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("hh:mm:ss a"));
    }


    // ---------------------------------------------------------------- broadcasting
    private static void broadcast(String message) {
        for (ClientHandler c : clients) c.send(message);
    }

    private static void sendUserList() {
        StringBuilder list = new StringBuilder();
        for (ClientHandler h : nicknames.values()) {
            if (list.length() > 0) list.append(",");
            list.append(encode(h.nickname));
        }
        broadcast("USERS|" + list);
    }

    private static void broadcastSystem(String text) {
        broadcast("SYSTEM|" + encode(text));
    }


    // ---------------------------------------------------------------- one thread per client
    static class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        volatile String nickname = "";
        private boolean closed = false;

        ClientHandler(Socket socket) { this.socket = socket; }

        void send(String message) {
            if (out != null) {
                synchronized (out) {
                    out.println(message);
                }
            }
        }

        private void sendSystem(String text) { send("SYSTEM|" + encode(text)); }

        private void sendError(String text) { send("ERROR|" + encode(text)); }

        @Override public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                String line;
                while ((line = in.readLine()) != null) {
                    handle(line);
                    if (closed) break;
                }
            } catch (IOException e) {
                System.out.println("A client disconnected.");
            } finally {
                disconnect();
            }
        }

        private void handle(String line) {
            try {
                String[] data = line.split("\\|", -1);
                String command = data[0];

                // Nobody can use the room before choosing a nickname
                boolean needsName = !command.equals("NAME") && !command.equals("HELP") && !command.equals("QUIT");
                if (needsName && nickname.isEmpty()) { sendError("Choose a nickname first."); return; }

                switch (command) {
                    case "NAME":
                        if (data.length > 1) setName(decode(data[1]));
                        break;
                    case "CHAT":
                        if (data.length > 1) chat(decode(data[1]));
                        break;
                    case "ALERT":
                        if (data.length > 1) sendAlert(decode(data[1]));
                        break;
                    case "ACK":
                        if (data.length > 1) acknowledge(data[1].trim());
                        break;
                    case "RESOURCE":
                        if (data.length > 2) requestResource(data[1], data[2]);
                        break;
                    case "HELP":
                        sendSystem("Commands: /name NewName, /help, /exit. Use the buttons for alerts and resource requests.");
                        break;
                    case "QUIT":
                        disconnect();
                        break;
                    default:
                        sendError("Unknown command.");
                }
            } catch (Exception e) {
                sendError("Could not understand that request.");
            }
        }

        // ------------------------------------------------------------ nicknames
        private void setName(String requested) {
            String clean = requested.trim();
            if (clean.isEmpty()) { sendError("Nickname is blank or already being used."); return; }
            if (clean.length() > MAX_NAME_LENGTH) { sendError("Nickname must be 1-" + MAX_NAME_LENGTH + " characters."); return; }

            String old;
            synchronized (nicknames) {   // check and reserve the name in one step so two users cannot take it together
                ClientHandler existing = nicknames.get(clean.toLowerCase());
                if (existing != null && existing != this) {
                    sendError("Nickname is blank or already being used."); return;
                }
                old = nickname;
                if (!old.isEmpty()) nicknames.remove(old.toLowerCase(), this);
                nickname = clean;
                nicknames.put(clean.toLowerCase(), this);
            }

            if (old.isEmpty()) {
                sendSystem("Welcome, " + clean + "!");
                broadcastSystem(clean + " joined the drill room.");
            } else if (!old.equals(clean)) {
                broadcastSystem(old + " changed name to " + clean + ".");
                renameInAcknowledgments(old, clean);
            }
            sendUserList();
        }

        /** Keeps acknowledgments linked to the person after a rename. */
        private void renameInAcknowledgments(String old, String now) {
            for (Set<String> names : acknowledgments.values()) {
                if (names.remove(old)) names.add(now);
            }
        }

        // ------------------------------------------------------------ group chat
        private void chat(String text) {
            String message = text.trim();
            if (message.isEmpty()) return;
            broadcast("CHAT|" + encode(nickname) + "|" + timeNow() + "|" + encode(message));
        }

        // ------------------------------------------------------------ feature 1: drill alerts
        private void sendAlert(String text) {
            String message = text.trim();
            if (message.isEmpty()) return;
            if (message.length() > MAX_ALERT_LENGTH) message = message.substring(0, MAX_ALERT_LENGTH);

            String id = "ALERT-" + nextAlertId.getAndIncrement();
            acknowledgments.put(id, ConcurrentHashMap.newKeySet());
            broadcast("ALERT|" + id + "|" + encode(nickname) + "|" + timeNow() + "|" + encode(message));
        }

        private void acknowledge(String id) {
            Set<String> names = acknowledgments.get(id);
            if (names == null) { sendSystem("Alert ID not found."); return; }
            if (names.add(nickname)) {
                broadcast("ACK|" + id + "|" + encode(nickname) + "|" + names.size());
            } else {
                sendSystem("You already acknowledged " + id + ".");
            }
        }

        // ------------------------------------------------------------ feature 2: resource requests
        private void requestResource(String item, String quantity) {
            if (item.isBlank() || quantity.isBlank()) { sendError("A resource and quantity are required."); return; }
            broadcast("RESOURCE|" + encode(nickname) + "|" + item + "|" + quantity + "|" + timeNow());
        }

        // ------------------------------------------------------------ cleanup
        private synchronized void disconnect() {
            if (closed) return;   // runs only once, even after QUIT
            closed = true;
            clients.remove(this);
            if (!nickname.isEmpty()) {
                nicknames.remove(nickname.toLowerCase(), this);
                broadcastSystem(nickname + " left the drill room.");
                sendUserList();
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}