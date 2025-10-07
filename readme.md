# 🖥️ ScreenShare Server Control Center

This Java project is a **multi-client server** for screen sharing, chat, and image/file sharing. It provides a **GUI control center** for monitoring connected clients, viewing streams, managing messages, and handling client connections efficiently.

---

## Features

* **Multi-client support**: Handle multiple clients at the same time.
* **Live screen streaming**: View real-time screens from connected clients.
* **Client chat**: Broadcast and receive messages from all clients.
* **Image sharing**: Clients can send images that appear in the global chat.
* **Server messages**: Admin can broadcast messages to all clients.
* **Client tabs**: Each client has a dedicated tab showing their stream and chat.
* **Server logs**: Real-time display of connections, disconnections, and events.
* **Right-click disconnect**: Easily terminate client connections from their tab.
* **Resizable UI**: Stream pane takes 75%, right pane shows logs and global chat.

---

## Ports Used

| Service           | Port  |
| ----------------- | ----- |
| Screen streaming  | 5000  |
| Chat              | 5001  |
| File/Image upload | 5002  |
| Remote control    | 6000* |

* Remote control allows server to control mouse and keyboard on client machine if implemented.

---

## UI Layout

* **Left Pane (75%)**:

    * Tabs for each client
    * Live screen preview
    * Client-specific chat

* **Right Pane (25%)**:

    * Server logs (top)
    * Global chat (bottom)
    * Global chat input with send button

---

## Setup Instructions

1. Clone the repository:

```bash
git clone https://github.com/Rutwaza/JAVA-VIEWER01-SERVER.git
```

2. Open the project in your Java IDE (IntelliJ, Eclipse, etc.).
3. Compile and run `ServerxV2.java`.

---

## Usage

1. Start the server UI. Logs will show connection attempts.
2. Clients connect using the client app with server IP and nickname.
3. Monitor clients via tabs:

    * **Click tab**: View stream and chat for that client.
    * **Right-click tab**: Disconnect client.
4. Global chat:

    * Send broadcast messages using the input box.
    * Images sent by clients appear in the global chat.
5. Server logs automatically update for connections, disconnections, and messages.

---

## Notes

* Ensure ports (5000-5002, 6000) are open in your firewall.
* Stable network connection recommended for smooth streaming.
* The server can handle multiple clients; the limit depends on system resources.

---

**Author:** Nelson RUTWAZA
**License:** MIT
