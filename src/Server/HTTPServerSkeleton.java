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


    RequestHandler(Socket s) {
        this.s = s;
    }


    public String readLineFromInputStream(){
        try {
            InputStream inputStream = s.getInputStream();
            int tmp;
            StringBuilder response = new StringBuilder();
            while((tmp = inputStream.read()) != -1){
                char c = (char) tmp;
                if(c == '\n') break;
                response.append(c);
            }
            if( response.length()!=0 && response.charAt(response.length() - 1) == '\r'){
                response.deleteCharAt(response.length() - 1);
            }
            return response.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String createHeader(long contentLength, String status, String mimeType) {
        return "HTTP/1.1 " + status + "\r\n" +
                "Server: Java HTTP Server: 1.0\r\n" +
                "Date: " + new Date() + "\r\n" +
                "Content-Type: " + mimeType + "\r\n" +
                "Content-Length: " + contentLength + "\r\n" ;
    }

    public void sendResponse(String content, String status, String mimeType) throws IOException {

        PrintWriter pr = new PrintWriter(s.getOutputStream());

        String header = createHeader(content.length(), status, mimeType);

        pr.write(header+"\r\n");
        pr.write(content);
        pr.flush();
    }




    public void logRequestAndResponse(String request, int status, String mimeType) {

        try (FileWriter fw = new FileWriter("log.txt",true);

             BufferedWriter bw = new BufferedWriter(fw);

             PrintWriter logWriter = new PrintWriter(bw)) {

            // Log request
            logWriter.println("REQUEST: " + request);

            String[] parts = request.split(" ");

            if(parts[0].equals("GET")){
                logWriter.println("RESPONSE: HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "NOT FOUND"));
                logWriter.println("Content-Type: " + mimeType);
            }else{
                logWriter.println("RESPONSE: HTTP/1.1 " + status);
                logWriter.println("MESSAGE: " + mimeType);
            }

            // Log response

            logWriter.println("----------------------------------------------------");

        } catch (IOException e) {
            System.out.println("Error writing to log file: " + e.getMessage());
        }
    }

    public String getMimeType(File file) throws IOException {

        String mimeType = Files.probeContentType(Path.of(file.getPath()));

        if (mimeType == null) {
            mimeType = "application/octet-stream"; // for binary data
        }
        return mimeType;
    }

    public String generateDirectoryListing(File directory) throws IOException {

        StringBuilder content = new StringBuilder("<html><body>");
        String path = directory.getPath().replace("src\\Server\\", "");

        content.append("<h2>Index of ").append(path).append("</h2>");
        content.append("<ul>");

        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {

                if (file.isDirectory()) {
                    content.append("<li><b><i><a href=\"").append(file.getName())
                            .append("/\">").append(file.getName()).append("/</a></i></b></li>");
                } else {
                    String mimeType = getMimeType(file);

                    // Check if the file is text or an image file
                    if (mimeType.startsWith("text") || mimeType.startsWith("image")) {
                        // Open text or image files in a new tab
                        content.append("<li><a href=\"").append(file.getName())
                                .append("\" target=\"_blank\">").append(file.getName()).append("</a></li>");
                    } else {
                        // Other files will open in the same tab
                        content.append("<li><a href=\"").append(file.getName())
                                .append("\">").append(file.getName()).append("</a></li>");
                    }
                }
            }
        }

        content.append("</ul></body></html>");

        return content.toString();
    }



    public void serveFile(File file, String input) throws IOException {

        String mimeType = getMimeType(file);

        if (mimeType.startsWith("text")) {

            String content = new String(HTTPServerSkeleton.readFileData(file, (int) file.length()), "UTF-8");

            String htmlContent = "<html><body><pre>" + content + "</pre></body></html>";

            sendResponse(htmlContent, "200 OK", "text/html");

            logRequestAndResponse(input, 200, "text/html");

        } else if (mimeType.startsWith("image")) {

            byte[] fileData = HTTPServerSkeleton.readFileData(file, (int) file.length());

            sendBinaryResponse(fileData, "200 OK", mimeType);

            logRequestAndResponse(input, 200, mimeType);

        } else {

            sendFileAsDownload(file, input);
        }
    }



    public void sendFileAsDownload(File file, String input) throws IOException {

        byte[] buffer = new byte[1024];
        int bytesRead;

        PrintWriter pr = new PrintWriter(s.getOutputStream());

        String header = createHeader(file.length(),"200","application/octet-stream");

        pr.write(header);

        pr.write("Content-Disposition: attachment; filename=\"" + file.getName() + "\"\r\n");

        pr.write("\r\n");
        pr.flush();

        // file in chunks
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

        PrintWriter pr = new PrintWriter(out);

        pr.write(createHeader(fileData.length,status,mimeType));

        pr.write("\r\n");
        pr.flush();

        out.write(fileData);
        out.flush();
    }


    private void handleFileUpload(String request, InputStream is, OutputStream os) {
        try {
            String[] requestParts = request.split(" ");
            System.out.println(Arrays.toString(requestParts));

            if (requestParts.length < 3) {

                String errorMessage = "Invalid upload request.";

                System.err.println(errorMessage);

                os.write(("ERROR " + errorMessage + "\r\n").getBytes());

                logRequestAndResponse(request,409,errorMessage);

                return;
            }

            String fileName = requestParts[1];
            long fileSize = Long.parseLong(requestParts[2]);

            System.out.println("File size: " + fileSize);

            if (!isValidFileType(fileName)) {
                String errorMessage = "Invalid file type: " + fileName;
                System.err.println(errorMessage);
                os.write(("ERROR " + errorMessage + "\r\n").getBytes());
                logRequestAndResponse(request,409,errorMessage);
                return;
            }

            File uploadDir = new File(UPLOAD_DIR);

            if (!uploadDir.exists() && !uploadDir.mkdir()) {
                String errorMessage = "Failed to create upload directory.";
                System.err.println(errorMessage);
                os.write(("ERROR " + errorMessage + "\r\n").getBytes());
                logRequestAndResponse(request,404,errorMessage);
                return;
            }

            File outFile = new File(uploadDir, fileName);
            System.out.println("Created output file: " + outFile.getAbsolutePath());

            try (FileOutputStream fileOut = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                long totalBytesRead = 0;

                System.out.println("Starting file upload loop");

                // Read data in chunks
                while (totalBytesRead < fileSize && (bytesRead = is.read(buffer, 0,
                        (int) Math.min(buffer.length, fileSize - totalBytesRead))) != -1) {

                    fileOut.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;

                    System.out.println("Bytes Read: " + bytesRead);
                    System.out.println("Total Bytes Received: " + totalBytesRead);
                    System.out.println("File Size: " + fileSize);
                }

                if (totalBytesRead == fileSize) {
                    String successMessage = "File " + fileName + " uploaded successfully.";
                    System.out.println(successMessage);
                    os.write(("SUCCESS " + successMessage + "\r\n").getBytes());
                    logRequestAndResponse(request,200,successMessage);
                } else {
                    String errorMessage = "File upload incomplete for: " + fileName;
                    System.err.println(errorMessage);
                    os.write(("ERROR " + errorMessage + "\r\n").getBytes());
                    logRequestAndResponse(request,400,errorMessage);
                }
            } catch (IOException e) {
                String errorMessage = "Error writing file: " + e.getMessage();
                System.err.println(errorMessage);
                os.write(("ERROR " + errorMessage + "\r\n").getBytes());
                logRequestAndResponse(request,400,errorMessage);
            }

        } catch (NumberFormatException | IOException e) {
            String errorMessage = "Error handling file upload: " + e.getMessage();
            System.err.println(errorMessage);
            logRequestAndResponse(request,400,errorMessage);
            try {
                os.write(("ERROR " + errorMessage + "\r\n").getBytes());
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


        input = readLineFromInputStream();

        System.out.println("input: " + input);

        if (input != null && input.length() > 0) {

            String[] parts = input.split(" ");

            if (parts[0].equals("GET")) {

                String requestedPath = parts[1].equals("/") ? root : root + parts[1];

                File file = new File(requestedPath);

                if (file.exists()) {

                    if (file.isDirectory()) {

                        try {

                            String content = generateDirectoryListing(file);

//                            System.out.println(content);

                            sendResponse(content, "200 OK", "text/html");

                            logRequestAndResponse(input, 200, "text/html");

                        } catch (IOException e) {

                            throw new RuntimeException(e);
                        }
                    } else {
                        // for Serving the file
                        try {
                            serveFile(file, input);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                } else {
                    try {
                        sendResponse("<html><h2>404 Not Found</h2></html>", "404 Not Found", "text/html");
                        logRequestAndResponse(input, 404, "text/html");
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


            }
        }

        try {
            s.close();

            System.out.println("socket closed");

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

        return fileData;
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