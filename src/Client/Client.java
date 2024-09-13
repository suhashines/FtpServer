package Client;
import java.io.*;
import java.net.Socket;
import java.util.Scanner;

class FileUploadTask extends Thread {
    private final String fileName;
    private final String serverHost;
    private final int serverPort;
    private static final int CHUNK_SIZE = 1024;

    private static final String root = "src/Client/root/" ;

    FileUploadTask(String fileName, String serverHost, int serverPort) {
        this.fileName = root+fileName;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
    }

    public String readLineFromInputStream(Socket s){
        try {
            InputStream inputStream = s.getInputStream();
            int tmp;
            StringBuilder response = new StringBuilder();
            while((tmp = inputStream.read()) != -1){
                char c = (char) tmp;
                if(c == '\n') break;
                response.append(c);
            }
            if(response.charAt(response.length() - 1) == '\r'){
                response.deleteCharAt(response.length() - 1);
            }
            return response.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void run() {

        File file = new File(fileName);

        // valid file extension  (text, image, mp4 )

        if (!isValidFileType(file)) {

            System.err.println("Invalid file format: " + fileName.replace(root, ""));

            return;
        }


        if (!file.exists() || !file.isFile()) {
            System.err.println("File not found: " + fileName);
            return;
        }

        // Upload file in chunks
        try (Socket socket = new Socket(serverHost, serverPort);
             OutputStream out = socket.getOutputStream();
             FileInputStream fileIn = new FileInputStream(file)) {

            // Upload request header

            String requestHeader = "UPLOAD " + file.getName() + " " + file.length() + "\r\n";
            out.write(requestHeader.getBytes());
            out.flush();

            // Sending file content in chunks
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;

            while ((bytesRead = fileIn.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }

//            System.out.println("sent to the server, waiting for response");

//            out.close();

            // Receive server's response
           String response = readLineFromInputStream(socket);

            System.out.println("server response: "+response);

        } catch (IOException e) {
            System.err.println("Error uploading file: " + file.getName() + ". " + e.getMessage());
        }

    }



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
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 6789;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {

            System.out.print("Enter the file name to upload (or type 'q' to quit): ");

            String fileName = scanner.nextLine();

            if (fileName.equalsIgnoreCase("q")) {
                break;
            }

            // file upload thread
            FileUploadTask uploadTask = new FileUploadTask(fileName, SERVER_HOST, SERVER_PORT);

            uploadTask.start();
        }

        scanner.close();
    }
}

