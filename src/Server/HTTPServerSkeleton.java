package Server;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Date;
import java.nio.file.*;


class RequestHandler extends Thread {

    Socket s;

    private static final String root = "src/Server/root" ;

    private static final String UPLOAD_DIR = "src/Server/uploaded/" ;

    private static final int CHUNK_SIZE = 1024 ;


    RequestHandler(Socket s) {
        this.s = s;
    }

    public String readRequest() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
        return in.readLine();
    }

    public void sendResponse(String content, String status, String mimeType) throws IOException {
        PrintWriter pr = new PrintWriter(s.getOutputStream());

        pr.write("HTTP/1.1 " + status +"\r\n");
        pr.write("Server: Java HTTP Server: 1.0\r\n");
        pr.write("Date: " + new Date() + "\r\n");
        pr.write("Content-Type: " + mimeType + "\r\n");
        pr.write("Content-Length: " + content.length() + "\r\n");
        pr.write("\r\n");
        pr.write(content);
        pr.flush();
    }

    public String getMimeType(File file) throws IOException {
        String mimeType = Files.probeContentType(Path.of(file.getPath()));
        if (mimeType == null) {
            // Default to binary if MIME type cannot be determined
            mimeType = "application/octet-stream";
        }
        return mimeType;
    }

    public void logRequestAndResponse(String request, int status, String mimeType) {
        try (FileWriter fw = new FileWriter("server.log", true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter logWriter = new PrintWriter(bw)) {

            // Log request
            logWriter.println("REQUEST: " + request);

            // Log response
            logWriter.println("RESPONSE: HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "NOT FOUND"));
            logWriter.println("Content-Type: " + mimeType);
            logWriter.println("----------------------------------------------------");

        } catch (IOException e) {
            System.out.println("Error writing to log file: " + e.getMessage());
        }
    }

    public String generateDirectoryListing(File directory) {
        StringBuilder content = new StringBuilder("<html><body>");
        String path = directory.getPath().replace("src\\Server\\","");

        content.append("<h2>Index of ").append(path).append("</h2>");
        content.append("<ul>");

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {

                System.out.println("name of the file "+file.getName());
                if (file.isDirectory()) {
                    content.append("<li><b><i><a href=\"").append(file.getName())
                            .append("/\">").append(file.getName()).append("/</a></i></b></li>");
                } else {
                    content.append("<li><a href=\"").append(file.getName())
                            .append("\">").append(file.getName()).append("</a></li>");

                }
            }
        }

        content.append("</ul></body></html>");
        return content.toString();
    }


    public void serveFile(File file, String input) throws IOException {
        String mimeType = getMimeType(file);

        if (mimeType.startsWith("text")) {
            // Serve text files in a new HTML page
            String content = new String(HTTPServerSkeleton.readFileData(file, (int) file.length()), "UTF-8");
            String htmlContent = "<html><body><pre>" + content + "</pre></body></html>";
            sendResponse(htmlContent, "200 OK", "text/html");
            logRequestAndResponse(input, 200, "text/html");
        } else if (mimeType.startsWith("image")) {
            // Serve image files directly
            byte[] fileData = HTTPServerSkeleton.readFileData(file, (int) file.length());
            sendBinaryResponse(fileData, "200 OK", mimeType);
            logRequestAndResponse(input, 200, mimeType);
        } else {
            // Force download for other formats
            sendFileAsDownload(file, input);
        }
    }





    public void sendFileAsDownload(File file, String input) throws IOException {
        byte[] buffer = new byte[1024]; // Set your chunk size (e.g., 1KB)
        int bytesRead;

        // Send headers
        PrintWriter pr = new PrintWriter(s.getOutputStream());
        pr.write("HTTP/1.1 200 OK\r\n");
        pr.write("Server: Java HTTP Server: 1.0\r\n");
        pr.write("Date: " + new Date() + "\r\n");
        pr.write("Content-Type: application/octet-stream\r\n");
        pr.write("Content-Length: " + file.length() + "\r\n");
        pr.write("Content-Disposition: attachment; filename=\"" + file.getName() + "\"\r\n");
        pr.write("\r\n");
        pr.flush();

        // Send file in chunks
        BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
        OutputStream os = s.getOutputStream();
        while ((bytesRead = bis.read(buffer)) != -1) {
            os.write(buffer, 0, bytesRead);
            os.flush();
        }

        bis.close();
        os.close();

        logRequestAndResponse(input, 200, "application/octet-stream"); // Log download request/response
    }


    public void sendBinaryResponse(byte[] fileData, String status, String mimeType) throws IOException {
        OutputStream out = s.getOutputStream();

        // Write HTTP headers
        PrintWriter pr = new PrintWriter(out);
        pr.write("HTTP/1.1 " + status + "\r\n");
        pr.write("Server: Java HTTP Server: 1.0\r\n");
        pr.write("Date: " + new Date() + "\r\n");
        pr.write("Content-Type: " + mimeType + "\r\n");
        pr.write("Content-Length: " + fileData.length + "\r\n");
        pr.write("\r\n");
        pr.flush();

        // Write binary data (image content)
        out.write(fileData);
        out.flush();
    }


    private void handleFileUpload(String request, InputStream is, OutputStream os) {
        try {
            // Parse the file name and file size from the client request
            String[] requestParts = request.split(" ");
            System.out.println(Arrays.toString(requestParts));

            if (requestParts.length < 3) {
                String errorMessage = "Invalid upload request.";
                System.err.println(errorMessage);
                os.write(("ERROR " + errorMessage + "\n").getBytes());
                return;
            }

            String fileName = requestParts[1];
            long fileSize = Long.parseLong(requestParts[2]);

            // Validate file type
            if (!isValidFileType(fileName)) {
                String errorMessage = "Invalid file type: " + fileName;
                System.err.println(errorMessage);
                os.write(("ERROR " + errorMessage + "\n").getBytes());
                return;
            }

            // Ensure upload directory exists
            File uploadDir = new File(UPLOAD_DIR);
            if (!uploadDir.exists()) {
                if (!uploadDir.mkdir()) { // Check if directory creation is successful
                    String errorMessage = "Failed to create upload directory.";
                    System.err.println(errorMessage);
                    os.write(("ERROR " + errorMessage + "\n").getBytes());
                    return;
                }
            }

            // Construct full file path
            File outFile = new File(uploadDir, fileName);  // Combines directory and file name safely

            try (BufferedOutputStream fileOut = new BufferedOutputStream(new FileOutputStream(outFile))) {
                // Read file chunks from the client and write them to the file
                byte[] buffer = new byte[65536];  // Use byte[] for reading/writing binary data
                int bytesRead;
                long totalBytesReceived = 0;

                while (totalBytesReceived < fileSize && (bytesRead = is.read(buffer)) != -1) {
                    // Write the exact number of bytes read to the file
                    fileOut.write(buffer, 0, bytesRead);
                    totalBytesReceived += bytesRead;
                }

                if (totalBytesReceived == fileSize) {
                    String successMessage = "File " + fileName + " uploaded successfully.";
                    System.out.println(successMessage);
                    os.write(("SUCCESS " + successMessage + "\n").getBytes());
                } else {
                    String errorMessage = "File upload incomplete for: " + fileName;
                    System.err.println(errorMessage);
                    os.write(("ERROR " + errorMessage + "\n").getBytes());
                }
            } catch (IOException e) {
                String errorMessage = "Error writing file: " + e.getMessage();
                System.err.println(errorMessage);
                try {
                    os.write(("ERROR " + errorMessage + "\n").getBytes());
                } catch (IOException ex) {
                    System.err.println("Error sending response: " + ex.getMessage());
                }
            }
        } catch (NumberFormatException | IOException e) {
            String errorMessage = "Error handling file upload: " + e.getMessage();
            System.err.println(errorMessage);
            try {
                os.write(("ERROR " + errorMessage + "\n").getBytes());
            } catch (IOException ex) {
                System.err.println("Error sending response: " + ex.getMessage());
            }
        }
    }




    private boolean isValidFileType(String fileName) {
        String[] validExtensions = {".txt", ".jpg", ".png", ".mp4"};
        for (String ext : validExtensions) {
            if (fileName.toLowerCase().endsWith(ext)) {
                return true;
            }
        }
        return false;
    }


    public void run() {
        String input;
        try {
            input = readRequest();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        System.out.println("input: " + input);

        if (input != null && input.length() > 0) {
            String[] parts = input.split(" ");
            if (parts[0].equals("GET")) {
                // Extract the requested path
                String requestedPath = parts[1].equals("/") ? root : root + parts[1];

                File file = new File(requestedPath);

                if (file.exists()) {
                    if (file.isDirectory()) {
                        try {
                            String content = generateDirectoryListing(file);
                            sendResponse(content, "200 OK", "text/html"); // Send directory listing as HTML
                            logRequestAndResponse(input, 200, "text/html"); // Log the directory request
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        // Serve the file
                        try {
                            serveFile(file, input); // A method to serve files based on their type
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                } else {
                    try {
                        sendResponse("<html><h2>404 Not Found</h2></html>", "404 Not Found", "text/html"); // Send a 404 response
                        logRequestAndResponse(input, 404, "text/html"); // Log the 404 response
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            } else if (parts[0].equals("UPLOAD")) {

                System.out.println("received upload request");

                try {
                    handleFileUpload(input,s.getInputStream(),s.getOutputStream());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }


            } else {
                try {
                    sendResponse("<html><h2>501 Not Implemented</h2></html>", "501 Not Implemented", "text/html"); // Send a 501 response
                    logRequestAndResponse(input, 501, "text/html"); // Log the 501 response
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        try {
            s.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}

    public class HTTPServerSkeleton {
    static final int PORT = 6789;

        public static byte[] readFileData(File file, int fileLength) throws IOException {
            FileInputStream fileIn = null;
            byte[] fileData = new byte[fileLength];

            try {
                fileIn = new FileInputStream(file);
                fileIn.read(fileData);
            } finally {
                if (fileIn != null)
                    fileIn.close();
            }

            return fileData;  // Return the byte data for the file
        }


        public static void main(String[] args) throws IOException {

        ServerSocket serverConnect = new ServerSocket(PORT);
        System.out.println("Server started.\nListening for connections on port : " + PORT + " ...\n");

        while(true) {

            Socket s = serverConnect.accept();

            new RequestHandler(s).start();
        }

    }

}
