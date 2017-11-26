package org.kishore.dv;

import java.io.*;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
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
    public static final String host = "localhost";
    public static final int MULTICAST_LISTENING_PORT = 6001;
    private static final int RECEIVE_PKT_LENGTH = 4000;

    public static void main(String[] args) {

    }

    static class Router implements Runnable{

        private String routerName;
        private List<Neighbor> neighborList = new ArrayList<>();
        private List<Neighbor> discoveredNeighbors = new ArrayList<>();
        private Socket socket;
        private MulticastSocket listenerPort;
        private Timer timer;

        public Router(File inpFile) throws IOException {
            try(BufferedReader br = new BufferedReader(new FileReader(inpFile))){
                String line = null;
                while((line = br.readLine()) != null){
                    if(this.routerName==null)this.routerName = line.trim();
                    neighborList.add(Helper.createNewNeibrFromInput(line, this));
                }
                socket = bindSocket();
                listenerPort = new MulticastSocket(MULTICAST_LISTENING_PORT);
                timer = new Timer("timer-"+routerName, true);
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        try{
                            reloadInputs();
                            advertiseCosts();
                        }catch(Exception e){
                            System.err.println("Something went wrong...");
                            e.printStackTrace();
//                            System.exit(1);
                        }
                    }
                }, ADVERT_TIMEOUT_MS);
            }
        }

        private Socket bindSocket() {
            Socket socket = null;
            for(int i=PORT_NUM_START; i< PORT_NUM_START+PORT_NUM_RANGE; i++){
                try {
                    socket = new Socket(host,i);
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

        private void reloadInputs() {
            //TODO clear neighborList and reload file
        }

        private void advertiseCosts() {
            //TODO unicast to discovered neightbors , multicast across the range of port numbers to supposedly undiscovered/dead neighbors

        }

        @Override
        public void run() {
            try {
                byte[] buf = new byte[RECEIVE_PKT_LENGTH];
                listenerPort.joinGroup(InetAddress.getByName(host));
                while (true) {
                    DatagramPacket msgPacket = new DatagramPacket(buf, buf.length);
                    listenerPort.receive(msgPacket);
                    processMessage(msgPacket);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void processMessage(DatagramPacket msgPacket) {

        }

        static class Neighbor implements Serializable{
            private final String name;
            private int port;
            private double cost;
            private String nextHop;

            public Neighbor(String name, double cost) {
                this.name = name;
                this.cost = cost;
            }

            public void setCost(double cost){
                this.cost = cost;
            }
            public void setPort(int port){
                this.port =port;
            }

            public int getPort() {
                return port;
            }

            public double getCost() {
                return cost;
            }

            public String getName() {
                return name;
            }

            public String getNextHop() {
                return nextHop;
            }

            public void setNextHop(String nextHop) {
                this.nextHop = nextHop;
            }
        }

        class Advertisement implements Serializable{
            private final List<Neighbor> neighbors;

            public Advertisement(List<Neighbor> neighbors){
                this.neighbors = neighbors;
            }

            @Override
            public String toString() {
                StringBuilder sb = new StringBuilder();
                for(Neighbor n : neighbors){
                    sb.append("Shortest path from "+routerName+"-"+n.getName()+": The next hop is "+ n.getNextHop() +" and the cost is "+n.getCost());
                    sb.append("\n");
                }
                return sb.toString();
            }
        }
    }


}
