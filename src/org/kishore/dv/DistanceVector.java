package org.kishore.dv;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Serializable;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by kishorekolluru on 11/12/17.
 */
public class DistanceVector {

    public static final int PORT_NUM_START = 5000;
    public static final int PORT_NUM_RANGE = 20;
    public static final int ADVERT_TIMEOUT_MS = 15000;
    public static final String host = "localhost";
    private static final int RECEIVE_PKT_LENGTH = 4000;

    public static void main(String[] args) {
        try {
            Runnable runnable = new Router(args[0].trim(), args[1].trim());
            Thread t = new Thread(runnable);
            t.start();
            t.join();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    static class Router implements Runnable{

        private String routerName;
        //synchronized since both timer and run thread may update at same time
        private Map< String, Neighbor> distanceMap = Collections.synchronizedMap(new HashMap<>());
        private Map<String, Double> originalNeighbors = new HashMap<>();
        private List<String> neighbors = new ArrayList<>();
        private DatagramSocket socket;
        private DatagramSocket listenerSocket;
        private Timer timer;
        private String inputFilename;

        public static final String USER_DIR = System.getProperty("user.dir");

        public Router(String routerName, String filename) throws IOException {
            this.inputFilename = filename;

            try(BufferedReader br = new BufferedReader(new FileReader(Paths.get(USER_DIR,filename).toFile()))){
                distanceMap.putAll(loadInput(br));
                distanceMap.values().forEach( x-> {
                    originalNeighbors.put(x.getName(), x.getCost());
                });
                //immediate neighbors
                neighbors.addAll(distanceMap.keySet());
                socket = bindSocket();
                System.out.println("Socket closed ?"+socket.getLocalPort());
                this.routerName = routerName;
//                this.routerName = Character.toString((char)(ASCII_A + (PORT_NUM_START % (PORT_NUM_START-1)-2)));

                listenerSocket = new DatagramSocket(socket.getLocalPort()+1);
            }
        }

        private Map<String, Neighbor> loadInput(BufferedReader br) throws IOException {
            String line = null;
            Map<String, Neighbor> neighborMap = new HashMap<>();
            while((line = br.readLine()) != null){
                if(this.routerName==null){
                    this.routerName = line.trim();
                    continue;
                }if(line.split("\\s").length<2)continue;
//                neighbors.add(Helper.createNewNeibrFromInput(line, this));
                Neighbor nbr = Helper.createNewNeibrFromInput(line, this);
                neighborMap.put(nbr.getName(), nbr);
            }
            return neighborMap;
        }

        private DatagramSocket bindSocket() {
            DatagramSocket socket = null;
            for(int i=PORT_NUM_START; i< PORT_NUM_START+PORT_NUM_RANGE; i=i+2){
                try {
                    socket = new DatagramSocket(i, InetAddress.getByName(host));
                    System.out.println("Bound socket "+i+" to Router");
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

        private void reloadInputs() throws IOException {
            System.err.println("\nReloading input costs...");
            final Map<String, Neighbor> reloadedMap = new HashMap<>();
            boolean[] flag = new boolean[1];
            try (BufferedReader br = new BufferedReader(new FileReader(Paths.get(USER_DIR,inputFilename).toFile()))) {
                reloadedMap.putAll(loadInput(br));
                this.originalNeighbors.keySet().forEach(k -> {
                    if (reloadedMap.containsKey(k) && originalNeighbors.get(k) != reloadedMap.get(k).getCost()) {//
                        flag[0] = true;
                        originalNeighbors.put(k, reloadedMap.get(k).getCost());//update so that we dont detect this change as new the next time reloading
                        distanceMap.get(k).setCost(reloadedMap.get(k).getCost());//set "updated" cose into distanceMap
                    }
                });
            }
            System.err.println("Input costs CHANGED? "+ flag[0]);
        }

        private void advertiseCosts() throws IOException {
            System.err.println("In advertize cost method...");
            List<String> sent = new ArrayList<>();

            for(Map.Entry<String, Neighbor> entry: distanceMap.entrySet()){
                Neighbor nebr = distanceMap.get(entry.getKey());
                if(neighbors.contains(nebr.getName()))//only send to immediate neighbors
                    sent.add(sendToNeighbor(entry.getValue()));
            }

            System.err.println("Advertised all...");

        }

        private String sendToNeighbor(Neighbor nebr) throws IOException {
            byte[] buf = new byte[RECEIVE_PKT_LENGTH];
            //TODO poisson reverse using the nebr's name
            List<Neighbor> advertisableNeighbors = new ArrayList<>(distanceMap.values());
            Advertisement adv = new Advertisement(routerName, nebr.getName(), advertisableNeighbors);
            buf = SerializationUtil.serialize(adv);

            DatagramPacket packet = null;
            if(nebr.getPort()!=-1) {
                packet = new DatagramPacket(buf, buf.length, InetAddress.getByName(host), nebr.getPort());
                socket.send(packet);
                System.err.println("Unicast to neighbr "+nebr.getName()+" at "+nebr.getPort());
            }else {//if neighbor still not sent antything to us
                System.err.print("Multicasting to ports for Neighbor "+nebr.getName()+"...");
                multicastMessage(buf);
            }
            System.err.println("\nAdvertized "+routerName+"-"+nebr.getName());//+": The next hop is "+ nebr.getNextHop() +" and the cost is "+nebr.getCost());
            System.err.println(adv);
            return nebr.getName();
        }

        private void multicastMessage(byte[] bytes) throws IOException {
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
            List<Integer> knownPorts = distanceMap.values().stream().map(x-> x.getPort()).collect(Collectors.toList());
            for (int i = PORT_NUM_START+1; i < PORT_NUM_START + PORT_NUM_RANGE /*&& !knownPorts.contains(i)*/; i = i + 2) {
                packet.setAddress(InetAddress.getByName(host));
                System.err.print(i+" ");
                packet.setPort(i);
                socket.send(packet);
            }
            System.out.println();
        }

        @Override
        public void run() {
            try {
                startTimer();
                byte[] buf = new byte[RECEIVE_PKT_LENGTH];
                System.out.println("Router listening for messages on port "+listenerSocket.getPort());
                while (true) {
                    DatagramPacket msgPacket = new DatagramPacket(buf, buf.length);
                    listenerSocket.receive(msgPacket);
                    processMessage(msgPacket);
                }
            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

        private void startTimer() {
            timer = new Timer("timer-"+routerName, true);
            timer.scheduleAtFixedRate(new TimerTask() {
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
            }, ADVERT_TIMEOUT_MS, ADVERT_TIMEOUT_MS);
        }

        private void processMessage(DatagramPacket msgPacket) throws IOException, ClassNotFoundException {
            //run DV and set the costs and nextHop in discoveredNeighbors
            Advertisement advert = (Advertisement) SerializationUtil.deserialize(msgPacket.getData());
            System.out.println("Received advert from "+advert.getSenderRouterName()+"...\n" + advert);
            if (!advert.getSenderRouterName().equals(this.routerName)) {//not same as the router receiving this
                Neighbor nbr = distanceMap.get(advert.getSenderRouterName());
                if (nbr.getPort() <= 0) {//when first time discovering neighbor note down port number for unicast
                    nbr.setPort(msgPacket.getPort()+1);
                }
                runDistanceVector(advert);
            }
        }

        private void runDistanceVector(Advertisement advert) {
//            for(Neighbor neighbor: advert.getNeighbors()){
//                if(!neighbor.getName().equals(routerName) && distanceMap.containsKey(neighbor.getName())){
            synchronized (distanceMap) {
                //update each known destination y from x(this router)
                String senderName = advert.getSenderRouterName();
                double costToSenderNeigh = distanceMap.get(senderName).getCost();//c(x,v)

                for (Map.Entry<String, Neighbor> dest : distanceMap.entrySet()) {
                    String destName = dest.getKey();        //destination y
                    if (neighbors.contains(senderName)) {     //only if  v in c(x,v) is a neighbor can we use the DV formula

                        Neighbor sender = distanceMap.get(advert.getSenderRouterName());
                        double oldCost = dest.getValue().getCost();
                        double newCost = costToSenderNeigh + getCostToDestFromNbrAdvert(destName, advert);

                        if (oldCost > newCost) {
                            dest.getValue().setCost(newCost);//update new cost
                            dest.getValue().setNextHop(senderName);//update next hop
                        }
                    }

                }
                //add any new nodes to known list
                for(Neighbor neighbor : advert.getNeighbors()){
                    if(!distanceMap.containsKey(neighbor.getName()) && !routerName.equals(neighbor.getName())){
                        neighbor.setNextHop(senderName);
                        neighbor.setCost(costToSenderNeigh + neighbor.getCost());
                        distanceMap.put(neighbor.getName(), neighbor);
                        System.out.println("ADDED STRANGER "+ neighbor.getName()+" IN "+routerName);
                    }
                }
            }
        }

        private double getCostToDestFromNbrAdvert(String dest, Advertisement neighborAdvert) {
            if(neighborAdvert.getSenderRouterName().equals(dest)) return 0.0;//if same dest and neighbor node (cost from neighbor to itself is 0)

            final double[] cost = new double[1];
            cost[0] = 0.0;
            neighborAdvert.getNeighbors().stream().filter( neighbor -> neighbor.getName().equals(dest)).findFirst().ifPresent( neighbor1 -> {
                cost[0] =  neighbor1.getCost();
//                System.out.println("FROM NBR TO "+ dest + " COST "+cost[0]);
            });
            return cost[0];
        }

        static class Neighbor implements Serializable{
            private final String name;
            private int port;
            private double cost;
            private String nextHop;

            public Neighbor(String name, double cost) {
                this.name = name;
                this.cost = cost;
                this.port = -1;
                this.nextHop = name;
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

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Neighbor neighbor = (Neighbor) o;
                return port == neighbor.port &&
                        Double.compare(neighbor.cost, cost) == 0 &&
                        Objects.equals(name, neighbor.name) &&
                        Objects.equals(nextHop, neighbor.nextHop);
            }

            @Override
            public int hashCode() {
                return Objects.hash(name, port, cost, nextHop);
            }
        }

        static class Advertisement implements Serializable{
            private final List<Neighbor> neighbors;
            private final String receiverRouterName;//required so that the receiver will know if its intended for it(since we need to use poisson reverse)
            private final String senderRouterName;

            public Advertisement(String senderRouterName, String receiverRouterName, List<Neighbor> neighbors){
                this.senderRouterName = senderRouterName;
                this.receiverRouterName = receiverRouterName;
                this.neighbors = neighbors;
            }

            public List<Neighbor> getNeighbors() {
                return neighbors;
            }

            public String getReceiverRouterName() {
                return receiverRouterName;
            }

            @Override
            public String toString() {
                StringBuilder sb = new StringBuilder();
                for(Neighbor n : neighbors){
                    sb.append("Shortest path from "+senderRouterName+"-"+n.getName()+": The next hop is "+ n.getNextHop() +" and the cost is "+n.getCost());
                    sb.append("\n");
                }
                return sb.toString();
            }

            public String getSenderRouterName() {
                return senderRouterName;
            }
        }
    }


}
