import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;


/**
 * The ChatClient class represents the client application that connects to the ChatServer,
 * handles user interactions, and updates the GUI.
 */
public class ChatClient extends JFrame {
    private Socket socket;
    private PrintWriter output;
    private BufferedReader input;
    private String username;
    private JTextPane chatArea;
    private JTextField messageField;
    private JList<String> userList;
    private DefaultListModel<String> userListModel;
    private final Map<String, Color> userColors = new HashMap<>();
    private final Color[] colors = {
            Color.BLUE, Color.RED, Color.GREEN, Color.MAGENTA,
            Color.ORANGE, Color.PINK
    };
    private int colorIndex = 0;

    /**
     * Initializes the ChatClient and connects to the server.
     *
     * @param serverAddress The server's address.
     * @param port          The server's port.
     */
    public ChatClient(String serverAddress, int port) {
        setTitle("ChatBox");
        setupGUI();
        connectToServer(serverAddress, port);
    }


    /**
     * Sets up the graphical user interface components.
     */
    private void setupGUI() {
        setSize(800, 600);
        setLayout(new BorderLayout(5, 5));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        JPanel leftPanel = new JPanel(new BorderLayout(5, 5));
        chatArea = new JTextPane();
        chatArea.setEditable(false);
        JScrollPane chatScroll = new JScrollPane(chatArea);
        chatScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
        leftPanel.add(chatScroll, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        messageField = new JTextField();
        JButton sendButton = new JButton("Send");
        inputPanel.add(messageField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        leftPanel.add(inputPanel, BorderLayout.SOUTH);

        JPanel rightPanel = new JPanel(new BorderLayout(5, 5));
        rightPanel.setBorder(BorderFactory.createTitledBorder("Connected Users"));
        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        JScrollPane userScroll = new JScrollPane(userList);
        rightPanel.add(userScroll, BorderLayout.CENTER);

        splitPane.setLeftComponent(leftPanel);
        splitPane.setRightComponent(rightPanel);
        splitPane.setResizeWeight(0.8);

        add(splitPane, BorderLayout.CENTER);

        ((JPanel)getContentPane()).setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        sendButton.addActionListener(e -> sendMessage());
        messageField.addActionListener(e -> sendMessage());

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disconnect();
                System.exit(0);
            }
        });

        setMinimumSize(new Dimension(500, 400));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }


    /**
     * Connects to the chat server and starts the message handling thread.
     *
     * @param serverAddress The server's address.
     * @param port          The server's port.
     */
    private void connectToServer(String serverAddress, int port) {
        try {
            socket = new Socket(serverAddress, port);
            output = new PrintWriter(socket.getOutputStream(), true);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            username = JOptionPane.showInputDialog(this, "Enter your username:");
            if ((username == null) || username.trim().isEmpty()) {
                disconnect();
                System.exit(0);
            }

            output.println(username);

            new Thread(this::handleServerMessages, "ServerMessageHandler-" + username).start();

            setVisible(true);
            messageField.requestFocusInWindow();

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "Could not connect to server: " + e.getMessage(),
                    "Connection Error",
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    /**
     * Handles incoming messages from the server.
     */
    private void handleServerMessages() {
        try {
            String serverMessage;
            while ((serverMessage = input.readLine()) != null) {
                final String messageToDisplay = serverMessage;
                SwingUtilities.invokeLater(() -> processServerMessage(messageToDisplay)); //ensuring server messages are safe!
            }
        } catch (IOException e) {
            if (!socket.isClosed()) {
                appendStyledMessage("SYSTEM", "Lost connection to server\n", Color.RED);
                System.err.println("IOException in handleServerMessages: " + e.getMessage());
            }
        }
    }

    /**
     * Processes and displays messages received from the server.
     *
     * @param messageToDisplay The message received from the server.
     */
    private void processServerMessage(String messageToDisplay) {
        if (messageToDisplay.startsWith("Available commands:")) {
            appendStyledMessage("SYSTEM", messageToDisplay + "\n", Color.BLUE);
        } else if (messageToDisplay.startsWith("Connected users: ")) {
            updateUserList(messageToDisplay.substring("Connected users: ".length()));
            appendStyledMessage("SYSTEM", messageToDisplay + "\n", Color.GRAY);
        } else if (messageToDisplay.startsWith("SYSTEM:")) {
            appendStyledMessage("SYSTEM", messageToDisplay + "\n", Color.GRAY);
        } else if (messageToDisplay.startsWith("(Private from ")) {
            String[] parts = messageToDisplay.split("\\):", 2);
            if (parts.length == 2) {
                String senderInfo = parts[0].substring(1); // Remove leading '('
                String sender = senderInfo.replace("Private from ", "").trim();
                String content = parts[1].trim();
                if (!userColors.containsKey(sender)) {
                    userColors.put(sender, colors[colorIndex % colors.length]);
                    colorIndex++;
                }
                appendStyledMessage("(Private) " + sender, content + "\n", userColors.get(sender));
            } else {
                appendStyledMessage("SYSTEM", messageToDisplay + "\n", Color.GRAY);
            }
        } else if (messageToDisplay.startsWith("(Private to ")) {
            appendStyledMessage("SYSTEM", messageToDisplay + "\n", Color.GRAY);
        } else {
            String[] parts = messageToDisplay.split(":", 2);
            if (parts.length == 2) {
                String sender = parts[0].trim();
                String content = parts[1].trim();
                if (!userColors.containsKey(sender)) {
                    userColors.put(sender, colors[colorIndex % colors.length]);
                    colorIndex++;
                }
                appendStyledMessage(sender, content + "\n", userColors.get(sender));
            } else {
                appendStyledMessage("SYSTEM", messageToDisplay + "\n", Color.GRAY);
            }
        }
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }


    /**
     * Appends a styled message to the chat area.
     *
     * @param sender  The sender's name.
     * @param message The message content.
     * @param color   The color to display the message in.
     */
    private void appendStyledMessage(String sender, String message, Color color) {
        StyledDocument doc = chatArea.getStyledDocument();
        Style style = chatArea.addStyle(sender, null);
        StyleConstants.setForeground(style, color);

        try {
            if (sender.equals("SYSTEM")) {
                doc.insertString(doc.getLength(), message, style);
            } else {
                doc.insertString(doc.getLength(), sender + ": " + message, style);
            }
            chatArea.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    /**
     * Updates the user list displayed in the GUI.
     *
     * @param userListStr The string containing the list of users.
     */
    private void updateUserList(String userListStr) {
        String[] users = userListStr.replaceAll("[\\[\\]]", "").split(", ");
        userListModel.clear();
        for (String user : users) {
            if (!user.trim().isEmpty()) {
                userListModel.addElement(user);
                if (!userColors.containsKey(user)) {
                    userColors.put(user, colors[colorIndex % colors.length]);
                    colorIndex++;
                }
            }
        }

        userList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String username = (String) value;
                if (userColors.containsKey(username)) {
                    setForeground(userColors.get(username));
                }
                return this;
            }
        });
    }


    /**
     * Sends the message typed by the user to the server.
     */
    private void sendMessage() {
        String message = messageField.getText().trim();
        if (!message.isEmpty()) {
            output.println(message);
            messageField.setText("");
            messageField.requestFocusInWindow();
        }
    }


    /**
     * Disconnects from the server and closes resources.
     */
    private void disconnect() {
        try {
            if (output != null) {
                output.println("/quit");
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Error during disconnect: " + e.getMessage());
        }
    }

    /**
     * The main method to start the ChatClient application.
     *
     * @param args Command-line arguments.
     */
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> {
            String server = "localhost";
            int port = 8888;
            new ChatClient(server, port);
        });
    }
}