package org.kishore.http;

/**
 * Created by kishorekolluru on 10/21/17.
 */
public class Sample1 {

    public static void main(String[] args) {
        byte[] asd = new byte[4];
        asd[0]=8;
        if(asd[1]==0){
            System.out.println("True");
        }
        System.out.println(Integer.toBinaryString(asd[0]));
    }
}
