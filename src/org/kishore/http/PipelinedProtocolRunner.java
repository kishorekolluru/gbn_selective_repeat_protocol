package org.kishore.http;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;

import static org.kishore.http.PipelinedProtocolRunner.host;

/**
 * Created by kishorekolluru on 10/21/17.
 */
public class PipelinedProtocolRunner {
    public static final String host = "localhost";
    public static void main(String[] args) {
        String msg = "THis is a message.Why do all this crap? This is implementation agnostic. One day, you might want to use this convenience on another implementation. Then you'll have to duplicate code, and hell begins. If you need a 3rd implementation too, and then add just one tiny bit of new functionality, you are doomed.";
        int m = 3;
        int N = 8;
        int segSize = 30;
        int timeout = 10000;
        int port = 5001;

//        segSize = segSize + 17 + String.valueOf((int)Math.pow(2,m)).length();

        try {
            GBNSender gbn = new GBNSender(port, msg, m, N, timeout, segSize);
            GBNReceiver gbnReceiver = new GBNReceiver(port, segSize, m);
            Thread t = new Thread(gbn);
            Thread rcvr = new Thread(gbnReceiver);
            t.start();
            rcvr.start();
            t.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (SocketException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}

class GBNSender implements Runnable{
    private final int port;
    private final int m;
    private final int N;
    private final int timeoutms;
    private final int segSize;
    private final int ackSegSize;
    private int windActEnd;
    private int windActStart;
    private String msg;
    private int sBase;
    private int sNextSeq;
    private int payloadSize;
    //Data
    private List<Byte[]> dataList = new ArrayList<>();
    DatagramSocket socket;
    Timer timer;

    public GBNSender(int port, String msg, int m, int N, int timeoutms, int segSize) throws Exception {
        try {
            this.port = port;
            this.msg = msg;
            this.m = m;
            this.N = N;
            this.windActStart = 0;
            this.windActEnd = N - 1;
            this.sBase = 0;
            this.sNextSeq = 0;
            this.timeoutms = timeoutms;
            this.segSize = segSize;
            //<checksum 16,1 space, 2^m number of digits, 1 space
            int sz = 16 + 1 + String.valueOf((int) Math.pow(2, m)).length() +1;
            this.payloadSize = segSize - sz;
            if (this.payloadSize < 1) {
                throw new Exception("The segment size has to be a min of " +sz);
            }
            socket = new DatagramSocket();
        } catch (Exception e) {
            throw e;
        }
        this.ackSegSize = 17 + String.valueOf((int) Math.pow(2, m)).length();
    }

    @Override
    public void run() {
        System.out.println("Initializing data array...");
        initializeDataArray();

        while(!isDataDone){
            //send one window
            try {
                sendWindow();
            } catch (Exception e) {
                e.printStackTrace();
                break;
            }
        }
        System.out.println("Data reliably sent using GBN protocol!!");
    }

    private boolean isDataDone = false;

    private void sendWindow() throws IOException {

        //send eligible current window packets
        sendPackets(socket);

        //wait for ack
        DatagramPacket packet = new DatagramPacket(new byte[ackSegSize], ackSegSize);
        socket.receive(packet);
        int ackNum = checksumAndGetAckNum(packet.getData());
        int newBase = Util.getSeqNum(++ackNum, m);

        //if ack corrupt or repeatAck then ignore packet
        if (ackNum == -1 || newBase == (sBase % Math.pow(2, m)))
            return;

        sBase = Util.getActualWindowSeqNum(newBase, windActStart, windActEnd, m);
        //get the actual value till where the window has to slide
        windActStart = sBase;
        windActEnd = windActStart + N - 1;

        //if all data sent and acked
        if (windActStart > dataList.size()) {
            isDataDone = true;
        }

        //if nothing in flight
        if (sBase == sNextSeq) {
            stopTimer();
        } else {//start new timer for new sBase
            resetTimer();
        }

    }


    private void startTimer(){
//        timer = new Timer("timer");
//        timer.schedule(new TimerTask() {
//            @Override
//            public void run() {
//                try {
//                    DatagramSocket timerSocket = new DatagramSocket();
//                    sendPackets(timerSocket);
//                } catch (IOException e) {
//                    System.out.println("Exception during timeout resend");
//                    e.printStackTrace();
//                }
//            }
//        },timeoutms);
    }

    private void stopTimer(){
        if(timer != null) timer.cancel();
    }

    private void resetTimer(){
        stopTimer();
        startTimer();
    }
    private void sendPackets(DatagramSocket socket) throws IOException {
        for(int i=windActStart; i<windActEnd && i<dataList.size(); i++){
//            if(sNextSeq == (windActStart+N) % Math.pow(2, m))
            if(sNextSeq == windActStart+N)
                break;
            sendPacket(i, socket);
        }
    }

    private void sendPacket(int i, DatagramSocket socket) throws IOException {

        byte[] bytesToSend = createPacketBytes(i);

        DatagramPacket packet = new DatagramPacket(bytesToSend, Math.min(segSize,bytesToSend.length));
        packet.setPort(port);
        packet.setAddress(InetAddress.getByName(host));
        socket.send(packet);
        System.out.println("SENDING PACKET "+Util.getSeqNum(sNextSeq,m)+":"+new String(bytesToSend));

        //if no in flight packets then there is no timer running
        if(sBase == sNextSeq){
            startTimer();
        }
//        sNextSeq = ++sNextSeq % m;
        ++sNextSeq;
    }

    private static class Packet{
//        private
    }
    private byte[] createPacketBytes(int i) {
        byte[] packetBytes;
        byte[] databytes = byteObjectToByteArray(dataList.get(i));
        StringBuilder builder = new StringBuilder();

        byte[] ccksumNseqNumbytes = builder.append(Util.checksumString(databytes))
                .append(' ')
                .append(Util.renderSeqNbrForTransport(Util.getSeqNum(sNextSeq, m),m))
                .append(' ').toString().getBytes();


        packetBytes = new byte[ccksumNseqNumbytes.length + databytes.length];
        int j = 0;
        for (j = 0; j < ccksumNseqNumbytes.length; j++) {
            packetBytes[j] = ccksumNseqNumbytes[j];
        }
        for (int k = 0; k < databytes.length; k++) {
            packetBytes[j + k] = databytes[k];
        }

        return packetBytes;
    }

    //acks of form <checksum><space><ackNum>
    private int checksumAndGetAckNum(byte[] data) {
        String ack = new String(data);
        System.out.println("Checking ACK "+ack);
        //TODO check if corrupt then return -1;
        int ackNum = Integer.parseInt(ack.split(" ")[1]);
        return ackNum;
    }

    private int getsBase() {
        return windActStart % (int) Math.pow(2, m);
    }

    private byte[] byteObjectToByteArray(Byte[] byteObjects) {
        int j=0;
        byte[] bytes = new byte[byteObjects.length];
        // Unboxing byte values. (Byte[] to byte[])
        for(Byte b: byteObjects)
            bytes[j++] = b.byteValue();
        return bytes;
    }


    private void initializeDataArray() {
        byte[] msgBytes = msg.getBytes();
        List<Byte> byteSeg = new ArrayList<>();
        int msgPartSize = payloadSize;
//        int msgPartSize = segSize - 17 - String.valueOf((int)Math.pow(2,m)).length();
        System.out.println("Message part size = "+msgPartSize);

        //split the msg bytes into msgpartsize segments
        for (int i = 0; i < msgBytes.length; i++) {
            byteSeg.add(msgBytes[i]);
            if (byteSeg.size() == msgPartSize || i == msgBytes.length - 1){
                dataList.add(byteSeg.toArray(new Byte[0]));
                byteSeg.clear();
            }
        }
    }
}

class GBNReceiver implements Runnable{
    private final int port;
    private final int segSize;
    private final int ackSegSize;
    private int senderPort;
    private InetAddress senderAddr;

    private int ack = -1;
    private String payload;
    private int expSeqNum;
    private int rcvrBase;
    private int m;

    private final DatagramSocket socket;

    public GBNReceiver(int port, int bufferSize, int m) throws SocketException {
        this.port = port;
        this.socket = new DatagramSocket(port);
        this.segSize = bufferSize;
        this.expSeqNum = 0;
        this.rcvrBase = 0;
        this.payload = "";
        this.senderAddr = null;
        this.senderPort = -1;
        this.m = m;
        this.ackSegSize = 17 + String.valueOf((int) Math.pow(2, m)).length();
    }

    @Override
    public void run() {
        while(true){
            DatagramPacket packet = new DatagramPacket(new byte[segSize], segSize);
            try {
                socket.receive(packet);
                System.err.println("DATA RECEIVED "+new String(packet.getData()));

                // frst time received only
                if(senderAddr==null && senderPort==-1){
                    senderAddr = packet.getAddress();
                    senderPort = packet.getPort();
                }

                //check if new packet is expected. If not, send the old ack
                if(isNotCorrupt(packet) && isExpectedSeqNum(packet)){
                    List<String> paylArr = extractMsg(packet);
                    String msg = paylArr.get(2);
                    System.err.println("RECEIVED PACKET "+ rcvrBase+" :"+ msg);
                    rcvrBase++;
                    expSeqNum = Util.getSeqNum(rcvrBase, m);//expNum++
                }

                sendAck(expSeqNum);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean isNotCorrupt(DatagramPacket packet) {
        List<String> paylArr = extractMsg(packet);
        String computedChecksum = Util.checksumString(paylArr.get(2).getBytes());
        return computedChecksum.equals(paylArr.get(0));
    }

    private boolean isExpectedSeqNum(DatagramPacket packet) {
        List<String> paylList = extractMsg(packet);
        return Integer.parseInt(paylList.get(1)) == expSeqNum;
    }

    private List<String> extractMsg(DatagramPacket packet) {
        String payl = new String(packet.getData());
        List<String> strList = new ArrayList<>();
        int firstSpaceInd = payl.indexOf(' ');
        int secSpaceInd = payl.indexOf(' ', firstSpaceInd + 1);
        //checksum
        strList.add(payl.substring(0, firstSpaceInd));
        //seqNum
        strList.add(payl.substring(firstSpaceInd + 1, secSpaceInd));
        //payload
        strList.add(payl.substring(secSpaceInd + 1));

        return strList;
    }

    private void sendAck(int packetNum) throws IOException {
        payload = Util.checksumString(String.valueOf(packetNum).getBytes())
                +" "
                +Util.renderSeqNbrForTransport(packetNum, m);
        DatagramPacket packet = createAckPacket(payload);
        //TODO loss logic
        System.err.println("ACK sent "+payload);
        socket.send(packet);
    }

    private DatagramPacket createAckPacket(String payload) {
        return new DatagramPacket(payload.getBytes(), ackSegSize,
                senderAddr, senderPort);
    }
}
class Sample{
    public static void main(String[] args) throws InterruptedException {
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
//                        Thread.sleep(2000);
                        int l = "hi1".getBytes().length;
                        DatagramSocket socket =  new DatagramSocket();
                        DatagramPacket packet = null;
                        for(int i=0; i<3; i++){
                            String msg = ("hi"+i);
                            if(i==2) msg = "end";
                            packet = new DatagramPacket(msg.getBytes(), l);
                            packet.setAddress(InetAddress.getByName(host));
                            packet.setPort(5001);
                            socket.send(packet);
                        }

                        System.err.println("Sender sent all...");

                        Thread t1 = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                int l = "hi1".getBytes().length;
                                try {
                                    Thread.sleep(1000);
                                    DatagramSocket socket1 = new DatagramSocket();
                                    System.out.println("Devil thread doing imp work...");
                                    DatagramPacket packet1 =
                                            new DatagramPacket("dv1".getBytes(), "dv1".getBytes().length,
                                                    InetAddress.getByName(host),5001);
                                    socket1.send(packet1);

                                    System.out.println("Imp has done its work");
                                    while(true){
                                        packet1 = new DatagramPacket(new byte[l*3], l*3);
                                        socket1.receive(packet1);
                                        System.err.println("Devil gets back " + new String(packet1.getData()));
                                    }
                                } catch (UnknownHostException e) {
                                    e.printStackTrace();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                        t1.start();
                        while(true){
                            packet = new DatagramPacket(new byte[l*3], l*3);
                            socket.receive(packet);
                            System.err.println("Sender gets back " + new String(packet.getData()));
                        }

                    } catch (SocketException e) {
                        e.printStackTrace();
                    } catch (UnknownHostException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                }
            });


            t.start();

            DatagramSocket socket = new DatagramSocket(5001);
            int l = "hi1".getBytes().length;
            DatagramPacket packet = new DatagramPacket(new byte[l],l);


            //receiver wait
            while(true){
                socket.receive(packet);
                String msg = new String(packet.getData());
                System.out.println("Received ..."+ msg);
//                if(msg.equals("end")) break;

//                Thread.sleep(25);
                InetAddress addr = packet.getAddress();
                int port = packet.getPort();
                packet = new DatagramPacket(msg.getBytes(),msg.getBytes().length , addr, port);
                socket.send(packet);
                System.out.println("Echoed back "+msg+"...");
            }

//            System.out.println("Ended");
        } catch (SocketException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}



















