package org.kishore.http;

import java.net.DatagramPacket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by kishorekolluru on 10/21/17.
 */
public class Util {
    public static int getSeqNum(int actualNum, int m){
        return  actualNum % (int)Math.pow(2,m);
    }

    public static int getActualWindowSeqNum(int snum, int oldWindStart, int oldWindEnd, int m){
        for(int i=oldWindStart; i<= oldWindEnd; i++){
            if(i % (int)Math.pow(2, m) == snum){
                return i;
            }
        }
        return -1;
    }

    public static String checksumString(byte[] bytes){
        try {
            return calculateChecksum(bytes);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String calculateChecksum(byte[] buf) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(buf);
        byte[] byteData = md.digest();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < byteData.length; i++) {
            sb.append(Integer.toString((byteData[i] & 0xff) + 0x100, 16).substring(1));
        }
        return sb.toString();
    }

    public static boolean isNotCorrupt(DatagramPacket packet) {
        List<String> paylArr = extractMsg(packet);
        String computedChecksum = Util.checksumString(paylArr.get(2).getBytes());
        return computedChecksum.equals(paylArr.get(0));
    }

    public static List<String> extractMsg(DatagramPacket packet) {
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

    public static void main(String[] args) throws NoSuchAlgorithmException {
        System.out.println(checksumString("hi there!".getBytes()));
        System.out.println(renderSeqNbrForTransport(4, 8));
    }
    public static byte corruptByte(byte b){
        //shift left random number of bits
        return (byte)(b << (int)(Math.random()*8));
//        return (byte)(b | 1 << (int)(Math.random()*8));
    }

    public static String renderSeqNbrForTransport(int sBase, int m) {
        //convert the sbase actual number to seq num for transport and prepend with leading 0's if applicable (for exampl %03d)
//        System.out.println(String.valueOf((int) Math.pow(2, m)).length());
        String format = "%0"+ String.valueOf((int) Math.pow(2, m)).length() +"d";
       return String.format(format, getSeqNum(sBase, m));
    }

    public static byte[] byteObjectToByteArray(Byte[] byteObjects) {
        int j=0;
        byte[] bytes = new byte[byteObjects.length];
        // Unboxing byte values. (Byte[] to byte[])
        for(Byte b: byteObjects)
            bytes[j++] = b.byteValue();
        return bytes;
    }

    public static List<Byte[]> initializeDataArray(String msg, int payloadSize) {
        List<Byte[]> dataList = new ArrayList<>();
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
        return dataList;
    }
}
