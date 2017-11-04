package org.kishore.pp;

import java.net.DatagramPacket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Created by kishorekolluru on 10/21/17.
 */
public class Util {
    public synchronized static int getSeqNum(int actualNum, int m){
        return  actualNum % (int)Math.pow(2,m);
    }

    public static int getActualWindowSeqNum(int snum, int oldWindStart, int oldWindEnd, int m){
        for(int i=oldWindStart; i< oldWindEnd; i++){
            if(i % (int)Math.pow(2, m) == snum){
                return i;
            }
        }
        return -1;
    }

    public synchronized static String checksumString(byte[] bytes){
        try {
            return calculateChecksum(bytes);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }

    private synchronized static String calculateChecksum(byte[] buf) throws NoSuchAlgorithmException {
        //remove trailing 0bytes
        buf = removeTrailing0Bytes(buf);

        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(buf);
        byte[] byteData = md.digest();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < byteData.length; i++) {
            sb.append(Integer.toString((byteData[i] & 0xff) + 0x100, 16).substring(1));
        }
        return sb.toString();
    }

    public synchronized static byte[] removeTrailing0Bytes(byte[] buf) {
        int j = buf.length-1;
        while( j>=0 && buf[j]==0)
            j--;
        buf = Arrays.copyOf(buf, j+1);
        return buf;
    }

    public synchronized static boolean isSegmentNotCorrupt(DatagramPacket packet) {
        PipelinedProtocol.Segment segment = extractSegment(packet);
        String computedChecksum = checksumString(segment.getData());
        if(!computedChecksum.equals(segment.getChecksum()))
            System.err.println("Checksum Error Seg"+segment.getSeqNbr());
        return computedChecksum.equals(segment.getChecksum());
    }

    public synchronized static PipelinedProtocol.Segment extractSegment(DatagramPacket packet) {
        String payl = new String(packet.getData());
        List<String> strList = new ArrayList<>();
        int firstSpaceInd = payl.indexOf(' ');
        int secSpaceInd = payl.indexOf(' ', firstSpaceInd + 1);
        //checksum
        String chksum = payl.substring(0, firstSpaceInd);
        //seqNum
        String seqNum = payl.substring(firstSpaceInd + 1, secSpaceInd);
        //payload
        String payload = payl.substring(secSpaceInd + 1);

        PipelinedProtocol.Segment segment = new PipelinedProtocol.Segment(
                removeTrailing0Bytes(payload.getBytes()),
                Integer.parseInt(seqNum),
                chksum);
        return segment;
    }

    public static byte[] corruptByte(byte[] bytes){
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }

    public synchronized static String renderSeqNbrForTransport(int act, int m) {
        //convert the sbase actual number to seq num for transport and prepend with leading 0's if applicable (for exampl %03d)
        String format = "%0"+ String.valueOf((int)Math.pow(2,m)).length() +"d";
       return String.format(format, act);
    }

    public static byte[] byteObjectToByteArray(Byte[] byteObjects) {
        int j=0;
        byte[] bytes = new byte[byteObjects.length];
        // Unboxing byte values. (Byte[] to byte[])
        for(Byte b: byteObjects)
            bytes[j++] = b;
        return bytes;
    }

    public static List<Byte[]> initializeDataArray(String msg, int payloadSize) {
        List<Byte[]> dataList = new ArrayList<>();
        byte[] msgBytes = msg.getBytes();
        List<Byte> byteSeg = new ArrayList<>();
        int msgPartSize = payloadSize;
        System.out.println("Message part size = "+msgPartSize);

        //split the msg bytes into msgpartsize segments
        for (int i = 0; i < msgBytes.length; i++) {
            byteSeg.add(msgBytes[i]);
            if (byteSeg.size() == msgPartSize || i == msgBytes.length - 1){
                dataList.add(byteSeg.toArray(new Byte[0]));
                byteSeg.clear();
            }
        }
        return dataList;
    }

    /*public static void main(String[] args) throws NoSuchAlgorithmException {
        byte[] bytes= "asdfjkei7893urjfasdfsdfwef984iks".getBytes();
        byte[] bytes2 = new byte[37];
        int i=0;
        for(byte b: bytes){
            bytes2[i]= (b);
            i++;
        }
        System.out.println("Print "+ new String(bytes));
        System.out.println("Print "+ new String(corruptByte(bytes)));

    }*/

    public synchronized static int checksumAndGetAckNum(byte[] data) {
        String ack = new String(data);
//        System.out.println("Checking ACK " + ack);
        int ackNum = -1;

        byte[] databytes = ack.split(" ")[1].getBytes();
        String chkSum = ack.split(" ")[0];
        String computedChksum = Util.checksumString(databytes);
        if (computedChksum.equals(chkSum)) {
            ackNum = Integer.parseInt(new String(removeTrailing0Bytes(databytes)));
        }
        return ackNum;
    }
}
