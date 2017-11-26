package org.kishore.http;

import javafx.util.Pair;

/**
 * Created by kishorekolluru on 10/21/17.
 */
public class Sample1 {

    public static void main(String[] args) {

    }

    static Pair<Integer, Integer> findRunsAndStraights(String str){
        int runs = 0;
        int st = 0;

        char c = str.charAt(0);
        int cnt = 0;
        int stcnt = 0;
        for(int i=1; i< str.length();i++){
            if(c == str.charAt(i)){
                cnt++;
                stcnt=0;
            }
            else if ( c==c+1) {
                stcnt++;
                cnt=0;
            }
            if(cnt ==3){
                runs++;
                cnt=0;
            }
        }
        return new Pair<>(runs, st);
    }
}
