package org.kishore.dv;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by kishorekolluru on 11/12/17.
 */
public class DistanceVector {
    //𝑑𝑥(𝑦) = _𝑚𝑖𝑛_𝑣_⁡{𝑐(𝑥,𝑣) + _𝑑_𝑣_(𝑦) }

    public static final int PORT_NUM_START = 5001;
    public static final int PORT_NUM_RANGE = 20;
    public static final int ADVERT_TIMEOUT_MS = 15000;
    public static void main(String[] args) {

    }

    static class Router{

        private String routerName;
        private List<Neighbor> neighborList = new ArrayList<>();
        private Socket socket;
        private Timer timer;
        public Router(File inpFile) throws IOException {
            try(BufferedReader br = new BufferedReader(new FileReader(inpFile))){
                String line = null;
                while((line = br.readLine()) != null){
                    if(this.routerName==null)this.routerName = line.trim();
                    neighborList.add(Helper.createNewNeibrFromInput(line, this));
                }
                socket = bindSocket();

                timer = new Timer("timer-"+routerName, true);
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        advertiseCosts();
                    }
                }, ADVERT_TIMEOUT_MS);
            }
        }

        private void advertiseCosts() {

        }

        private Socket bindSocket() {
            Socket socket = null;
            for(int i=PORT_NUM_START; i< PORT_NUM_START+PORT_NUM_RANGE; i++){
                try {
                    socket = new Socket("localhost",i);
                    return socket;
                } catch (IOException e) {
                    System.err.println("Trying another port number to bind...");
                    if(i== PORT_NUM_START+PORT_NUM_RANGE) {
                        System.err.println("The port range is exhausted. Cannot add more routers!");
                        System.exit(1);
                    }
                }
            }
            return socket;
        }

        class Neighbor{
            private final String name;
            private int port;
            private double cost;

            public Neighbor(String name, double cost) {
                this.name = name;
                this.cost = cost;
            }

            public void updateCost(double cost){
                this.cost = cost;
            }
            public void setPort(int port){
                this.port =port;
            }
        }
    }


}
