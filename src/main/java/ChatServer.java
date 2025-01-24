import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The ChatServer class handles incoming client connections,
 * manages connected clients, and facilitates message broadcasting.
 */
public class ChatServer {

    private int port;
    private String serverName;
    private final Set<String> bannedPhrases;
    private final Map<String, ClientHandler> connectedClients;
    private final ServerSocket serverSocket;
    private final ExecutorService clientExecutor;
    private final ExecutorService messageProcessorExecutor;
    private final Object clientLock = new Object();

    /**
     * Initializes the ChatServer with configuration from the specified file.
     *
     * @param configFile The path to the configuration file.
     */
    public ChatServer(String configFile) {
        this.bannedPhrases = new HashSet<>();
        this.connectedClients = new ConcurrentHashMap<>();

        // load config
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            this.port = Integer.parseInt(reader.readLine().trim());
            this.serverName = reader.readLine().trim();
            String phrase;
            while ((phrase = reader.readLine()) != null) {
                if (!phrase.trim().isEmpty()) {
                    bannedPhrases.add(phrase.trim().toLowerCase());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Configuration error: " + e.getMessage());
        }

        // start server
        try {
            this.serverSocket = new ServerSocket(port);
            this.clientExecutor = Executors.newVirtualThreadPerTaskExecutor();
            this.messageProcessorExecutor = Executors.newVirtualThreadPerTaskExecutor();
            System.out.println(serverName + " started on port " + port);
            new Thread(this::acceptClients).start();
        } catch (IOException e) {
            throw new RuntimeException("Server startup failed: " + e.getMessage());
        }
    }


    /**
     * Registers a new client with the given username.
     *
     * @param username The username of the client.
     * @param handler  The ClientHandler managing the client.
     * @return True if registration is successful, false if the username is taken.
     */
    public boolean registerClient(String username, ClientHandler handler) {
        synchronized (clientLock) {
            if (connectedClients.containsKey(username)) {
                return false; // Username is taken
            } else {
                connectedClients.put(username, handler);
                // Broadcast messages inside the synchronized block to ensure consistency
                broadcastUserList();
                broadcastSystemMessage(username + " joined");
                return true;
            }
        }
    }


    /**
     * Removes a client with the given username.
     *
     * @param username The username of the client to remove.
     */
    public void removeClient(String username) {
        synchronized (clientLock) {
            if (connectedClients.remove(username) != null) {
                broadcastUserList();
                broadcastSystemMessage(username + " left");
            }
        }
    }


    /**
     * Broadcasts a message from a sender to all connected clients.
     *
     * @param sender  The sender's username.
     * @param message The message to broadcast.
     */
    public void broadcast(String sender, String message) {
        if (containsBannedPhrase(message)) {
            synchronized(clientLock) {
                ClientHandler senderClient = connectedClients.get(sender);
                senderClient.sendMessage("SYSTEM: Message blocked - contains banned phrase");
            }
            return;
        }

        Map<String, ClientHandler> clientsCopy;
        synchronized(clientLock) {
            clientsCopy = new HashMap<>(connectedClients);
        }

        clientsCopy.values().forEach(client -> {
            client.sendMessage(sender + ": " + message);
        });
    }


    /**
     * Shuts down the server.
     */
    public void shutdown() {
        try {
            serverSocket.close();
            clientExecutor.shutdown();
            messageProcessorExecutor.shutdown();
            if (!clientExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                clientExecutor.shutdownNow();
            }
            if (!messageProcessorExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                messageProcessorExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            clientExecutor.shutdownNow();
            messageProcessorExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            clientExecutor.shutdownNow();
            messageProcessorExecutor.shutdownNow();
            System.err.println("Error during shutdown: " + e.getMessage());
        }
    }

    /**
     * Gets the current list of connected clients.
     *
     * @return A copy of the connected clients map.
     */
    public Map<String, ClientHandler> getConnectedClients() {
        synchronized(clientLock) {
            return new HashMap<>(connectedClients);
        }
    }

    /**
     * Gets the set of banned phrases.
     *
     * @return An unmodifiable set of banned phrases.
     */
    public Set<String> getBannedPhrases() {
        return Set.copyOf(bannedPhrases);
    }


    /**
     * Checks if a message contains any banned phrases.
     *
     * @param message The message to check.
     * @return True if the message contains a banned phrase, false otherwise.
     */
    public boolean containsBannedPhrase(String message) {
        if (message == null) { //for toLowerCase
            return false;
        }
        String lowerCaseMessage = message.toLowerCase(Locale.ROOT);
        return bannedPhrases.stream().anyMatch(lowerCaseMessage::contains);
    }


    /**
     * Accepts incoming client connections.
     */
    private void acceptClients() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                clientExecutor.execute(new ClientHandler(socket, this, messageProcessorExecutor));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    System.err.println("Connection error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Broadcasts a system message to all clients.
     *
     * @param message The message to broadcast.
     */
    private void broadcastSystemMessage(String message) {
        String systemMessage = "SYSTEM: " + message;
        connectedClients.values().forEach(client ->
                client.sendMessage(systemMessage));
    }


    /**
     * Broadcasts the list of connected users to all clients.
     */
    private void broadcastUserList() {
        synchronized (clientLock) {
            String userList = "Connected users: " + connectedClients.keySet();
            connectedClients.values().forEach(client -> client.sendMessage(userList));
        }
    }
}