package org.kishore.http;


import java.io.*;
import java.net.Socket;

/**
 * Created by kishorekolluru on 9/16/17.
 */
public class HttpClient {

    public static final String crlf = "\r\n";
    public static final String space = " ";
    public static final String http11 = "HTTP/1.1";
    public static final String content_length = "Content-Length:";

    public static void main(String[] args) {
        Socket socket = null;
        DataOutputStream os = null;
        BufferedReader is = null;
        String hostname = args[0];
        int port = Integer.parseInt(args[1]);
        String cmd = args[2];
        String filename = args[3];

        try {
            socket = openSocket(hostname, port);
            os = new DataOutputStream(socket.getOutputStream());
            is = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            if(socket!=null && os!=null && is!=null){
                String req = formHttpReq(cmd, filename);
                System.out.println(req);
                os.writeBytes(req);

                String line = null;
                while((line=is.readLine()) !=null){
                    System.out.println("Server: " + line);
                }
                is.close();
                os.close();
                socket.close();

                System.out.println("Response ended...Connection with server terminated gracefully");
            }

        } catch (IOException e) {
            System.err.println("File issue: "+e.getMessage());
        }catch(Exception e){
            System.err.println(e.getMessage());
        }
    }

    public static Socket openSocket(String serverName, int port) throws IOException {
        return new Socket(serverName, port);
    }

    private static final String USER_DIR = System.getProperty("user.dir");
    private static String formHttpReq(String method, String resource) throws IOException {
        StringBuffer sbuf = new StringBuffer();
        if(!resource.startsWith("/")) resource = "/"+resource;
        sbuf.append(method)
                .append(space)
                .append(resource)
                .append(space)
                .append(http11)
                .append(crlf);
        if(method.equals("PUT")){
            InputStream is = new FileInputStream(USER_DIR+resource);

            if(is!=null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(is));
                String line = null;
                StringBuffer buf = new StringBuffer();
                while ((line = br.readLine()) != null) {
                    buf.append(line);
                }
                //Content-Length: 123
                sbuf.append(content_length)
                        .append(buf.length())
                        .append(crlf)

                        //empty line
                        .append(crlf)

                        //body after empty line
                        .append(buf.toString())
                        .append(crlf);

            }else{
                throw new IOException("The file name given does not exist");
            }

        }
//        sbuf.append(crlf);
        return sbuf.toString();
    }
}
