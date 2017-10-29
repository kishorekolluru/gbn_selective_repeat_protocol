package org.kishore.http;

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
    public static int WINDOW_SIZE;
    public static int seqNumRenderLength;
    private static final double PROBABILITY_PKT_LOSS = 0.1;

//    public static int datalistSize = -1;
    public static void main(String[] args) {
        String msg = "THis is a message.Why do all this crap? This is implementation agnostic. One day, you might want to use this convenience on another implementation. Then you'll have to duplicate code, and hell begins. If you need a 3rd implementation too, and then add just one tiny bit of new functionality, you are doomed.";
        m = 3;
        WINDOW_SIZE = 8;

        int segSize = 46;
        int timeout = 1000;
        int port = 5001;
        seqNumRenderLength = String.valueOf((int)Math.pow(2, m)).length();
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
    static Map<Long, String> soutSenderMap = Collections.synchronizedMap(new TreeMap<>());
    static Map<Long, String> soutRcrMap = Collections.synchronizedMap(new TreeMap<>());

    static class GBNSender implements Runnable{

        private final int port;
        private final int timeoutms;
        private final int segSize;
        private final int ackSegSize;
        private int windActEnd;
        private int windActStart;
        private String msg;
        private int lastAcked;
        private int lastSent;
        private int payloadSize;
        //Data
        private List<Byte[]> dataList = new ArrayList<>();
        DatagramSocket toReceiver;
        Timer timer;
        private int flag = 0;

        public static void putIntoSmap(String str){
            soutSenderMap.put(System.currentTimeMillis(), str);
            System.out.println(str);
        }
        public GBNSender(int port, String msg, int timeoutms, int segSize) throws Exception {
            try {
                this.port = port;
                this.msg = msg;
                this.windActStart = 0;
                this.windActEnd = WINDOW_SIZE - 1;
                this.lastAcked = 0;
                this.lastSent = 0;
                this.timeoutms = timeoutms;

                this.segSize = segSize;
                this.ackSegSize = 32+ 1 + seqNumRenderLength;

                //<checksum 16,1 space, list size digits length, 1 space
                int sz = 32 + 1 + seqNumRenderLength +1;
                this.payloadSize = segSize - sz;
                if (this.payloadSize < 1) {
                    throw new Exception("The segment size has to be a min of " +sz);
                }

                toReceiver = new DatagramSocket();
            } catch (Exception e) {
                throw e;
            }
        }

        @Override
        public void run() {
            System.out.println("Initializing data array...");

            dataList = Util.initializeDataArray(msg, payloadSize);
//            datalistSize = dataList.size();
            System.out.println("TOTAL packets :"+dataList.size());
            try {
                while (!isDataDone) {
                    //send one window
                    sendWindow();
                }
                System.out.println("Data reliably sent using GBN protocol!!");
                //end connection
                toReceiver.send(new DatagramPacket("end".getBytes(), "end".getBytes().length, InetAddress.getByName(host), port));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private boolean isDataDone = false;

        private void sendWindow() throws IOException {

            //send eligible current window packets
            sendPackets();

            //wait for ack
            DatagramPacket packet = new DatagramPacket(new byte[ackSegSize], ackSegSize);
            toReceiver.receive(packet);

            //ackNum = next expected seqNum
            int ackNum = checksumAndGetAckNum(packet.getData());
//            int newBase = Util.getSeqNum(ackNum, m);

            //if ack corrupt or repeatAck then ignore packet
            if (ackNum == -1 || ackNum == (lastAcked % Math.pow(2, m)))
                return;

//            lastAcked = Util.getActualWindowSeqNum(ackNum, windActStart, windActEnd, m);
            lastAcked = Util.getActualWindowSeqNum(ackNum, windActStart, windActEnd, m);
            //get the actual value till where the window has to slide
            windActStart = lastAcked;
            windActEnd = windActStart + WINDOW_SIZE - 1;

            //if all data sent and acked
            if (windActStart >= dataList.size()) {
                isDataDone = true;
            }

            //if nothing in flight
            if (lastAcked == lastSent) {
                stopTimer();
            } else {//start new timer for new lastAcked
                resetTimer();
            }

        }

        private void sendPackets() throws IOException {
//            for(int i=windActStart; i<=windActEnd && i<dataList.size() ; i++){
            while(this.lastSent < dataList.size() && lastSent - lastAcked < WINDOW_SIZE){
                Segment segment = new Segment(Util.byteObjectToByteArray(
                        dataList.get(lastSent)),
                        Util.getSeqNum(lastSent,m),
                        Util.checksumString(Util.byteObjectToByteArray(dataList.get(lastSent))));
                sendPacket(lastSent, segSize, host, toReceiver, port, segment);
//                System.out.println("STRING :" + new String(Util.byteObjectToByteArray(dataList.get(lastSent))));
                //if no in flight packets then there is no timer running
                if(lastAcked == lastSent){
                    startTimer();
                }
                lastSent++;
            }
        }

        private void resendPackets(DatagramSocket toReceiverTimer) throws IOException {
            int i = windActStart;
            while(i < lastSent){
                Segment segment = new Segment(
                        Util.byteObjectToByteArray(dataList.get(i)),
                        Util.getSeqNum(i,m),
                        Util.checksumString(Util.byteObjectToByteArray(dataList.get(i))));

                sendPacket(i, segSize, host, toReceiverTimer, port, segment);
                i++;
            }
        }
        private void sendPacket(int nexSeq, int segSize, String host, DatagramSocket socket, int port, Segment segment) throws IOException {
            DatagramPacket dgpacket = new DatagramPacket(
                    segment.renderFullSgmnt(),
                    Math.min(segSize,segment.renderFullSgmnt().length));
            dgpacket.setPort(port);
            dgpacket.setAddress(InetAddress.getByName(host));

            putIntoSmap("SENDING PACKET " + segment);
//            if(nexSeq == 3 && flag==0){
//                flag++;
//                return;
//            }
            if(Math.random() > PROBABILITY_PKT_LOSS){
                socket.send(dgpacket);
            }else{
                System.out.println("PKT LOSS "+segment.getSeqNbr());
            }
        }

        private void startTimer(){
        timer = new Timer("timer");
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    DatagramSocket timerSocket = new DatagramSocket();
//                    sendPackets();
                    resendPackets(timerSocket);
                    System.err.println("TIMEOUT fired");
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
            putIntoSmap("Checking ACK " + ack);
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
        private int ackSegSize;
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
            //32 bytes for chksum, space, acknum
            this.ackSegSize = 32 +1 + seqNumRenderLength;
        }

        public static void putIntoSmap(String str){
            soutRcrMap.put(System.currentTimeMillis(), str);
            System.out.println(str);
        }

        @Override
        public void run() {
            while(true){
                DatagramPacket packet = new DatagramPacket(new byte[segSize], segSize);
                this.ackSegSize = 32 +1 + seqNumRenderLength;

                try {
                    socket.receive(packet);
                    if(new String(packet.getData()).startsWith("end")){
                        break;
                    }
                    putIntoSmap("DATA RECEIVED " + Util.extractSegment(packet));
                    // frst time received only


                    if(senderAddr==null && senderPort==-1){
                        senderAddr = packet.getAddress();
                        senderPort = packet.getPort();
                    }
                    if(new String(packet.getData()).contains("ion.")){

                    }
                    //check if new packet is expected. If not, send the old ack
                    if(Util.isNotCorrupt(packet) && isExpectedSeqNum(packet)){
                        Segment segment = Util.extractSegment(packet);
                        String msg = new String(segment.getData());
//                        System.err.println("RECEIVED PACKET "+ rcvrBase+" :"+ msg);
                        rcvrBase++;
                        expSeqNum = Util.getSeqNum(rcvrBase, m);//expNum++
//                        expSeqNum++;
                    }

                    sendAck(expSeqNum);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }



        private boolean isExpectedSeqNum(DatagramPacket packet) {
            Segment segment = Util.extractSegment(packet);
            return segment.getSeqNbr() == expSeqNum;
        }

        private void sendAck(int packetNum) throws IOException {
            payload = Util.checksumString(Util.renderSeqNbrForTransport(packetNum, m).getBytes())
                    +" "
                    +Util.renderSeqNbrForTransport(packetNum, m);
            this.ackSegSize = 32 +1 + seqNumRenderLength;
            DatagramPacket packet = createAck(payload);
            //TODO loss logic
            putIntoSmap("ACK sent "+packetNum);
            socket.send(packet);
        }

        private DatagramPacket createAck(String payload) {
            return new DatagramPacket(payload.getBytes(), ackSegSize,
                    senderAddr, senderPort);
        }
    }

    static class Segment {
        protected String checksum;
        protected byte[] data;
        protected int seqNbr;

        public Segment(byte[] data, int seqNbr, String checksum) {
            this.data = data;
            this.seqNbr = seqNbr;
            this.checksum = checksum;
        }

        public byte[] renderFullSgmnt(){
            StringBuilder builder = new StringBuilder();

            String format = "%0"+ seqNumRenderLength +"d";

            builder.append(checksum)
                    .append(' ')
                    .append(String.format(format, seqNbr))
                    .append(' ')
                    .append(new String(this.data));
            return builder.toString().getBytes();
        }
        public String getChecksum() {
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

        @Override
        public String toString() {
            return seqNbr + "\t" + new String(data);
        }
    }










    static class SrSegment extends Segment{
        private InetAddress addr;
        private int port;
        private Timer segTimer;
        private int timeoutms;

        private SrSegment(byte[] data, int seqNbr, String checksum) {
            super(data, seqNbr, checksum);
        }

        public SrSegment(byte[] data, int seqNbr, String checksum, int timeoutms, InetAddress addr, int port){
            super(data, seqNbr, checksum);
            this.timeoutms = timeoutms;
            this.addr = addr;
            this.port = port;
        }

        public void startTimer(){
            segTimer = new Timer("timer");
            segTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        DatagramSocket timerSocket = new DatagramSocket();
                        System.err.println("Seg TIMEOUT fired");
//                        DatagramPacket packet = new DatagramPacket();

                    } catch (IOException e) {
                        System.out.println("Exception during timeout resend");
                        e.printStackTrace();
                    }
                }
            },timeoutms);
        }

        public void stopTimer(){
            if(segTimer != null) segTimer.cancel();
        }

        public void resetTimer(){
            stopTimer();
            startTimer();
        }
    }

    static class SrSender implements Runnable{
        private List<SrSegment> segmentList;

        public SrSender(String msg, int segmentSize){
//            initializeSegmentList()
        }
        @Override
        public void run() {
//            initialize
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



















