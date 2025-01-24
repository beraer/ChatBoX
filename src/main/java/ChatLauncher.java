/**
 * The ChatLauncher class starts the ChatServer.
 */
public class ChatLauncher {
    public static void main(String[] args) {

        String configPath = "/home/carga/IdeaProjects/ChatBoX/config";

        try {
            ChatServer server = new ChatServer(configPath);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down server...");
                server.shutdown();
            }));

        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}