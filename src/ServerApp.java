// ServerApp.java
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/**
 * Multi-feature server that pairs with Clientx (client) implementation.
 *
 * Ports used:
 *  - 5000 : screen stream (DataInputStream: client writes UTF(name), server replies "start_screenshare", then client sends frames)
 *  - 5001 : chat (simple text lines, first line is client's nickname)
 *  - 5002 : file uploads (DataInputStream: sender, originalFilename, length; then raw bytes)
 *  - 5003 : HTTP file server (serves shared_images/ and screens/)
 *  - 6000 : remote control clients listen on this port (server will connect to clientHost:6000 to send control commands)
 */
public class ServerApp {
    // Configuration
    private static final int SCREEN_PORT = 5000;
    private static final int CHAT_PORT = 5001;
    private static final int FILE_PORT = 5002;
    private static final int HTTP_PORT = 5003;
    private static final String SHARED_IMAGES_DIR = "shared_images";
    private static final String SCREENS_DIR = "screens";

    // State
    private final ConcurrentMap<String, ClientChatHandler> chatClients = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScreenStats> screenClients = new ConcurrentHashMap<>();
    private final ExecutorService clientPool = Executors.newCachedThreadPool();

    // For remote-control outgoing connections (one per active admin connection)
    private final Map<String, Socket> remoteControlSockets = new ConcurrentHashMap<>();
    private final AtomicInteger imageCounter = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        ServerApp server = new ServerApp();
        server.start();
    }

    private void start() throws Exception {
        // Ensure directories exist
        Files.createDirectories(Paths.get(SHARED_IMAGES_DIR));
        Files.createDirectories(Paths.get(SCREENS_DIR));

        // Start HTTP file server
        startHttpServer();

        // Start chat, screen, and file servers
        startChatServer();
        startScreenServer();
        startFileServer();

        // Start console command handler for remote control and admin tasks
        startConsoleAdmin();
    }

    // ------------------------
    // HTTP file server (serves from shared_images and screens)
    // ------------------------
    private void startHttpServer() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        httpServer.createContext("/", new FileHandler());
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.start();
        System.out.println("[HTTP] File server started on port " + HTTP_PORT);
    }

    private class FileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/")) path = path.substring(1);
            if (path.isEmpty()) {
                String resp = "Simple file server. Available directories: /" + SHARED_IMAGES_DIR + "/<file>, /" + SCREENS_DIR + "/<file>";
                exchange.sendResponseHeaders(200, resp.getBytes().length);
                exchange.getResponseBody().write(resp.getBytes());
                exchange.close();
                return;
            }

            // Prevent path traversal
            if (path.contains("..")) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }

            // Try shared_images then screens
            Path file = Paths.get(SHARED_IMAGES_DIR).resolve(path);
            if (!Files.exists(file)) {
                file = Paths.get(SCREENS_DIR).resolve(path);
            }

            if (!Files.exists(file) || Files.isDirectory(file)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            Headers h = exchange.getResponseHeaders();
            String contentType = Files.probeContentType(file);
            if (contentType == null) contentType = "application/octet-stream";
            h.add("Content-Type", contentType);
            long length = Files.size(file);
            exchange.sendResponseHeaders(200, length);
            try (OutputStream os = exchange.getResponseBody(); InputStream is = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int r;
                while ((r = is.read(buffer)) != -1) os.write(buffer, 0, r);
            } finally {
                exchange.close();
            }
        }
    }

    // ------------------------
    // Chat server
    // ------------------------
    private void startChatServer() {
        clientPool.execute(() -> {
            try (ServerSocket serverSocket = new ServerSocket(CHAT_PORT)) {
                System.out.println("[CHAT] Listening on port " + CHAT_PORT);
                while (true) {
                    Socket client = serverSocket.accept();
                    clientPool.execute(() -> handleChatClient(client));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void handleChatClient(Socket socket) {
        String nick = null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true)) {

            // First message expected to be the nickname line (client does chatWriter.println(nickname);)
            nick = in.readLine();
            if (nick == null || nick.trim().isEmpty()) nick = "Unknown" + new Random().nextInt(1000);

            ClientChatHandler handler = new ClientChatHandler(nick, socket, in, out);
            chatClients.put(nick, handler);
            broadcast("/chat " + nick + " joined the chat");

            System.out.println("[CHAT] " + nick + " connected (" + socket.getRemoteSocketAddress() + "). Total chat clients: " + chatClients.size());

            // Listen for lines and broadcast them
            String line;
            while ((line = in.readLine()) != null) {
                // we assume the client sends "/chat message" or other commands
                if (line.trim().isEmpty()) continue;
                broadcast(line);
            }
        } catch (IOException e) {
            System.out.println("[CHAT] Connection lost for " + nick + ": " + e.getMessage());
        } finally {
            if (nick != null) {
                chatClients.remove(nick);
                broadcast("/chat " + nick + " left the chat");
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void broadcast(String message) {
        // send to all chat clients
        for (ClientChatHandler c : chatClients.values()) {
            try {
                c.out.println(message);
            } catch (Exception e) {
                // ignore one-client failure; cleanup happens elsewhere
            }
        }
    }

    private static class ClientChatHandler {
        final String nickname;
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;

        ClientChatHandler(String nickname, Socket socket, BufferedReader in, PrintWriter out) {
            this.nickname = nickname;
            this.socket = socket;
            this.in = in;
            this.out = out;
        }
    }

    // ------------------------
    // File server (for images clients send with sendFile())
    // ------------------------
    private void startFileServer() {
        clientPool.execute(() -> {
            try (ServerSocket serverSocket = new ServerSocket(FILE_PORT)) {
                System.out.println("[FILE] Listening on port " + FILE_PORT);
                while (true) {
                    Socket s = serverSocket.accept();
                    clientPool.execute(() -> handleFileUpload(s));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void handleFileUpload(Socket socket) {
        try (DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            String sender = dis.readUTF();
            String originalFilename = dis.readUTF();
            long fileLen = dis.readLong();

            // Ensure unique server filename
            String ts = new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date());
            String serverFilename = ts + "_" + imageCounter.incrementAndGet() + "_" + originalFilename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
            Path target = Paths.get(SHARED_IMAGES_DIR).resolve(serverFilename);

            try (OutputStream fos = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192];
                long remaining = fileLen;
                while (remaining > 0) {
                    int read = dis.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                    if (read == -1) throw new EOFException("Unexpected EOF while reading file");
                    fos.write(buffer, 0, read);
                    remaining -= read;
                }
            }

            System.out.println("[FILE] Received image from " + sender + " -> " + target.toString());

            // Notify chat clients so they can show the image
            // Format: /image [sender] [serverFilename] [originalFilename]
            broadcast("/image " + sender + " " + serverFilename + " " + originalFilename);
        } catch (IOException e) {
            System.err.println("[FILE] Error receiving file: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ------------------------
    // Screen server (receives continuous JPEG frames from each client)
    // ------------------------
    private void startScreenServer() {
        clientPool.execute(() -> {
            try (ServerSocket serverSocket = new ServerSocket(SCREEN_PORT)) {
                System.out.println("[SCREEN] Listening on port " + SCREEN_PORT);
                while (true) {
                    Socket s = serverSocket.accept();
                    clientPool.execute(() -> handleScreenClient(s));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void handleScreenClient(Socket socket) {
        DataInputStream dis = null;
        DataOutputStream dos = null;
        String nickname = null;
        try {
            dis = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            // Client writes UTF(nickname) first
            nickname = dis.readUTF();
            if (nickname == null || nickname.trim().isEmpty()) nickname = "Client" + new Random().nextInt(1000);

            // Register client stats
            ScreenStats stats = new ScreenStats(nickname, socket.getRemoteSocketAddress().toString());
            screenClients.put(nickname, stats);

            // Tell client to start
            dos.writeUTF("start_screenshare");
            dos.flush();

            System.out.println("[SCREEN] " + nickname + " connected for screen streaming.");

            // Loop reading frames: int length, then length bytes
            while (!socket.isClosed()) {
                int length;
                try {
                    length = dis.readInt();
                } catch (EOFException eof) {
                    break;
                }
                if (length <= 0 || length > 50_000_000) {
                    System.err.println("[SCREEN] Unexpected frame length " + length + " from " + nickname);
                    break;
                }

                byte[] imageBytes = new byte[length];
                dis.readFully(imageBytes);

                // Save latest frame to file (overwrite)
                Path target = Paths.get(SCREENS_DIR).resolve(nickname + ".jpg");
                try (OutputStream fos = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    fos.write(imageBytes);
                }

                // Update stats
                stats.framesReceived.incrementAndGet();
                stats.lastFrameTime = System.currentTimeMillis();
            }

            System.out.println("[SCREEN] " + nickname + " disconnected (stream ended).");
        } catch (IOException e) {
            System.out.println("[SCREEN] Connection error with " + nickname + ": " + e.getMessage());
        } finally {
            if (nickname != null) screenClients.remove(nickname);
            try { if (dis != null) dis.close(); } catch (IOException ignored) {}
            try { if (dos != null) dos.close(); } catch (IOException ignored) {}
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static class ScreenStats {
        final String nickname;
        final String remoteAddr;
        final AtomicInteger framesReceived = new AtomicInteger(0);
        volatile long connectedAt = System.currentTimeMillis();
        volatile long lastFrameTime = 0;

        ScreenStats(String nickname, String remoteAddr) {
            this.nickname = nickname;
            this.remoteAddr = remoteAddr;
        }
    }

    // ------------------------
    // Console admin: simple commands to list clients and connect/send remote control commands
    // ------------------------
    private void startConsoleAdmin() {
        clientPool.execute(() -> {
            try (Scanner scanner = new Scanner(System.in)) {
                printHelp();
                while (true) {
                    System.out.print("server> ");
                    String line = scanner.nextLine();
                    if (line == null) break;
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    String[] parts = line.split("\\s+");
                    String cmd = parts[0].toLowerCase();

                    switch (cmd) {
                        case "help":
                            printHelp();
                            break;
                        case "list":
                            listClients();
                            break;
                        case "screens":
                            listScreens();
                            break;
                        case "connect":
                            // connect <clientHost> <alias>
                            if (parts.length < 3) {
                                System.out.println("Usage: connect <host> <alias>");
                            } else {
                                connectToClient(parts[1], parts[2]);
                            }
                            break;
                        case "move":
                            // move <alias> <x> <y>
                            if (parts.length < 4) {
                                System.out.println("Usage: move <alias> <x> <y>");
                            } else {
                                String alias = parts[1];
                                int x = Integer.parseInt(parts[2]);
                                int y = Integer.parseInt(parts[3]);
                                sendMouseMove(alias, x, y);
                            }
                            break;
                        case "click":
                            // click <alias> <button>
                            if (parts.length < 3) {
                                System.out.println("Usage: click <alias> <button>");
                            } else {
                                String alias = parts[1];
                                int button = Integer.parseInt(parts[2]);
                                sendMouseClick(alias, button);
                            }
                            break;
                        case "key":
                            // key <alias> <keycode>
                            if (parts.length < 3) {
                                System.out.println("Usage: key <alias> <keycode>");
                            } else {
                                String alias = parts[1];
                                int code = Integer.parseInt(parts[2]);
                                sendKeyPress(alias, code);
                            }
                            break;
                        case "quit":
                            System.out.println("Shutting down server...");
                            shutdown();
                            return;
                        default:
                            System.out.println("Unknown command. Type help.");
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void printHelp() {
        System.out.println("Server admin commands:");
        System.out.println("  help                     - show this help");
        System.out.println("  list                     - list chat clients");
        System.out.println("  screens                  - list screen clients");
        System.out.println("  connect <host> <alias>   - connect to client's remote-control port (6000) and store as alias");
        System.out.println("  move <alias> <x> <y>     - send MOUSE_MOVE to alias");
        System.out.println("  click <alias> <button>   - send MOUSE_CLICK to alias (button 1/2/3)");
        System.out.println("  key <alias> <keycode>    - send KEY_PRESS to alias (use Java KeyEvent VK_ codes)");
        System.out.println("  quit                     - stop server");
    }

    private void listClients() {
        System.out.println("Chat clients (" + chatClients.size() + "):");
        for (String nick : chatClients.keySet()) {
            System.out.println("  - " + nick);
        }
    }

    private void listScreens() {
        System.out.println("Screen clients (" + screenClients.size() + "):");
        for (Map.Entry<String, ScreenStats> e : screenClients.entrySet()) {
            ScreenStats s = e.getValue();
            System.out.println("  - " + s.nickname + " | frames=" + s.framesReceived.get() + " | lastFrame=" +
                    (s.lastFrameTime==0 ? "N/A" : new Date(s.lastFrameTime).toString()));
        }
    }

    private void connectToClient(String host, String alias) {
        try {
            Socket sock = new Socket();
            sock.connect(new InetSocketAddress(host, 6000), 5000); // 5s timeout
            remoteControlSockets.put(alias, sock);
            System.out.println("Connected to " + host + " as alias '" + alias + "'. Use move/click/key commands.");
        } catch (IOException e) {
            System.err.println("Failed to connect to " + host + ": " + e.getMessage());
        }
    }

    private DataOutputStream getRemoteOut(String alias) throws IOException {
        Socket s = remoteControlSockets.get(alias);
        if (s == null || s.isClosed()) throw new IOException("Not connected: " + alias);
        return new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
    }

    private void sendMouseMove(String alias, int x, int y) {
        try {
            DataOutputStream out = getRemoteOut(alias);
            out.writeUTF("MOUSE_MOVE");
            out.writeInt(x);
            out.writeInt(y);
            out.flush();
            System.out.println("Sent MOUSE_MOVE to " + alias + " -> " + x + "," + y);
        } catch (IOException e) {
            System.err.println("Failed to send MOUSE_MOVE: " + e.getMessage());
        }
    }

    private void sendMouseClick(String alias, int button) {
        try {
            DataOutputStream out = getRemoteOut(alias);
            out.writeUTF("MOUSE_CLICK");
            out.writeInt(button);
            out.flush();
            System.out.println("Sent MOUSE_CLICK to " + alias + " -> button " + button);
        } catch (IOException e) {
            System.err.println("Failed to send MOUSE_CLICK: " + e.getMessage());
        }
    }

    private void sendKeyPress(String alias, int keyCode) {
        try {
            DataOutputStream out = getRemoteOut(alias);
            out.writeUTF("KEY_PRESS");
            out.writeInt(keyCode);
            out.flush();
            System.out.println("Sent KEY_PRESS to " + alias + " -> keycode " + keyCode);
        } catch (IOException e) {
            System.err.println("Failed to send KEY_PRESS: " + e.getMessage());
        }
    }

    private void shutdown() {
        try {
            clientPool.shutdownNow();
            for (Socket s : remoteControlSockets.values()) {
                try { s.close(); } catch (IOException ignored) {}
            }
            System.out.println("Server shut down.");
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.exit(0);
    }
}
