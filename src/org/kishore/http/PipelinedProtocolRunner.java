package org.kishore.http;

import javax.swing.text.Segment;
import java.io.IOException;
import java.net.*;
import java.util.*;

import static org.kishore.http.PipelinedProtocolRunner.host;

/**
 * Created by kishorekolluru on 10/21/17.
 */
public class PipelinedProtocolRunner {
    public static final String host = "localhost";
    public static int m;
    public static int N;
    public static void main(String[] args) {
        String msg = "THis is a message.Why do all this crap? This is implementation agnostic.";// One day, you might want to use this convenience on another implementation. Then you'll have to duplicate code, and hell begins. If you need a 3rd implementation too, and then add just one tiny bit of new functionality, you are doomed.";
        m = 3;
        N = 8;
        int segSize = 46;
        int timeout = 500;
        int port = 5001;

        try {
            GBNSender gbn = new GBNSender(port, msg, timeout, segSize);
            GBNReceiver gbnReceiver = new GBNReceiver(port, segSize);
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
    static Map<Long, String> soutMap = Collections.synchronizedMap(new TreeMap<>());

    public static void putIntoSmap(String str){
        soutMap.put(System.currentTimeMillis(), str);
        System.out.println(str);
    }
    static class GBNSender implements Runnable{
        private final int port;
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
        private int flag = 0;

        public GBNSender(int port, String msg, int timeoutms, int segSize) throws Exception {
            try {
                this.port = port;
                this.msg = msg;
                this.windActStart = 0;
                this.windActEnd = N - 1;
                this.sBase = 0;
                this.sNextSeq = 0;
                this.timeoutms = timeoutms;
                this.segSize = segSize;
                //<checksum 16,1 space, 2^m number of digits, 1 space
                int sz = 32 + 1 + String.valueOf((int) Math.pow(2, m)).length() +1;
                this.payloadSize = segSize - sz;
                if (this.payloadSize < 1) {
                    throw new Exception("The segment size has to be a min of " +sz);
                }
                socket = new DatagramSocket();
            } catch (Exception e) {
                throw e;
            }
            this.ackSegSize = 32+ 1 + String.valueOf((int) Math.pow(2, m)).length();
        }

        @Override
        public void run() {
            System.out.println("Initializing data array...");
            dataList = Util.initializeDataArray(msg, payloadSize);
            try {
                while (!isDataDone) {
                    //send one window
                    sendWindow();
                }
                System.out.println("Data reliably sent using GBN protocol!!");
                //end connection
                socket.send(new DatagramPacket("end".getBytes(), "end".getBytes().length, InetAddress.getByName(host), port));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private boolean isDataDone = false;

        private void sendWindow() throws IOException {

            //send eligible current window packets
            sendPackets(socket, false);

            //wait for ack
            DatagramPacket packet = new DatagramPacket(new byte[ackSegSize], ackSegSize);
            socket.receive(packet);
            //ackNum = next expected seqNum
            int ackNum = checksumAndGetAckNum(packet.getData());
            int newBase = Util.getSeqNum(ackNum, m);

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

        private void sendPackets(DatagramSocket socket, boolean retransmit) throws IOException {
            for(int i=windActStart; i<=windActEnd && i<dataList.size() ; i++){
                if(!retransmit && sNextSeq == windActStart+N)
                    break;
                Segment segment = new Segment(Util.byteObjectToByteArray(dataList.get(i)), sNextSeq);
                sendPacket(i, segSize, host, socket, port, segment);

                //if no in flight packets then there is no timer running
                if(sBase == sNextSeq){
                    startTimer();
                }
                sNextSeq++;
            }
        }

        private void sendPacket(int i, int segSize, String host, DatagramSocket socket, int port, Segment segment) throws IOException {
            DatagramPacket dgpacket = new DatagramPacket(segment.renderFullSgmnt(), Math.min(segSize,segment.renderFullSgmnt().length));
            dgpacket.setPort(port);
            dgpacket.setAddress(InetAddress.getByName(host));

            putIntoSmap("SENDER:: SENDING PACKET "+Util.getSeqNum(sNextSeq,m)+":"+new String(segment.renderFullSgmnt()));
            if(i==3 && flag==0){
                flag++;
                return;
            }
            socket.send(dgpacket);
        }

        private void startTimer(){
        timer = new Timer("timer");
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    DatagramSocket timerSocket = new DatagramSocket();
                    sendPackets(timerSocket, true);
                    System.err.println("TIMEOUT ACTION fired");
                } catch (IOException e) {
                    System.out.println("Exception during timeout resend");
                    e.printStackTrace();
                }
            }
        },timeoutms);
        }

        private void stopTimer(){
            if(timer != null) timer.cancel();
        }

        private void resetTimer(){
            stopTimer();
            startTimer();
        }

        //acks of form <checksum><space><ackNum>
        private int checksumAndGetAckNum(byte[] data) {
            String ack = new String(data);
            putIntoSmap("SENDER:: Checking ACK " + ack);
            int ackNum = -1;

            byte[] databytes = ack.split(" ")[1].getBytes();
            String chkSum = ack.split(" ")[0];
            if (Util.checksumString(databytes).equals(chkSum)) {
                ackNum = Integer.parseInt(ack.split(" ")[1]);
            }
            return ackNum;
        }
    }



    static class GBNReceiver implements Runnable{
        private final int port;
        private final int segSize;
        private final int ackSegSize;
        private int senderPort;
        private InetAddress senderAddr;

        private int ack = -1;
        private String payload;
        private int expSeqNum;
        private int rcvrBase;

        private final DatagramSocket socket;

        public GBNReceiver(int port, int bufferSize) throws SocketException {
            this.port = port;
            this.socket = new DatagramSocket(port);
            this.segSize = bufferSize;
            this.expSeqNum = 0;
            this.rcvrBase = 0;
            this.payload = "";
            this.senderAddr = null;
            this.senderPort = -1;
            this.ackSegSize = 32 +1 + String.valueOf((int) Math.pow(2, m)).length();
        }

        @Override
        public void run() {
            while(true){
                DatagramPacket packet = new DatagramPacket(new byte[segSize], segSize);
                try {
                    socket.receive(packet);
                    putIntoSmap("RCVR  :: DATA RECEIVED "+new String(packet.getData()));
                    // frst time received only

                    if(new String(packet.getData()).startsWith("end")){
                        break;
                    }

                    if(senderAddr==null && senderPort==-1){
                        senderAddr = packet.getAddress();
                        senderPort = packet.getPort();
                    }

                    //check if new packet is expected. If not, send the old ack
                    if(Util.isNotCorrupt(packet) && isExpectedSeqNum(packet)){
                        List<String> paylArr = Util.extractMsg(packet);
                        String msg = paylArr.get(2);
//                        System.err.println("RECEIVED PACKET "+ rcvrBase+" :"+ msg);
                        rcvrBase++;
                        expSeqNum = Util.getSeqNum(rcvrBase, m);//expNum++
                    }

                    sendAck(expSeqNum);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }



        private boolean isExpectedSeqNum(DatagramPacket packet) {
            List<String> paylList = Util.extractMsg(packet);
            return Integer.parseInt(paylList.get(1)) == expSeqNum;
        }

        private void sendAck(int packetNum) throws IOException {
            payload = Util.checksumString(String.valueOf(packetNum).getBytes())
                    +" "
                    +Util.renderSeqNbrForTransport(packetNum, m);
            DatagramPacket packet = createAckPacket(payload);
            //TODO loss logic
            putIntoSmap("RCVR  :: ACK sent "+payload);
            socket.send(packet);
        }

        private DatagramPacket createAckPacket(String payload) {
            return new DatagramPacket(payload.getBytes(), ackSegSize,
                    senderAddr, senderPort);
        }
    }

    static class Segment {
        private String checksum;
        private byte[] data;
        private int seqNbr;

        public Segment(byte[] data, int seqNbr) {
            this.data = data;
            this.seqNbr = seqNbr;
        }

        public byte[] renderFullSgmnt(){
            StringBuilder builder = new StringBuilder();
            builder.append(getChecksum())
                    .append(' ')
                    .append(Util.renderSeqNbrForTransport(Util.getSeqNum(this.seqNbr, m),m))
                    .append(' ')
                    .append(new String(this.data));
            return builder.toString().getBytes();
        }
        public String getChecksum() {
            if (checksum == null) {
                checksum = Util.checksumString(data);
            }
            return checksum;
        }

        public void setChecksum(String checksum) {
            this.checksum = checksum;
        }

        public byte[] getData() {
            return data;
        }

        public void setData(byte[] data) {
            this.data = data;
        }

        public int getSeqNbr() {
            return seqNbr;
        }

        public void setSeqNbr(int seqNbr) {
            this.seqNbr = seqNbr;
        }

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
//                                    while(true){
//                                        packet1 = new DatagramPacket(new byte[l*3], l*3);
//                                        socket1.receive(packet1);
//                                        System.err.println("Devil gets back " + new String(packet1.getData()));
//                                    }
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
            InetAddress addr = null;
            int port = -1;
            while(true){
                socket.receive(packet);
                String msg = new String(packet.getData());
                System.out.println("Received ..."+ msg);
//                if(msg.equals("end")) break;

//                Thread.sleep(25);
                if(addr == null && port ==-1){
                    addr = packet.getAddress();
                    port = packet.getPort();
                }
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



















