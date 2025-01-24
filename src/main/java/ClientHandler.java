import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles communication between the server and a connected client.
 */
public class ClientHandler implements Runnable, AutoCloseable {
    private final Socket clientSocket;
    private final ChatServer server;
    private String username;
    private final BufferedReader input;
    private final PrintWriter output;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>(100); //prevent unbounded growth
    private ExecutorService messageProcessorExecutor;
    private Future<?> messageProcessorFuture;

    /**
     * Initializes the ClientHandler for a connected client.
     *
     * @param socket                   The client's socket.
     * @param server                   The ChatServer instance.
     * @param messageProcessorExecutor The ExecutorService for message processing.
     */
    public ClientHandler(Socket socket, ChatServer server, ExecutorService messageProcessorExecutor) {
        this.clientSocket = socket;
        this.server = server;
        this.messageProcessorExecutor = messageProcessorExecutor;
        try {
            input = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            output = new PrintWriter(clientSocket.getOutputStream(), true);
        } catch (IOException e) {
            throw new RuntimeException("Error creating client handler: " + e.getMessage());
        }
    }

    /**
     * The main execution method for the client handler.
     */
    @Override
    public void run() {
        try {
            //initial connection and username assignment
            String proposedUsername = input.readLine();

            while (!server.registerClient(proposedUsername, this)) {
                output.println("Username taken. Please enter another username:");
                proposedUsername = input.readLine();
            }

            this.username = proposedUsername;
            output.println("Welcome " + username + "!");
            output.println("Connected users: " + server.getConnectedClients().keySet());

            String instructions =
                    "Available commands:\n" +
                            "@user message - Send private message to one user\n" +
                            "@user1,user2,user3 message - Send private message to multiple users\n" +
                            "!user message - Send to all except user\n" +
                            "/users - List connected users\n" +
                            "/banned - List banned phrases\n" +
                            "/help - Show this message\n" +
                            "/threads - List the active threads\n" +
                            "/quit - Disconnect";
            output.println(instructions);

            Thread.currentThread().setName("ClientHandler-" + username);

            messageProcessorFuture = messageProcessorExecutor.submit(this::processOutgoingMessages);

            try {
                String message;
                while (running.get() && (message = input.readLine()) != null) {
                    handleMessage(message);
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("IOException for client " + username + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            close();
        }
    }

    /**
     * Processes outgoing messages from the message queue and sends them to the client.
     */
    private void processOutgoingMessages() {
        try {
            while (running.get() || !messageQueue.isEmpty()) {
                String message = messageQueue.poll(1, TimeUnit.SECONDS);
                if (message != null) {
                    synchronized (output) {
                        try {
                            output.println(message);
                            output.flush();
                        } catch (Exception e) {
                            System.err.println("Error sending message to client " + username + ": " + e.getMessage());
                            close();
                            break;
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    /**
     * Adds a message to the message queue to be sent to the client.
     *
     * @param message The message to send.
     */
    public void sendMessage(String message) {
        try {
            if (!messageQueue.offer(message, 5, TimeUnit.SECONDS)) {
                System.err.println("Message queue full for client " + username + ". Disconnecting.");
                running.set(false);
                server.removeClient(username);
                try {
                    input.close();
                    output.close();
                    clientSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing resources for client " + username + ": " + e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    /**
     * Handles incoming messages from the client.
     *
     * @param message The message received from the client.
     */
    private void handleMessage(String message) {
        if (server.containsBannedPhrase(message)) {
            sendMessage("Message not sent - contains banned phrase");
            return;
        }

        if (message.startsWith("/")) {
            switch (message.toLowerCase()) {
                case "/users" -> sendMessage("Connected users: " + server.getConnectedClients().keySet());
                case "/banned" -> sendMessage("Banned phrases: " + server.getBannedPhrases());
                case "/help" -> {
                    String instructions =
                            "Available commands:\n" +
                                    "@user message - Send private message to one user\n" +
                                    "@user1,user2,user3 message - Send private message to multiple users\n" +
                                    "!user message - Send to all except user\n" +
                                    "/users - List connected users\n" +
                                    "/banned - List banned phrases\n" +
                                    "/help - Show this message\n" +
                                    "/threads - List the active threads\n" +
                                    "/quit - Disconnect";
                    sendMessage(instructions);
                }
                case "/threads" -> {
                    Map<Thread, StackTraceElement[]> threadMap = Thread.getAllStackTraces();
                    sendMessage("Current Active Threads:");
                    for (Thread thread : threadMap.keySet()) {
                        sendMessage("Thread Name: " + thread.getName() + ", State: " + thread.getState());
                    }
                }
                case "/quit" -> {
                    sendMessage("disconnected");
                    close();
                }
                default -> sendMessage("Unknown command. Type /help for instructions.");
            }
        } else if (message.startsWith("@")) {
            handlePrivateMessage(message);
        } else if (message.startsWith("!")) {
            handleExclusionMessage(message);
        } else {
            server.broadcast(username, message);
        }
    }

    /**
     * Handles private messages directed to specific users.
     *
     * @param message The message containing recipients and content.
     */
    private void handlePrivateMessage(String message) {
        int spaceIndex = message.indexOf(" ");
        if (spaceIndex != -1) {
            String recipients = message.substring(1, spaceIndex);
            String content = message.substring(spaceIndex + 1);
            String[] targetUsers = recipients.split(",");
            Map<String, ClientHandler> clients = server.getConnectedClients();

            for (String recipient : targetUsers) {
                recipient = recipient.trim();
                ClientHandler targetClient = clients.get(recipient);

                if (targetClient != null) {
                    targetClient.sendMessage("(Private from " + username + "): " + content);
                } else {
                    sendMessage("User '" + recipient + "' not found.");
                }
            }
            sendMessage("(Private to " + recipients + "): " + content);
        } else {
            sendMessage("Invalid private message format. Usage: @user message");
        }
    }

    /**
     * Handles messages sent to all users except a specified user.
     *
     * @param message The message containing the excluded user and content.
     */
    private void handleExclusionMessage(String message) {
        int spaceIndex = message.indexOf(" ");
        if (spaceIndex != -1) {
            String excludedUser = message.substring(1, spaceIndex);
            String content = message.substring(spaceIndex + 1);

            server.getConnectedClients().forEach((user, handler) -> {
                if (!user.equals(excludedUser) && !user.equals(username)) {
                    handler.sendMessage(username + " (excluding " + excludedUser + "): " + content);
                }
            });
        }
    }

    @Override
    public void close() {
        if (!running.get()) {
            return;
        }

        running.set(false);

        if (messageProcessorFuture != null && !messageProcessorFuture.isDone()) {
            try {
                messageProcessorFuture.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                System.err.println("Error waiting for message processor to finish for client " + username + ": " + e.getMessage());
            }
        }

        server.removeClient(username);

        try {
            input.close();
            output.close();
            clientSocket.close();
        } catch (IOException e) {
            System.err.println("Error closing resources for client " + username + ": " + e.getMessage());
        }
    }
}