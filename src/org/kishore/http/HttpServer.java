package org.kishore.http;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by kishorekolluru on 9/16/17.
 */
public class HttpServer {

    public static final String http_msg_regex = "(GET|PUT)(\\s)([\\\\a-zA-Z0-9\\._/-]+)(\\s)(HTTP/1\\.1)";
    public static final String contetn_length_reg = "(Content-Length:)(\\s?)([0-9]+)";
    static final Pattern req_pattern = Pattern.compile(http_msg_regex);
    public static final String server_op_dir = "server_op";

    static ServerSocket socket = null;
    public static void main(String[] args) {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if(socket!=null){
                    try {
                        socket.close();
                        System.out.println("...Server Destroyed Gracefully!");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }));

            socket = new ServerSocket(Integer.parseInt(args[0]));

            if (socket != null) {
                System.out.println("HTTP Server started listening on port "+ args[0] +"...");
                //infinite loop to receive new client connections
                while (true) {
                    Socket client = socket.accept();
                    //spawn a new thread to process the request
                    Executors.newFixedThreadPool(10).submit(new ClientConnectionHandler(client));
//                    new Thread(new ClientConnectionHandler(client)).start();
                }

            }
        } catch (IOException e) {
            e.printStackTrace();
        }catch(Exception e){
            System.err.println(e.getMessage());
        }
    }

    static class ClientConnectionHandler implements Runnable {

        //vars to keep track of PUT request
        boolean isPut = false;
        boolean isHeaderEndRchd = false;
        int contLength = -1;
        StringBuffer uploadedFile = null;
        Socket client;
        PrintStream ostream = null;
        String clientName = "Client";
        private boolean isGet;
        private boolean isRequestDone;

        public ClientConnectionHandler(Socket client) {
            this.client = client;
        }

        @Override
        /*
        * Actual method that gets executed when a request is received
        * */
        public void run() {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                ostream = new PrintStream(client.getOutputStream());
                String line = null;
//                try {
//                    Thread.sleep(8000);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//                System.out.println("Thread sleep over");
                while ((line = reader.readLine()) != null) {
                    System.out.println(clientName+": " + line);
                    processLine(line);
                    if(isRequestDone){
                        break;
                    }
                }
                ostream.close();
                reader.close();
                client.close();
                client = null;
                isRequestDone = true;
                System.out.println(clientName +" connection terminated");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /*
        * Method which processes HTTP req lines one by one.
        * Different actions taken based on GET or PUT methods in the headers
        * */
        private void processLine(String line) {
            Matcher matcher = req_pattern.matcher(line);
            if (line.startsWith("GET") && matcher.matches()){
                processGet(matcher.group(3));
                isRequestDone = true;
            }
            else if (line.startsWith("PUT") && matcher.matches()) {
                isPut = true;
            } else if (isPut && line.matches(contetn_length_reg)) {
                Matcher matcher1 = Pattern.compile(contetn_length_reg).matcher(line);
                if (matcher1.matches())
                    contLength = Integer.parseInt(matcher1.group(3));
            } else if (isPut && line.equals("")) {
                isHeaderEndRchd = true;
            } else if (isPut && isHeaderEndRchd) {
                if (contLength >= 0) {
                    uploadedFile = new StringBuffer();
                    uploadedFile.append(line);
                    if (uploadedFile.length() == contLength) {
                        writeFile(uploadedFile);
                    }
                } else {
                    printToClient("HTTP/1.1 500 Error:Content-Length not valid/unspecified.");
                }
                isRequestDone = true;
                resetPutVars();
            }
        }

        /*
        * Method that gets called from request processing code in various places
        * to write the responses to Client via the output stream extracted from the Socket connection bound
        * to a specific client
        * */
        private void printToClient(String s) {
            s = s+"\r\n";
            System.out.println("Sent to "+clientName+":"+s);
            ostream.println(s);
        }

        private void resetPutVars() {
            contLength = -1;
            isPut = false;
            uploadedFile = null;
            isHeaderEndRchd = false;
        }


        //Method that writess the Data sent along with the PUT request
        private void writeFile(StringBuffer uploadedFile) {
            String filename = "server_" +
                    System.currentTimeMillis() + "_" + Thread.currentThread().getId() + ".txt";
            File op = Paths.get(USER_DIR, server_op_dir,filename).toFile();
            try (FileOutputStream fos = new FileOutputStream(op)) {
                fos.write(uploadedFile.toString().getBytes());
                printToClient("HTTP/1.1 200 OK File Created");
            } catch (IOException e) {
                e.printStackTrace();
                printToClient("HTTP/1.1 500 Internal Server Error");
            }
        }

        /*
        * Method that processes a GET req from Client.
        *
        * */
        private static final String USER_DIR = System.getProperty("user.dir");
        private void processGet(String resource) {
            try {
                File file = Paths.get(USER_DIR,resource).toFile();
//                System.out.println(USER_DIR+resource);
                InputStream fis = new FileInputStream(file);
                if (fis != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(fis))) {
                        String data = null;
                        StringBuffer buf = new StringBuffer();
                        buf.append("HTTP/1.1 200 OK\r\n")
                                .append("\r\n");
                        while ((data = br.readLine()) != null) {
                            buf.append(data);
                        }
                        printToClient(buf.toString());
                    }
                } else {
                    throw new FileNotFoundException();
                }
            } catch (FileNotFoundException e) {
//                System.err.println("The requested resource " + resource + " not found!");
                printToClient("HTTP/1.1 404 Not Found");
            } catch (IOException e) {
                printToClient("HTTP/1.1 500 Internal Server Error");
                e.printStackTrace();
            }
        }
    }


}
