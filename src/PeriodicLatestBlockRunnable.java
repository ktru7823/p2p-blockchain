import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

public class PeriodicLatestBlockRunnable implements Runnable {

    private HashMap<ServerInfo, Date> serverStatus;
    private int localPort;
    private Blockchain bc;

    public PeriodicLatestBlockRunnable(HashMap<ServerInfo, Date> serverStatus, int localPort, Blockchain bc) {
        this.serverStatus = serverStatus;
        this.localPort = localPort;
        this.bc = bc;
    }

    @Override
    public void run() {
        while(true) {
            // broadcast message to 5 peers
            ArrayList<Thread> threadArrayList = new ArrayList<>();
            
            int count = 0;
            int bcLength = bc.getLength();
            Block head = bc.getHead();
            String hashEncode = null;
            if (head != null) {
            	hashEncode = Base64.getEncoder().encodeToString(bc.getHead().calculateHash());
            } else {
            	try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                }
            	continue;
            }
            
            List<ServerInfo> siList = new ArrayList<>();
            for (ServerInfo si : serverStatus.keySet()) {
            	siList.add(si);
            }
            
            Collections.shuffle(siList);
            
            for (ServerInfo si : siList) {
            	Thread thread = new Thread(new HeartBeatClientRunnable(si.getHost(), si.getPort(), 
                		"lb|" + localPort + "|" + bcLength + "|" + hashEncode));
                threadArrayList.add(thread);
                thread.start();
                
                count++;
                if (count >= 5) {
                	break;
                }
            }

            for (Thread thread : threadArrayList) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                }
            }

            // sleep
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
            }
        }
    }
}
