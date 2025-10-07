import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ServerxV3 extends JFrame {
    private JTabbedPane clientTabs;
    private JTextPane logArea, globalChatArea;
    private JTextField globalChatInput;
    private JButton sendGlobalBtn;
    private ExecutorService threadPool;

    private ServerSocket screenServer, chatServer, fileServer;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ServerxV3().setVisible(true));
    }

    public ServerxV3() {
        setTitle("🖥️ ViewerServe V3 - Multi Client ScreenShare Server");
        setSize(1300, 800);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        threadPool = Executors.newCachedThreadPool();

        // === LEFT PANEL (Client Streams) ===
        clientTabs = new JTabbedPane();
        clientTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        clientTabs.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int index = clientTabs.indexAtLocation(e.getX(), e.getY());
                    if (index >= 0) {
                        String clientName = clientTabs.getTitleAt(index);
                        JPopupMenu menu = new JPopupMenu();
                        JMenuItem closeItem = new JMenuItem("Disconnect " + clientName);
                        closeItem.addActionListener(a -> disconnectClient(clientName));
                        menu.add(closeItem);
                        menu.show(clientTabs, e.getX(), e.getY());
                    }
                }
            }
        });

        // === RIGHT PANEL (Logs + Global Chat + Server Chat) ===
        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new GridLayout(3, 1, 10, 10));
        rightPanel.setPreferredSize(new Dimension(200, 0));

        // 🟢 SERVER LOGS
        logArea = createStyledPane();
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("🧾 Server Logs"));

        // 🌍 GLOBAL CHAT
        globalChatArea = createStyledPane();
        JScrollPane globalChatScroll = new JScrollPane(globalChatArea);
        globalChatScroll.setBorder(BorderFactory.createTitledBorder("🌍 Global Chat"));

        globalChatInput = new JTextField();
        sendGlobalBtn = new JButton("Send");
        JPanel globalChatInputPanel = new JPanel(new BorderLayout(5, 5));
        globalChatInputPanel.add(globalChatInput, BorderLayout.CENTER);
        globalChatInputPanel.add(sendGlobalBtn, BorderLayout.EAST);
        sendGlobalBtn.addActionListener(e -> sendGlobalMessage());
        globalChatInput.addActionListener(e -> sendGlobalMessage());

        JPanel globalChatPanel = new JPanel(new BorderLayout(5, 5));
        globalChatPanel.add(globalChatScroll, BorderLayout.CENTER);
        globalChatPanel.add(globalChatInputPanel, BorderLayout.SOUTH);

        // 💬 SERVER-CLIENT CHAT (For future expansion)
        JTextPane serverChatArea = createStyledPane();
        JScrollPane serverChatScroll = new JScrollPane(serverChatArea);
        serverChatScroll.setBorder(BorderFactory.createTitledBorder("💬 Server Messages"));

        rightPanel.add(logScroll);
        rightPanel.add(globalChatPanel);
        rightPanel.add(serverChatScroll);

        // === MAIN SPLIT PANE ===
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, clientTabs, rightPanel);
        splitPane.setResizeWeight(0.75);
        add(splitPane, BorderLayout.CENTER);

        log("🟢 Server UI initialized...");
        threadPool.submit(this::startServers);
    }

    // === Styled JTextPane Creator ===
    private JTextPane createStyledPane() {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setContentType("text/html");
        pane.setText("<html><body style='font-family:Arial; font-size:13px;'></body></html>");
        return pane;
    }

    private void appendStyled(JTextPane pane, String html) {
        SwingUtilities.invokeLater(() -> {
            try {
                HTMLDocument doc = (HTMLDocument) pane.getDocument();
                HTMLEditorKit kit = (HTMLEditorKit) pane.getEditorKit();
                kit.insertHTML(doc, doc.getLength(), html, 0, 0, null);
                pane.setCaretPosition(doc.getLength());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // === SERVER CORE ===
    private void startServers() {
        try {
            screenServer = new ServerSocket(5000);
            chatServer = new ServerSocket(5001);
            fileServer = new ServerSocket(5002);

            log("✅ Open ports 5000 (screen), 5001 (chat), 5002 (files)");
            while (true) {
                Socket screenSocket = screenServer.accept();
                DataInputStream dis = new DataInputStream(screenSocket.getInputStream());
                String nickname = dis.readUTF();

                log("💻 " + nickname + " connecting...");
                DataOutputStream dos = new DataOutputStream(screenSocket.getOutputStream());
                dos.writeUTF("start_screenshare");

                Socket chatSocket = chatServer.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(chatSocket.getInputStream(), "UTF-8"));
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(chatSocket.getOutputStream(), "UTF-8"), true);
                String chatNick = reader.readLine();

                ClientPanel panel = new ClientPanel(chatNick, writer);
                clients.put(chatNick, new ClientHandler(chatNick, screenSocket, chatSocket, panel));

                SwingUtilities.invokeLater(() -> {
                    clientTabs.addTab(chatNick, panel);
                    log("✅ " + chatNick + " connected (" + clients.size() + " active clients)");
                    appendStyled(globalChatArea, "<div style='color:green;'>✅ " + chatNick + " joined the server.</div>");
                });

                threadPool.submit(() -> receiveScreen(chatNick));
                threadPool.submit(() -> receiveChat(chatNick));
                threadPool.submit(this::handleFileUploads);
            }
        } catch (IOException e) {
            log("❌ Server error: " + e.getMessage());
        }
    }

    private void receiveScreen(String nickname) {
        ClientHandler handler = clients.get(nickname);
        try (DataInputStream dis = new DataInputStream(handler.screenSocket.getInputStream())) {
            while (true) {
                int len = dis.readInt();
                byte[] data = dis.readNBytes(len);
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                if (img != null) handler.panel.updateImage(img);
            }
        } catch (IOException e) {
            log("⚠️ Stream lost: " + nickname);
            disconnectClient(nickname);
        }
    }

    private void receiveChat(String nickname) {
        ClientHandler handler = clients.get(nickname);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(handler.chatSocket.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("/chat ")) {
                    String msg = nickname + ": " + line.substring(6);
                    handler.panel.appendChat(msg);
                    broadcast("/chat " + msg, nickname);
                    appendStyled(globalChatArea, "<div><b>" + nickname + ":</b> " + line.substring(6) + "</div>");
                }
            }
        } catch (IOException e) {
            log("❌ Chat lost for " + nickname);
            disconnectClient(nickname);
        }
    }

    private void handleFileUploads() {
        try {
            Socket fileSocket = fileServer.accept();
            DataInputStream dis = new DataInputStream(fileSocket.getInputStream());
            String sender = dis.readUTF();
            String fileName = dis.readUTF();
            long fileSize = dis.readLong();

            File uploadDir = new File("uploads");
            if (!uploadDir.exists()) uploadDir.mkdirs();
            File file = new File(uploadDir, UUID.randomUUID() + "_" + fileName);

            try (FileOutputStream fos = new FileOutputStream(file)) {
                byte[] buf = new byte[8192];
                long remaining = fileSize;
                while (remaining > 0) {
                    int r = dis.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (r == -1) break;
                    fos.write(buf, 0, r);
                    remaining -= r;
                }
            }

            log("🖼️ Image received: " + fileName + " from " + sender);
            appendStyled(globalChatArea, "<div><b>" + sender + "</b> shared an image:</div>"
                    + "<img src='file:" + file.getAbsolutePath() + "' style='max-width:300px; max-height:200px;'><br>");
            broadcast("/image " + sender + " " + file.getName() + " " + fileName, null);
        } catch (IOException e) {
            log("❌ File upload error: " + e.getMessage());
        }
    }

    private void sendGlobalMessage() {
        String msg = globalChatInput.getText().trim();
        if (!msg.isEmpty()) {
            appendStyled(globalChatArea, "<div style='color:blue;'><b>Server:</b> " + msg + "</div>");
            broadcast("/chat Server: " + msg, null);
            globalChatInput.setText("");
        }
    }

    private void broadcast(String message, String exclude) {
        for (ClientHandler ch : clients.values()) {
            if (!ch.nickname.equals(exclude)) ch.panel.writer.println(message);
        }
    }

    private void disconnectClient(String name) {
        ClientHandler handler = clients.remove(name);
        if (handler != null) {
            try {
                handler.screenSocket.close();
                handler.chatSocket.close();
            } catch (IOException ignored) {}
            SwingUtilities.invokeLater(() -> {
                for (int i = 0; i < clientTabs.getTabCount(); i++) {
                    if (clientTabs.getTitleAt(i).equals(name)) {
                        clientTabs.removeTabAt(i);
                        break;
                    }
                }
                log("🔴 " + name + " disconnected (" + clients.size() + " active)");
                appendStyled(globalChatArea, "<div style='color:red;'>🔴 " + name + " disconnected.</div>");
            });
        }
    }

    // === UTIL ===
    private void log(String msg) {
        appendStyled(logArea, "<div>" + msg + "</div>");
    }

    // === INNER CLASSES ===
    static class ClientHandler {
        String nickname;
        Socket screenSocket, chatSocket;
        ClientPanel panel;
        ClientHandler(String n, Socket s, Socket c, ClientPanel p) {
            nickname = n; screenSocket = s; chatSocket = c; panel = p;
        }
    }

    static class ClientPanel extends JPanel {
        JLabel screen;
        JTextArea chat;
        JTextField input;
        JButton send;
        PrintWriter writer;

        ClientPanel(String name, PrintWriter w) {
            this.writer = w;
            setLayout(new BorderLayout(5, 5));

            screen = new JLabel("Waiting for stream...", JLabel.CENTER);
            screen.setPreferredSize(new Dimension(900, 600));
            screen.setOpaque(true);
            screen.setBackground(Color.BLACK);
            screen.setForeground(Color.WHITE);

            chat = new JTextArea();
            chat.setEditable(false);
            JScrollPane chatScroll = new JScrollPane(chat);
            chatScroll.setBorder(BorderFactory.createTitledBorder("Chat with " + name));

            input = new JTextField();
            send = new JButton("Send");
            JPanel inputPanel = new JPanel(new BorderLayout());
            inputPanel.add(input, BorderLayout.CENTER);
            inputPanel.add(send, BorderLayout.EAST);

            send.addActionListener(e -> sendMessage());
            input.addActionListener(e -> sendMessage());

            add(screen, BorderLayout.CENTER);
            add(chatScroll, BorderLayout.SOUTH);
            add(inputPanel, BorderLayout.NORTH);
        }

        void updateImage(BufferedImage img) {
            SwingUtilities.invokeLater(() -> {
                Image scaled = img.getScaledInstance(screen.getWidth(), screen.getHeight(), Image.SCALE_SMOOTH);
                screen.setIcon(new ImageIcon(scaled));
                screen.setText("");
            });
        }

        void appendChat(String msg) {
            SwingUtilities.invokeLater(() -> {
                chat.append(msg + "\n");
                chat.setCaretPosition(chat.getDocument().getLength());
            });
        }

        void sendMessage() {
            String msg = input.getText().trim();
            if (!msg.isEmpty()) {
                writer.println("/chat " + msg);
                appendChat("You: " + msg);
                input.setText("");
            }
        }
    }
}
