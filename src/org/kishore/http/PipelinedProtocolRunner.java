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
    private static final double PROBABILITY_BIT_ERROR = 0.1;
    private static final double PROBABILITY_ACK_LOSS = 0.05;

    public static final boolean DEBUG = false;

    public static void main(String[] args) {

        //params
        String msg = "THis is a message.Why do all this crap? This is implementation agnostic. One day, you might want to use this convenience on another implementation. Then you'll have to duplicate code, and hell begins. If you need a 3rd implementation too, and then add just one tiny bit of new functionality, you are doomed.";
        m = 4;
        WINDOW_SIZE = 8;
        int segSize = 46;
        int timeout = 500;
        int port = 5001;
        seqNumRenderLength = String.valueOf((int)Math.pow(2, m)).length();

        String alg = "GBN";
        try {
            Thread sndr, rcvr;
            Runnable senderThread= null, recvThread = null;
            if (alg.equalsIgnoreCase("GBN")) {
                senderThread = new GBNSender(port, msg, timeout, segSize);
                recvThread = new GBNReceiver(port, segSize);

            } else if (alg.equalsIgnoreCase("SR")) {
                senderThread = new SrSender(msg, segSize, timeout, InetAddress.getByName(host), port);
                recvThread = new SrReceiver(segSize, port);
            }
            sndr = new Thread(senderThread);
            rcvr = new Thread(recvThread);
            sndr.start();
            rcvr.start();
            sndr.join();

            Thread.sleep(2);
            System.out.println("\nData reliably sent using "+alg+" protocol!!");
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
        private boolean isDataDone = false;

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

        private boolean isBitError = false;
        @Override
        public void run() {
            try {
                Thread.sleep(10);
                System.out.println("Initializing data array...");
                dataList = Util.initializeDataArray(msg, payloadSize);
                System.out.println("TOTAL packets :" + dataList.size());

                while (!isDataDone) {
                    //send one window at a time
                    //send eligible current window packets
                    sendPackets();

                    //wait for ack
                    DatagramPacket packet = new DatagramPacket(new byte[ackSegSize], ackSegSize);
                    toReceiver.receive(packet);
                    //here ackNum = next expected seqNum
                    int ackNum = Util.checksumAndGetAckNum(packet.getData());

                    //if ack corrupt or repeatAck then ignore packet
                    if (ackNum == -1 || ackNum == (lastAcked % Math.pow(2, m)))
                        continue;
                    //Simulated loss
                    if(Math.random() < PROBABILITY_ACK_LOSS){
                        System.out.println("Dropped ACK "+ ackNum);
                        continue;
                    }

                    putIntoSmap("Received ACK " + ackNum);
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
                stopTimer();
                System.out.println("Sender Process Done...");
                //end connection
                toReceiver.send(new DatagramPacket("end".getBytes(), "end".getBytes().length, InetAddress.getByName(host), port));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }


        private void sendPackets() throws IOException {
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
            resetTimer();
        }
        private void sendPacket(int nexSeq, int segSize, String host, DatagramSocket socket, int port, Segment segment) throws IOException {
            if( Math.random() < PROBABILITY_BIT_ERROR){
                System.out.println("BIT error "+ segment.getSeqNbr());
                segment.setData(Util.corruptByte(segment.getData()));
            }
            DatagramPacket dgpacket = new DatagramPacket(
                    segment.renderFullSgmnt(),
                    Math.min(segSize,segment.renderFullSgmnt().length));
            dgpacket.setPort(port);
            dgpacket.setAddress(InetAddress.getByName(host));

            putIntoSmap("Sending Seg " + segment);

            socket.send(dgpacket);
        }

        private synchronized void startTimer() {
            timer = new Timer("timer"+System.currentTimeMillis(), true);
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        DatagramSocket timerSocket = new DatagramSocket();
                        System.err.print("RESENDING ");
                        int i = windActStart;
                        while (i < lastSent) {
                            System.err.print("Seg" + Util.getSeqNum(i, m) + " ");
                            i++;
                        }
                        System.err.println("...");
                        resendPackets(timerSocket);
                    } catch (IOException e) {
                        System.out.println("Exception during timeout resend");
                        e.printStackTrace();
                    }
                }
            }, timeoutms);
        }

        private synchronized void stopTimer(){
            if(timer != null){
                timer.cancel();
                timer = null;
            }
        }

        private synchronized void resetTimer(){
            stopTimer();
            startTimer();
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
            System.err.println(str);
        }

        @Override
        public void run() {         //GBN Receiver
            while(true){
                DatagramPacket packet = new DatagramPacket(new byte[segSize], segSize);
                this.ackSegSize = 32 +1 + seqNumRenderLength;

                try {
                    socket.receive(packet);
                    if(new String(packet.getData()).startsWith("end")){
                        break;
                    }
                    if(Math.random() < PROBABILITY_PKT_LOSS){
                        System.err.println("Packet LOST "+Util.extractSegment(packet));
                        continue;
                    }
//                    putIntoSmap("DATA RECEIVED " + Util.extractSegment(packet));
                    // frst time received only
                    if(senderAddr==null && senderPort==-1){
                        senderAddr = packet.getAddress();
                        senderPort = packet.getPort();
                    }

                    //check if new packet is expected. If not, send the old ack
                    if(Util.isSegmentNotCorrupt(packet) && isExpectedSeqNum(packet)){
                        putIntoSmap("Received Seg " + Util.extractSegment(packet));
                        Segment segment = Util.extractSegment(packet);
                        String msg = new String(segment.getData());
//                        System.err.println("RECEIVED PACKET "+ rcvrBase+" :"+ msg);
                        rcvrBase++;
                        expSeqNum = Util.getSeqNum(rcvrBase, m);//expNum++
                    }

                    sendAck(expSeqNum);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            putIntoSmap("Receiver process done...");
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










    //seqNum is actualSeqNum
    static class SrSegment extends Segment{
        private InetAddress addr;
        private int port;
        private Timer segTimer;
        private int timeoutms;
        public int segmentSize;
        private boolean isAcked;

        public SrSegment(byte[] data, int seqNbr, String checksum) {
            super(data, seqNbr, checksum);
        }

        public SrSegment(byte[] data, int seqNbr, String checksum, int segmentSize, int timeoutms, InetAddress addr, int port){
            super(data, seqNbr, checksum);
            this.timeoutms = timeoutms;
            this.addr = addr;
            this.port = port;
            this.segmentSize = segmentSize;
            this.isAcked = false;
        }

        @Override
        public byte[] renderFullSgmnt(){
            StringBuilder builder = new StringBuilder();

            builder.append(checksum)
                    .append(' ')
                    .append(Util.getSeqNum(seqNbr,m))
                    .append(' ')
                    .append(new String(this.data));
            return builder.toString().getBytes();
        }

        public void startTimer(){
            segTimer = new Timer("timer-"+seqNbr, true);
            segTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        DatagramSocket timerSocket = new DatagramSocket();
                        DatagramPacket packet = new DatagramPacket(renderFullSgmnt(), Math.min(renderFullSgmnt().length, segmentSize), addr, port);
                        timerSocket.send(packet);
                        System.err.println("TIMEOUT Resent " + Util.getSeqNum(seqNbr, m)+":"+new String(data));
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

        public boolean isAcked() {
            return isAcked;
        }

        public void setAcked(boolean acked) {
            isAcked = acked;
            stopTimer();
        }

        @Override
        public String toString() {
            return Util.getSeqNum(seqNbr,m) + "\t" + new String(data);
        }
    }



    static class SrSender implements Runnable{
        private final int ackSegSize;
        private List<SrSegment> segmentList;
        private InetAddress addr;
        private int port;
        private int timeoutms;
        private int segmentSize;
        //SR vars
        private int base = 0;
        private int nSeqNum = 0;
        private DatagramSocket toReceiver;

        public SrSender(String msg, int segmentSize, int timeoutms, InetAddress addr, int port) throws SocketException {
            segmentList = initializeSegmentList(msg, segmentSize, timeoutms, addr, port);
            if(DEBUG)
            for(SrSegment seg : segmentList){
                System.out.println(new String(seg.renderFullSgmnt())+ "  "+seg.renderFullSgmnt().length);
            }
            this.segmentSize = segmentSize;
            this.timeoutms = timeoutms;
            this.port = port;
            this.addr = addr;
            this.toReceiver = new DatagramSocket();
            this.ackSegSize = 33 + String.valueOf(segmentList.size()).length();
        }

        private List<SrSegment> initializeSegmentList(String msg, int segmentSize, int timeoutms, InetAddress addr, int port) {
            byte[] msgBytes = msg.getBytes();
            List<SrSegment> segmentList = new ArrayList<>();
            //packet num count
            int count = 0;
            int msgByteptr = 0;

            while(true) {
                int i = 32 + 1 +1;//32 bits of chksum + 1space
                i = i + String.valueOf(Util.getSeqNum(count,m)).getBytes().length;

                byte[] byteArr;
                List<Byte> byteList = new LinkedList<>();

                for (; i < segmentSize && msgByteptr < msgBytes.length; i++) {
                    byteList.add(msgBytes[msgByteptr++]);
                }
                byteArr = Util.byteObjectToByteArray(byteList.toArray(new Byte[0]));
                String checksumStr = Util.checksumString(byteArr);
                SrSegment segment = new SrSegment(byteArr, count, checksumStr, segmentSize, timeoutms, addr, port);
                segmentList.add(segment);
                count++;

                if (msgByteptr >= msgBytes.length)
                    break;
            }
            return segmentList;
        }

        @Override
        public void run() {
            try {                   //SR SENDER
                while (true) {

                    while (nSeqNum < segmentList.size() && nSeqNum - base < WINDOW_SIZE) {
                        //send segment
                        sendSegment(segmentList.get(nSeqNum));
                    }
                    //wait for ack
                    DatagramPacket packet = new DatagramPacket(new byte[ackSegSize], ackSegSize);
                    toReceiver.receive(packet);
                    int ackNum = Util.checksumAndGetAckNum(packet.getData());//ack will have many null bytes, remove them

                    if( Math.random() < 0.05){
                        System.err.println("ACK LOSS " + ackNum);
                        continue;
                    }
                    //if ack not corrupt - ackNum is 'm' based
                    if (ackNum != -1 ) {
                        int actSeqNum = Util.getActualWindowSeqNum(ackNum, base, nSeqNum, m);
                        //if ack within frame
                        if( actSeqNum >=0){
                            //set segment has been acked else ignore acks
                            segmentList.get(actSeqNum).setAcked(true);
                            //if received ack is the smallest unacked segment
                            if (base == actSeqNum) {
                                int i = base;
                                while (i < nSeqNum) {
                                    if (!segmentList.get(i).isAcked())
                                        break;
                                    i++;
                                }
                                base = i;
                            }
                        }
                    }
                    //data sent
                    if (base >= segmentList.size())
                        break;
                }
                toReceiver.send(new DatagramPacket("end".getBytes(), 3,addr, port));
                toReceiver.close();
                System.out.println("\nDATA SENDING SUCCESS using SR!!");
                System.out.println("Sender disconnected...");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void sendSegment(SrSegment srSegment) throws IOException {
            DatagramPacket dgpacket = new DatagramPacket(
                    srSegment.renderFullSgmnt(),
                    Math.min(srSegment.renderFullSgmnt().length, segmentSize));
            dgpacket.setPort(port);
            dgpacket.setAddress(addr);
            System.out.println("SENDING PACKET " + srSegment);

            //TODO timer disable
            srSegment.startTimer();
            nSeqNum++;
            toReceiver.send(dgpacket);

        }
    }

    static class SrReceiver implements Runnable{

        private final int segmentSize;
        private final DatagramSocket toSender;
        private int rBase;
        private Map<Integer, Segment> segmentMap = new TreeMap<>();
        private InetAddress senderAddr = null;
        private int senderPort = -1;

        public SrReceiver(int segmentSize, int port) throws SocketException {
            this.segmentSize = segmentSize;
            this.rBase = 0;
            this.toSender = new DatagramSocket(port);
            //TODO if changing to actual numbering scheme change below val to segSize(removeTrailing 0 bytes anyways)
            // TODO, Util.getSeqNum, Util.getActualSeqNum
        }
        @Override
        public void run() {
            try {                   //SR RECEIVER
                while (true) {
                    DatagramPacket packet = new DatagramPacket(new byte[segmentSize], segmentSize);
                    toSender.receive(packet);

                    //if sender done sending
                    if (new String(packet.getData()).startsWith("end")) {
                        break;
                    }
                    setSenderAddrAndPort(packet);

                    if(Math.random() > PROBABILITY_PKT_LOSS){
                        if(Util.isSegmentNotCorrupt(packet)) {
                            Segment segment = Util.extractSegment(packet);
                            if (isWithinCurrentWindow(segment.getSeqNbr())) {
                                int actSeqNum = Util.getActualWindowSeqNum(segment.getSeqNbr(), rBase, rBase + WINDOW_SIZE - 1, m);
                                segmentMap.put(actSeqNum, segment);
                                int i = rBase;
                                while (i < rBase + WINDOW_SIZE - 1) {
                                    if (segmentMap.get(i) == null)
                                        break;
                                    i++;
                                }
                                rBase = i;
                                sendAck(segment.getSeqNbr());
                            } else if (isInsidePrevWindow(segment)) {
                                sendAck(segment.getSeqNbr());
                            }
                        }
                    }else{
                        System.err.println("PKT LOSS " + new String(packet.getData()));
                    }
                }
                toSender.close();
//                printMessage();
                System.err.println("\nReceiver disconnected...");

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void printMessage() {
            StringBuilder builder = new StringBuilder();
            for(Segment s: segmentMap.values()){
                builder.append(new String(s.getData()));
            }
            System.out.println("THE RECEIVED MSG is ..");
            System.err.println(builder.toString());
        }


        private boolean isWithinCurrentWindow(int seqNbr) {
            return Util.getActualWindowSeqNum(seqNbr, rBase, rBase + WINDOW_SIZE - 1, m) >= 0;
        }

        private boolean isInsidePrevWindow(Segment segment) {
            return true;
        }

        private void setSenderAddrAndPort(DatagramPacket packet) {
            //execs first time rcvd only
            if (senderAddr == null && senderPort == -1) {
                senderAddr = packet.getAddress();
                senderPort = packet.getPort();
            }
        }

        private void sendAck(int seqNum) throws IOException {
            String payload = Util.checksumString((""+seqNum).getBytes())
                    +" "
                    +Util.getSeqNum(seqNum, m);
            byte[] payBytes= Util.removeTrailing0Bytes(payload.getBytes());
            DatagramPacket packet = new DatagramPacket(payBytes, payBytes.length,
                    senderAddr, senderPort);
            //TODO loss logic
//            if(Math.random())
            System.out.println("ACK sent " + seqNum);
            toSender.send(packet);
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



















