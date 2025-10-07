import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.List;

public class Serverx extends JFrame {
    private JTabbedPane clientTabs;
    private JLabel statusLabel;
    private ExecutorService threadPool;
    private ServerSocket screenServer, chatServer, fileServer;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Serverx server = new Serverx();
            server.setVisible(true);
        });
    }

    public Serverx() {
        setTitle("ScreenShare Server");
        setSize(1200, 800);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        clientTabs = new JTabbedPane();
        add(clientTabs, BorderLayout.CENTER);

        statusLabel = new JLabel("Status: Waiting for clients...");
        statusLabel.setFont(new Font("Arial", Font.BOLD, 14));
        add(statusLabel, BorderLayout.SOUTH);

        threadPool = Executors.newCachedThreadPool();

        // Start listening
        threadPool.submit(this::startServers);
    }

    private void startServers() {
        try {
            screenServer = new ServerSocket(5000);
            chatServer = new ServerSocket(5001);
            fileServer = new ServerSocket(5002);

            updateStatus("Servers running on ports 5000 (screen), 5001 (chat), 5002 (files)");

            while (true) {
                Socket screenSocket = screenServer.accept();
                DataInputStream dis = new DataInputStream(screenSocket.getInputStream());
                String nickname = dis.readUTF();
                updateStatus("Incoming connection: " + nickname);

                // Notify client to start screen streaming
                DataOutputStream dos = new DataOutputStream(screenSocket.getOutputStream());
                dos.writeUTF("start_screenshare");

                // Accept chat socket for same client
                Socket chatSocket = chatServer.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(chatSocket.getInputStream(), "UTF-8"));
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(chatSocket.getOutputStream(), "UTF-8"), true);
                String chatNick = reader.readLine();

                // Create UI for this client
                ClientPanel panel = new ClientPanel(chatNick, writer);
                clients.put(chatNick, new ClientHandler(chatNick, screenSocket, chatSocket, panel));
                SwingUtilities.invokeLater(() -> clientTabs.addTab(chatNick, panel));

                // Start screen receiving thread
                threadPool.submit(() -> receiveScreen(chatNick));

                // Start chat receiving thread
                threadPool.submit(() -> receiveChat(chatNick));

                // Start file server thread
                threadPool.submit(this::handleFileUploads);
            }
        } catch (Exception e) {
            updateStatus("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void receiveScreen(String nickname) {
        ClientHandler handler = clients.get(nickname);
        try (DataInputStream dis = new DataInputStream(handler.screenSocket.getInputStream())) {
            while (true) {
                int len = dis.readInt();
                byte[] data = dis.readNBytes(len);
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
                if (img != null) {
                    handler.panel.updateImage(img);
                }
            }
        } catch (IOException e) {
            updateStatus("Screen stream lost for " + nickname);
            removeClient(nickname);
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
                    broadcastChat("/chat " + msg, nickname);
                }
            }
        } catch (IOException e) {
            updateStatus("Chat connection lost for " + nickname);
            removeClient(nickname);
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
                byte[] buffer = new byte[8192];
                long remaining = fileSize;
                while (remaining > 0) {
                    int read = dis.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read == -1) break;
                    fos.write(buffer, 0, read);
                    remaining -= read;
                }
            }

            broadcastChat("/image " + sender + " " + file.getName() + " " + fileName, null);
            updateStatus(sender + " uploaded " + fileName);
        } catch (IOException e) {
            updateStatus("File upload error: " + e.getMessage());
        }
    }

    private void broadcastChat(String message, String exclude) {
        clients.values().forEach(handler -> {
            if (!handler.nickname.equals(exclude)) {
                handler.panel.writer.println(message);
            }
        });
    }

    private void removeClient(String nickname) {
        ClientHandler handler = clients.remove(nickname);
        if (handler != null) {
            SwingUtilities.invokeLater(() -> {
                for (int i = 0; i < clientTabs.getTabCount(); i++) {
                    if (clientTabs.getTitleAt(i).equals(nickname)) {
                        clientTabs.removeTabAt(i);
                        break;
                    }
                }
            });
        }
    }

    private void updateStatus(String msg) {
        SwingUtilities.invokeLater(() -> statusLabel.setText("Status: " + msg));
        System.out.println(msg);
    }

    // ===== Inner Classes =====

    static class ClientHandler {
        String nickname;
        Socket screenSocket, chatSocket;
        ClientPanel panel;

        ClientHandler(String nickname, Socket screenSocket, Socket chatSocket, ClientPanel panel) {
            this.nickname = nickname;
            this.screenSocket = screenSocket;
            this.chatSocket = chatSocket;
            this.panel = panel;
        }
    }

    static class ClientPanel extends JPanel {
        private JLabel screenLabel;
        private JTextArea chatArea;
        private JTextField inputField;
        private JButton sendButton;
        PrintWriter writer;

        ClientPanel(String name, PrintWriter writer) {
            this.writer = writer;
            setLayout(new BorderLayout(10, 10));

            screenLabel = new JLabel("Waiting for stream...", JLabel.CENTER);
            screenLabel.setPreferredSize(new Dimension(800, 500));
            screenLabel.setOpaque(true);
            screenLabel.setBackground(Color.BLACK);
            screenLabel.setForeground(Color.WHITE);

            chatArea = new JTextArea();
            chatArea.setEditable(false);
            chatArea.setFont(new Font("Arial", Font.PLAIN, 14));
            JScrollPane chatScroll = new JScrollPane(chatArea);

            JPanel inputPanel = new JPanel(new BorderLayout());
            inputField = new JTextField();
            sendButton = new JButton("Send");
            inputPanel.add(inputField, BorderLayout.CENTER);
            inputPanel.add(sendButton, BorderLayout.EAST);

            sendButton.addActionListener(e -> sendMessage());
            inputField.addActionListener(e -> sendMessage());

            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(screenLabel), chatScroll);
            split.setResizeWeight(0.8);

            add(split, BorderLayout.CENTER);
            add(inputPanel, BorderLayout.SOUTH);
        }

        void updateImage(BufferedImage img) {
            SwingUtilities.invokeLater(() -> {
                Image scaled = img.getScaledInstance(screenLabel.getWidth(), screenLabel.getHeight(), Image.SCALE_SMOOTH);
                screenLabel.setIcon(new ImageIcon(scaled));
                screenLabel.setText("");
            });
        }

        void appendChat(String msg) {
            SwingUtilities.invokeLater(() -> {
                chatArea.append(msg + "\n");
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
            });
        }

        void sendMessage() {
            String msg = inputField.getText().trim();
            if (!msg.isEmpty()) {
                writer.println("/chat " + msg);
                appendChat("You: " + msg);
                inputField.setText("");
            }
        }
    }
}
