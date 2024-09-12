package Client;
import java.io.*;
import java.net.Socket;
import java.util.Scanner;

class FileUploadTask extends Thread {
    private final String fileName;
    private final String serverHost;
    private final int serverPort;
    private static final int CHUNK_SIZE = 1024;  // File chunk size

    private static final String root = "src/Client/root/" ;

    FileUploadTask(String fileName, String serverHost, int serverPort) {
        this.fileName = root+fileName;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
    }

    public void run() {
        File file = new File(fileName);

        // Validate file extension (only text, image, mp4 allowed)
        if (!isValidFileType(file)) {
            System.err.println("Invalid file format: " + fileName.replace(root, ""));
            return;
        }

        // Check if file exists
        if (!file.exists() || !file.isFile()) {
            System.err.println("File not found: " + fileName);
            return;
        }

        // Upload file in chunks
        try (Socket socket = new Socket(serverHost, serverPort);
             BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
             BufferedInputStream fileIn = new BufferedInputStream(new FileInputStream(file));
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            // Send file upload header (filename and size)
            PrintWriter writer = new PrintWriter(out, true);
            writer.println("UPLOAD " + file.getName() + " " + file.length());

            // Send the file content in chunks
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;

            while ((bytesRead = fileIn.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }

            // Read the server's response
            String responseLine;
            while ((responseLine = reader.readLine()) != null) {
                System.out.println("Server response: " + responseLine);
                // If the server sends a blank line, stop reading
                if (responseLine.isEmpty()) {
                    break;
                }
            }

        } catch (IOException e) {
            System.err.println("Error uploading file: " + fileName + ". " + e.getMessage());
        }
    }


    // Method to validate allowed file types
    private boolean isValidFileType(File file) {
        String[] validExtensions = {".txt", ".jpg", ".png", ".mp4"};
        String fileName = file.getName().toLowerCase();
        for (String ext : validExtensions) {
            if (fileName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}

public class Client {
    private static final String SERVER_HOST = "localhost";  // Change this to your server's address if necessary
    private static final int SERVER_PORT = 6789;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("Enter the file name to upload (or type 'exit' to quit): ");
            String fileName = scanner.nextLine();

            if (fileName.equalsIgnoreCase("exit")) {
                break;
            }

            // Start a new thread to upload the file
            FileUploadTask uploadTask = new FileUploadTask(fileName, SERVER_HOST, SERVER_PORT);
            uploadTask.start();
        }

        scanner.close();
    }
}

