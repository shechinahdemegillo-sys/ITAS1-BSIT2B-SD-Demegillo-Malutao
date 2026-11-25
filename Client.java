import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Disaster Drill Room - client.
 * Features: group chat, drill alerts with acknowledgments, resource requests.
 *
 * Message format (fields separated by "|", text fields are Base64 encoded):
 *   Client to server: NAME, CHAT, ALERT, ACK, RESOURCE, HELP, QUIT
 *   Server to client: SYSTEM, ERROR, NAME_OK, USERS, CHAT, ALERT, ACK, RESOURCE
 */
public class Client extends JFrame {

    // ---------- Palette ----------
    private static final Color INK        = new Color(0x1F2A44);
    private static final Color INK_SOFT   = new Color(0x34425F);
    private static final Color PAGE       = new Color(0xF4F6FA);
    private static final Color CARD       = Color.WHITE;
    private static final Color LINE       = new Color(0xDDE2EC);
    private static final Color TEXT       = new Color(0x1E2433);
    private static final Color MUTED      = new Color(0x7A8399);
    private static final Color ACCENT     = new Color(0xD4492B);
    private static final Color ACCENT_DK  = new Color(0xB23A20);
    private static final Color ALERT_RED  = new Color(0xB3261E);
    private static final Color GREEN      = new Color(0x3E8E6B);
    private static final Color RED        = new Color(0xC0504D);
    private static final Color MINE_BG    = new Color(0xFCEBE6);

    // ---------- Fonts ----------
    private static final Font BASE  = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font BOLD  = BASE.deriveFont(Font.BOLD);
    private static final Font SMALL = BASE.deriveFont(11f);
    private static final Font TITLE = BASE.deriveFont(Font.BOLD, 18f);

    // ---------- Header ----------
    private final HintField serverIP = new HintField("Server IP");
    private final HintField serverPort = new HintField("Port");
    private final HintField nickname = new HintField("Nickname");
    private final RoundButton connectButton = new RoundButton("Connect", ACCENT, ACCENT_DK, Color.WHITE);
    private final StatusDot statusDot = new StatusDot();
    private final JLabel statusLabel = new JLabel("Offline");

    // ---------- Chat ----------
    private final JTextPane chatPane = new JTextPane();
    private final HintField messageInput = new HintField("Connect first to start chatting");

    // ---------- Alert banner ----------
    private final JPanel banner = new JPanel(new BorderLayout(12, 0));
    private final JLabel bannerLabel = new JLabel();
    private String bannerAlertId = null;

    // ---------- Side panels ----------
    private final DefaultListModel<String> userModel = new DefaultListModel<>();
    private final JList<String> userList = new JList<>(userModel);
    private final JLabel usersCount = new JLabel("0");
    private final DefaultListModel<Alert> alertModel = new DefaultListModel<>();
    private final JList<Alert> alertList = new JList<>(alertModel);
    private final DefaultListModel<Resource> resourceModel = new DefaultListModel<>();
    private final JList<Resource> resourceList = new JList<>(resourceModel);
    private RoundButton ackButton;

    // ---------- Networking ----------
    private Socket socket;
    private BufferedReader input;
    private PrintWriter output;
    private volatile boolean connected = false;
    private String myName = "";

    public Client() {
        setTitle("Disaster Drill Room - Client");
        setSize(1120, 760);
        setMinimumSize(new Dimension(900, 620));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        createGUI();
        setConnectedState(false);

        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                disconnect();
                dispose();
                System.exit(0);
            }
        });
    }

    // =====================================================================
    //  UI
    // =====================================================================
    private void createGUI() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(PAGE);
        setContentPane(root);

        JPanel north = new JPanel(new BorderLayout());
        north.add(buildHeader(), BorderLayout.NORTH);
        north.add(buildBanner(), BorderLayout.SOUTH);
        root.add(north, BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(14, 0));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(14, 14, 14, 14));
        body.add(buildChatCard(), BorderLayout.CENTER);
        body.add(buildSidebar(), BorderLayout.EAST);
        root.add(body, BorderLayout.CENTER);
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBackground(INK);
        header.setBorder(new EmptyBorder(12, 18, 12, 18));

        JLabel title = new JLabel("Disaster Drill Room");
        title.setFont(TITLE);
        title.setForeground(Color.WHITE);
        JLabel sub = new JLabel("Classroom emergency drills: alerts, acknowledgments and resource requests");
        sub.setFont(SMALL);
        sub.setForeground(new Color(0xB5BED3));
        JPanel titleBox = new JPanel();
        titleBox.setOpaque(false);
        titleBox.setLayout(new BoxLayout(titleBox, BoxLayout.Y_AXIS));
        titleBox.add(title);
        titleBox.add(sub);
        header.add(titleBox, BorderLayout.WEST);

        serverIP.setText("127.0.0.1");
        serverPort.setText("5000");
        nickname.setText("Student");
        serverIP.setPreferredSize(new Dimension(130, 32));
        serverPort.setPreferredSize(new Dimension(64, 32));
        nickname.setPreferredSize(new Dimension(130, 32));
        connectButton.setPreferredSize(new Dimension(110, 32));
        connectButton.addActionListener(e -> {
            if (connected) disconnect();
            else connect();
        });

        statusLabel.setFont(SMALL);
        statusLabel.setForeground(new Color(0xB5BED3));

        JPanel conn = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        conn.setOpaque(false);
        conn.add(statusDot);
        conn.add(statusLabel);
        conn.add(Box.createHorizontalStrut(6));
        conn.add(serverIP);
        conn.add(serverPort);
        conn.add(nickname);
        conn.add(connectButton);
        header.add(conn, BorderLayout.EAST);
        return header;
    }

    /** Red bar shown while there is an alert from someone else that you have not acknowledged. */
    private JComponent buildBanner() {
        banner.setBackground(ALERT_RED);
        banner.setBorder(new EmptyBorder(8, 18, 8, 18));
        bannerLabel.setFont(BOLD.deriveFont(14f));
        bannerLabel.setForeground(Color.WHITE);
        RoundButton bannerAck = new RoundButton("Acknowledge", Color.WHITE, new Color(0xF3DEDC), ALERT_RED);
        bannerAck.setPreferredSize(new Dimension(130, 30));
        bannerAck.addActionListener(e -> { if (bannerAlertId != null) send("ACK|" + bannerAlertId); });
        banner.add(bannerLabel, BorderLayout.CENTER);
        banner.add(bannerAck, BorderLayout.EAST);
        banner.setVisible(false);
        return banner;
    }

    private JComponent buildChatCard() {
        RoundPanel card = new RoundPanel(CARD, 14);
        card.setLayout(new BorderLayout(0, 0));
        card.setBorder(new EmptyBorder(6, 6, 10, 6));

        chatPane.setEditable(false);
        chatPane.setFont(BASE);
        chatPane.setForeground(TEXT);
        chatPane.setOpaque(false);
        chatPane.setBorder(new EmptyBorder(8, 10, 8, 10));
        JScrollPane scroll = new JScrollPane(chatPane);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        card.add(scroll, BorderLayout.CENTER);

        messageInput.setPreferredSize(new Dimension(100, 38));
        RoundButton sendButton = new RoundButton("Send", ACCENT, ACCENT_DK, Color.WHITE);
        sendButton.setPreferredSize(new Dimension(84, 38));
        sendButton.addActionListener(e -> sendMessage());
        messageInput.addActionListener(e -> sendMessage());

        JPanel sendRow = new JPanel(new BorderLayout(8, 0));
        sendRow.setOpaque(false);
        sendRow.setBorder(new EmptyBorder(6, 10, 0, 10));
        sendRow.add(messageInput, BorderLayout.CENTER);
        sendRow.add(sendButton, BorderLayout.EAST);
        card.add(sendRow, BorderLayout.SOUTH);
        return card;
    }

    private JComponent buildSidebar() {
        JPanel side = new JPanel(new GridBagLayout());
        side.setOpaque(false);
        side.setPreferredSize(new Dimension(340, 100));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;

        c.gridy = 0; c.weighty = 0.42;
        side.add(buildAlertsCard(), c);
        c.gridy = 1; c.weighty = 0.16; c.insets = new Insets(12, 0, 12, 0);
        side.add(buildUsersCard(), c);
        c.gridy = 2; c.weighty = 0.42; c.insets = new Insets(0, 0, 0, 0);
        side.add(buildResourcesCard(), c);
        return side;
    }

    private JComponent buildAlertsCard() {
        RoundPanel card = new RoundPanel(CARD, 14);
        card.setLayout(new BorderLayout(0, 6));
        card.setBorder(new EmptyBorder(12, 14, 12, 14));
        card.add(sectionTitle("Drill alerts"), BorderLayout.NORTH);

        alertList.setCellRenderer(new AlertCell());
        alertList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        alertList.setOpaque(false);
        alertList.addListSelectionListener(e -> updateAckButton());
        card.add(listScroll(alertList), BorderLayout.CENTER);

        RoundButton broadcast = new RoundButton("Broadcast Drill Alert", ACCENT, ACCENT_DK, Color.WHITE);
        ackButton = new RoundButton("Acknowledge", PAGE, LINE, TEXT);
        broadcast.addActionListener(e -> sendAlert());
        ackButton.addActionListener(e -> {
            Alert a = alertList.getSelectedValue();
            if (a != null) send("ACK|" + a.id);
        });
        JPanel row = new JPanel(new GridLayout(1, 2, 6, 0));
        row.setOpaque(false);
        row.add(broadcast); row.add(ackButton);
        row.setPreferredSize(new Dimension(100, 32));
        card.add(row, BorderLayout.SOUTH);
        updateAckButton();
        return card;
    }

    private JComponent buildUsersCard() {
        RoundPanel card = new RoundPanel(CARD, 14);
        card.setLayout(new BorderLayout(0, 6));
        card.setBorder(new EmptyBorder(12, 14, 12, 14));

        usersCount.setFont(SMALL);
        usersCount.setForeground(MUTED);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(sectionTitle("Online"), BorderLayout.WEST);
        head.add(usersCount, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);

        userList.setCellRenderer(new UserRenderer());
        userList.setOpaque(false);
        userList.setSelectionModel(new NoSelectionModel());
        userList.setFocusable(false);
        card.add(listScroll(userList), BorderLayout.CENTER);
        return card;
    }

    private JComponent buildResourcesCard() {
        RoundPanel card = new RoundPanel(CARD, 14);
        card.setLayout(new BorderLayout(0, 6));
        card.setBorder(new EmptyBorder(12, 14, 12, 14));
        card.add(sectionTitle("Resource requests"), BorderLayout.NORTH);

        resourceList.setCellRenderer(new ResourceCell());
        resourceList.setSelectionModel(new NoSelectionModel());
        resourceList.setOpaque(false);
        resourceList.setFocusable(false);
        card.add(listScroll(resourceList), BorderLayout.CENTER);

        RoundButton request = new RoundButton("Request Resource", ACCENT, ACCENT_DK, Color.WHITE);
        request.addActionListener(e -> requestResource());
        request.setPreferredSize(new Dimension(100, 32));
        card.add(request, BorderLayout.SOUTH);
        return card;
    }

    private JScrollPane listScroll(JComponent list) {
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        return sp;
    }

    private JLabel sectionTitle(String text) {
        JLabel l = new JLabel(text);
        l.setFont(BOLD);
        l.setForeground(TEXT);
        return l;
    }

    private void updateAckButton() {
        if (ackButton == null) return;
        Alert a = alertList.getSelectedValue();
        ackButton.setEnabled(a != null && connected && !a.ackedByMe);
    }

    private void setConnectedState(boolean on) {
        serverIP.setEnabled(!on);
        serverPort.setEnabled(!on);
        nickname.setEnabled(!on);
        connectButton.setEnabled(true);
        connectButton.setText(on ? "Disconnect" : "Connect");
        messageInput.setHint(on ? "Type a message…  (/help for commands)" : "Connect first to start chatting");
        statusDot.setOn(on);
        statusLabel.setText(on ? "Online" : "Offline");
    }

    // =====================================================================
    //  Networking
    // =====================================================================
    private String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private void connect() {
        final String ip = serverIP.getText().trim();
        final String user = nickname.getText().trim();
        if (ip.isEmpty() || user.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter the server IP and your nickname.");
            return;
        }
        int parsedPort;
        try { parsedPort = Integer.parseInt(serverPort.getText().trim()); }
        catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Port must be a number.");
            return;
        }
        if (parsedPort < 1 || parsedPort > 65535) {
            JOptionPane.showMessageDialog(this, "Port must be from 1 to 65535.");
            return;
        }
        if (user.length() > 18) {
            JOptionPane.showMessageDialog(this, "Nickname must be 1-18 characters.");
            return;
        }
        final int port = parsedPort;

        // Connect on a background thread so the window never freezes
        connectButton.setEnabled(false);
        connectButton.setText("Connecting…");
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, port), 4000);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                SwingUtilities.invokeLater(() -> {
                    socket = s;
                    input = in;
                    output = w;
                    connected = true;
                    send("NAME|" + encode(user));
                    setConnectedState(true);
                    addSystem("Connected to " + ip + ":" + port);
                    Thread listener = new Thread(this::receiveMessages, "ChatListener");
                    listener.setDaemon(true);
                    listener.start();
                    messageInput.requestFocusInWindow();
                });
            } catch (IOException ex) {
                SwingUtilities.invokeLater(() -> {
                    setConnectedState(false);
                    JOptionPane.showMessageDialog(this,
                        "Connection failed. Check the server IP, port, and network.\n\nDetails: " + ex.getMessage());
                });
            }
        }, "Connector").start();
    }

    private void receiveMessages() {
        final BufferedReader in = input;
        try {
            String line;
            while (connected && in != null && (line = in.readLine()) != null) {
                final String received = line;
                SwingUtilities.invokeLater(() -> showServerMessage(received));
            }
        } catch (IOException ignored) {
        } finally {
            SwingUtilities.invokeLater(() -> {
                if (connected) {
                    addSystem("Server connection ended.");
                    resetConnection();
                }
            });
        }
    }

    private void showServerMessage(String line) {
        try {
            String[] data = line.split("\\|", -1);
            switch (data[0]) {
                case "SYSTEM":
                    addSystem(decode(data[1]));
                    break;
                case "ERROR": {
                    String error = decode(data[1]);
                    addSystem("Error: " + error);
                    // Nickname rejected on first join: close this connection so the user can try again
                    if (myName.isEmpty() && error.startsWith("Nickname")) {
                        JOptionPane.showMessageDialog(this, error + "\nChoose another nickname and connect again.");
                        disconnect();
                    }
                    break;
                }
                case "NAME_OK":
                    myName = decode(data[1]);
                    nickname.setText(myName);
                    userList.repaint();
                    updateAckButton();
                    break;
                case "USERS":
                    userModel.clear();
                    if (data.length > 1 && !data[1].isEmpty()) {
                        for (String item : data[1].split(",")) userModel.addElement(decode(item));
                    }
                    usersCount.setText(String.valueOf(userModel.size()));
                    alertList.repaint();
                    break;
                case "CHAT":
                    addChat(decode(data[1]), data[2], decode(data[3]));
                    break;
                case "ALERT":
                    handleAlert(data[1], decode(data[2]), data[3], decode(data[4]));
                    break;
                case "ACK":
                    handleAck(data[1], decode(data[2]), Integer.parseInt(data[3].trim()));
                    break;
                case "RESOURCE":
                    handleResource(decode(data[1]), decode(data[2]), decode(data[3]), data[4]);
                    break;
            }
        } catch (Exception e) {
            addSystem("Received an invalid server message.");
        }
    }

    private void send(String message) {
        if (output != null && connected) output.println(message);
    }

    // =====================================================================
    //  Feature 1: drill alerts with acknowledgments
    // =====================================================================
    private void handleAlert(String id, String sender, String time, String message) {
        Alert a = new Alert(id, sender, time, message);
        alertModel.add(0, a);   // newest first
        addAlertLine("DRILL ALERT " + id + " from " + sender + " at " + time + ":  " + message);
        if (!sender.equalsIgnoreCase(myName)) Toolkit.getDefaultToolkit().beep();
        updateBanner();
        updateAckButton();
    }

    private void handleAck(String id, String name, int count) {
        for (int i = 0; i < alertModel.size(); i++) {
            Alert a = alertModel.get(i);
            if (a.id.equals(id)) {
                a.acks = count;
                if (name.equalsIgnoreCase(myName)) a.ackedByMe = true;
                break;
            }
        }
        addSystem(name + " acknowledged " + id + ". Total: " + count);
        alertList.repaint();
        updateBanner();
        updateAckButton();
    }

    private void updateBanner() {
        Alert pending = null;
        for (int i = 0; i < alertModel.size(); i++) {
            Alert a = alertModel.get(i);
            if (!a.ackedByMe && !a.sender.equalsIgnoreCase(myName)) { pending = a; break; }
        }
        if (pending == null || myName.isEmpty()) {
            bannerAlertId = null;
            banner.setVisible(false);
        } else {
            bannerAlertId = pending.id;
            bannerLabel.setText("DRILL ALERT from " + pending.sender + ":  " + pending.message);
            banner.setVisible(true);
        }
        banner.revalidate();
        banner.repaint();
    }

    private void sendAlert() {
        if (!connected) { addSystem("Connect to the server first."); return; }
        String alert = JOptionPane.showInputDialog(this,
                "Write the disaster drill announcement:",
                "Broadcast Drill Alert", JOptionPane.PLAIN_MESSAGE);
        if (alert != null && !alert.trim().isEmpty()) {
            send("ALERT|" + encode(alert.trim()));
        }
    }

    // =====================================================================
    //  Feature 2: resource requests
    // =====================================================================
    private void handleResource(String requester, String item, String details, String time) {
        resourceModel.add(0, new Resource(requester, item, details, time));
        addSystem(requester + " requested " + item + (details.isEmpty() ? "." : " (" + details + ")."));
    }

    private void requestResource() {
        if (!connected) { addSystem("Connect to the server first."); return; }
        JComboBox<String> choice = new JComboBox<>(new String[]{
                "First-aid kit", "Drinking water", "Flashlight", "Emergency blanket", "Other"});
        JTextField details = new JTextField(22);
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 6));
        panel.add(new JLabel("Choose a simulated resource:"));
        panel.add(choice);
        panel.add(new JLabel("Location or additional details:"));
        panel.add(details);
        int result = JOptionPane.showConfirmDialog(this, panel, "Resource Request",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            send("RESOURCE|" + encode((String) choice.getSelectedItem()) + "|" + encode(details.getText().trim()));
        }
    }

    // =====================================================================
    //  Chat actions and rendering
    // =====================================================================
    private void sendMessage() {
        String text = messageInput.getText().trim();
        if (text.isEmpty()) return;
        if (!connected) {
            addSystem("Not connected. Enter the server IP, port and nickname at the top, then click Connect.");
            return;
        }
        String lower = text.toLowerCase();
        if (lower.startsWith("/name ")) {
            String newName = text.substring(6).trim();
            if (!newName.isEmpty()) send("NAME|" + encode(newName));
        } else if (lower.equals("/help")) {
            send("HELP");
        } else if (lower.equals("/exit")) {
            disconnect();
        } else if (text.startsWith("/")) {
            addSystem("Unknown command. Type /help for commands.");
        } else {
            send("CHAT|" + encode(text));
        }
        messageInput.setText("");
    }

    private void disconnect() {
        if (connected) send("QUIT");
        resetConnection();
    }

    private void resetConnection() {
        connected = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        socket = null;
        input = null;
        output = null;
        myName = "";
        userModel.clear();
        alertModel.clear();
        resourceModel.clear();
        usersCount.setText("0");
        bannerAlertId = null;
        banner.setVisible(false);
        setConnectedState(false);
        updateAckButton();
    }

    private void addChat(String sender, String time, String text) {
        boolean mine = sender.equals(myName);
        StyledDocument doc = chatPane.getStyledDocument();

        SimpleAttributeSet head = new SimpleAttributeSet();
        StyleConstants.setFontFamily(head, BASE.getFamily());
        StyleConstants.setFontSize(head, 11);
        StyleConstants.setBold(head, true);
        StyleConstants.setForeground(head, mine ? ACCENT_DK : INK_SOFT);

        SimpleAttributeSet stamp = new SimpleAttributeSet(head);
        StyleConstants.setBold(stamp, false);
        StyleConstants.setForeground(stamp, MUTED);

        SimpleAttributeSet body = new SimpleAttributeSet();
        StyleConstants.setFontFamily(body, BASE.getFamily());
        StyleConstants.setFontSize(body, 14);
        StyleConstants.setForeground(body, TEXT);
        if (mine) StyleConstants.setBackground(body, MINE_BG);

        SimpleAttributeSet align = new SimpleAttributeSet();
        StyleConstants.setAlignment(align, mine ? StyleConstants.ALIGN_RIGHT : StyleConstants.ALIGN_LEFT);
        StyleConstants.setSpaceAbove(align, 8);

        try {
            int start = doc.getLength();
            doc.insertString(doc.getLength(), (mine ? "You" : sender) + "  ", head);
            doc.insertString(doc.getLength(), time + "\n", stamp);
            doc.insertString(doc.getLength(), " " + text + " \n", body);
            doc.setParagraphAttributes(start, doc.getLength() - start, align, false);
        } catch (BadLocationException ignored) {}
        scrollToEnd();
    }

    private void addSystem(String text) {
        appendCentered(text + "  (" + new SimpleDateFormat("HH:mm").format(new Date()) + ")", MUTED, false);
    }

    private void addAlertLine(String text) {
        appendCentered(text, ALERT_RED, true);
    }

    private void appendCentered(String text, Color color, boolean bold) {
        StyledDocument doc = chatPane.getStyledDocument();
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, BASE.getFamily());
        StyleConstants.setFontSize(a, 12);
        StyleConstants.setItalic(a, !bold);
        StyleConstants.setBold(a, bold);
        StyleConstants.setForeground(a, color);
        StyleConstants.setAlignment(a, StyleConstants.ALIGN_CENTER);
        StyleConstants.setSpaceAbove(a, 8);
        try {
            int start = doc.getLength();
            doc.insertString(doc.getLength(), text + "\n", a);
            doc.setParagraphAttributes(start, doc.getLength() - start, a, false);
        } catch (BadLocationException ignored) {}
        scrollToEnd();
    }

    private void scrollToEnd() {
        chatPane.setCaretPosition(chatPane.getDocument().getLength());
    }

    // =====================================================================
    //  Data classes
    // =====================================================================
    private static class Alert {
        final String id, sender, time, message;
        int acks = 0;
        boolean ackedByMe = false;
        Alert(String id, String sender, String time, String message) {
            this.id = id; this.sender = sender; this.time = time; this.message = message;
        }
    }

    private static class Resource {
        final String requester, item, details, time;
        Resource(String requester, String item, String details, String time) {
            this.requester = requester; this.item = item; this.details = details; this.time = time;
        }
    }

    // =====================================================================
    //  List renderers
    // =====================================================================
    private abstract static class CardCell extends JPanel {
        protected boolean selected;
        CardCell() {
            super(new BorderLayout(6, 0));
            setBorder(new EmptyBorder(6, 8, 6, 8));
            setOpaque(false);
        }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(selected ? MINE_BG : PAGE);
            g2.fillRoundRect(0, 2, getWidth(), getHeight() - 4, 10, 10);
            if (selected) {
                g2.setColor(ACCENT);
                g2.drawRoundRect(0, 2, getWidth() - 1, getHeight() - 5, 10, 10);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private class AlertCell extends CardCell implements ListCellRenderer<Alert> {
        private final JLabel title = new JLabel();
        private final JLabel meta = new JLabel();
        private final JLabel status = new JLabel();
        AlertCell() {
            title.setFont(BOLD);
            title.setForeground(ALERT_RED);
            meta.setFont(SMALL);
            meta.setForeground(MUTED);
            status.setFont(SMALL);
            JPanel left = new JPanel(new GridLayout(2, 1));
            left.setOpaque(false);
            left.add(title); left.add(meta);
            add(left, BorderLayout.CENTER);
            add(status, BorderLayout.EAST);
        }
        @Override
        public Component getListCellRendererComponent(JList<? extends Alert> l, Alert a, int i, boolean sel, boolean f) {
            selected = sel;
            title.setText(a.id + "  by " + a.sender);
            meta.setText(a.message);
            int total = Math.max(userModel.size(), a.acks);
            status.setText(a.acks + "/" + total + " ack" + (a.ackedByMe ? " (you)" : ""));
            status.setForeground(a.ackedByMe ? GREEN : ACCENT_DK);
            return this;
        }
    }

    private class ResourceCell extends CardCell implements ListCellRenderer<Resource> {
        private final JLabel title = new JLabel();
        private final JLabel meta = new JLabel();
        ResourceCell() {
            title.setFont(BOLD);
            title.setForeground(TEXT);
            meta.setFont(SMALL);
            meta.setForeground(MUTED);
            JPanel left = new JPanel(new GridLayout(2, 1));
            left.setOpaque(false);
            left.add(title); left.add(meta);
            add(left, BorderLayout.CENTER);
        }
        @Override
        public Component getListCellRendererComponent(JList<? extends Resource> l, Resource r, int i, boolean sel, boolean f) {
            selected = false;
            title.setText(r.item);
            meta.setText(r.requester + " · " + r.time + (r.details.isEmpty() ? "" : " · " + r.details));
            return this;
        }
    }

    private class UserRenderer extends JPanel implements ListCellRenderer<String> {
        private final JLabel name = new JLabel();
        private final StatusDot dot = new StatusDot();
        UserRenderer() {
            super(new FlowLayout(FlowLayout.LEFT, 6, 3));
            setOpaque(false);
            dot.setOn(true);
            name.setFont(BASE);
            add(dot); add(name);
        }
        @Override
        public Component getListCellRendererComponent(JList<? extends String> l, String v, int i, boolean s, boolean f) {
            boolean me = v.equalsIgnoreCase(myName);
            name.setText(me ? v + " (you)" : v);
            name.setFont(me ? BOLD : BASE);
            name.setForeground(TEXT);
            return this;
        }
    }

    private static class NoSelectionModel extends DefaultListSelectionModel {
        @Override public void setSelectionInterval(int a, int b) {}
        @Override public void addSelectionInterval(int a, int b) {}
    }

    // =====================================================================
    //  Custom components
    // =====================================================================
    private static class RoundPanel extends JPanel {
        private final Color fill;
        private final int arc;
        RoundPanel(Color fill, int arc) {
            this.fill = fill; this.arc = arc;
            setOpaque(false);
        }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.setColor(LINE);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static class RoundButton extends JButton {
        private final Color base, hover, fg;
        private boolean over;
        RoundButton(String text, Color base, Color hover, Color fg) {
            super(text);
            this.base = base; this.hover = hover; this.fg = fg;
            setFont(BOLD);
            setForeground(fg);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { over = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { over = false; repaint(); }
            });
        }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = !isEnabled() ? new Color(0xE3E6EE) : (over ? hover : base);
            g2.setColor(c);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.setFont(getFont());
            g2.setColor(isEnabled() ? fg : MUTED);
            FontMetrics fm = g2.getFontMetrics();
            int x = (getWidth() - fm.stringWidth(getText())) / 2;
            int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(getText(), x, y);
            g2.dispose();
        }
    }

    private static class HintField extends JTextField {
        private String hint;
        void setHint(String hint) { this.hint = hint; repaint(); }
        HintField(String hint) {
            this.hint = hint;
            setFont(BASE);
            setForeground(TEXT);
            setCaretColor(TEXT);
            setBorder(new CompoundBorder(new LineBorder(LINE, 1, true), new EmptyBorder(4, 10, 4, 10)));
        }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isEnabled() ? Color.WHITE : new Color(0xEEF0F5));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.dispose();
            super.paintComponent(g);
            if (getText().isEmpty() && !isFocusOwner()) {
                Graphics2D h = (Graphics2D) g.create();
                h.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                h.setColor(MUTED);
                h.setFont(getFont());
                Insets in = getInsets();
                h.drawString(hint, in.left, (getHeight() + h.getFontMetrics().getAscent() - h.getFontMetrics().getDescent()) / 2);
                h.dispose();
            }
        }
    }

    private static class StatusDot extends JComponent {
        private boolean on;
        StatusDot() { setPreferredSize(new Dimension(10, 10)); }
        void setOn(boolean on) { this.on = on; repaint(); }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(on ? GREEN : RED);
            g2.fillOval(0, 0, 9, 9);
            g2.dispose();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Client().setVisible(true));
    }
}