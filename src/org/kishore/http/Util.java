package org.kishore.http;

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

    public static String checksumString(byte[] bytes) {
        String checksum = Long.toBinaryString(calculateChecksum(bytes));
        int num0s = 16 - checksum.length();
        String zeros = "";
        for(int i=0; i<num0s; i++){
            zeros += "0";
        }
        checksum = zeros + checksum;
        return checksum;
    }

    private static long calculateChecksum(byte[] buf) {
        int length = buf.length;
        int i = 0;

        long sum = 0;
        long data;

        // Handle all pairs
        while (length > 1) {
            // Corrected to include @Andy's edits and various comments on Stack Overflow
            data = (((buf[i] << 8) & 0xFF00) | ((buf[i + 1]) & 0xFF));
            sum += data;
            // 1's complement carry bit correction in 16-bits (detecting sign extension)
            if ((sum & 0xFFFF0000) > 0) {
                sum = sum & 0xFFFF;
                sum += 1;
            }

            i += 2;
            length -= 2;
        }

        // Handle remaining byte in odd length buffers
        if (length > 0) {
            // Corrected to include @Andy's edits and various comments on Stack Overflow
            sum += (buf[i] << 8 & 0xFF00);
            // 1's complement carry bit correction in 16-bits (detecting sign extension)
            if ((sum & 0xFFFF0000) > 0) {
                sum = sum & 0xFFFF;
                sum += 1;
            }
        }

        // Final 1's complement value correction to 16-bits
        sum = ~sum;
        sum = sum & 0xFFFF;
//        System.out.println("Sum "+sum);
        return sum;

    }

    public static void main(String[] args) {
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
}
