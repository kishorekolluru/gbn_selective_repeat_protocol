package org.kishore.http;

import java.io.IOException;
import java.net.*;
import java.util.Scanner;

public class EchoClient {
    private DatagramSocket socket;
    private InetAddress address;
 
    private byte[] buf;

    public static void main(String[] args) throws IOException {
        String msg = null;
        Scanner obj = new Scanner(System.in);
        EchoClient cli = new EchoClient();
        do{
            msg = obj.nextLine();
            System.out.println(cli.sendEcho(msg));
        }while(!msg.equals("end"));
    }
    public EchoClient() throws SocketException, UnknownHostException {
        socket = new DatagramSocket();
        address = InetAddress.getByName("localhost");
    }
 
    public String sendEcho(String msg) throws IOException {
        buf = msg.getBytes();
        DatagramPacket packet
          = new DatagramPacket(buf, buf.length, address, 4445);
        socket.send(packet);
        packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        String received = new String(
          packet.getData(), 0, packet.getLength());
        return received;
    }
 
    public void close() {
        socket.close();
    }
}