import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

public class HeartBeatClientRunnable implements Runnable{

    private String ip;
    private int port;
    private String message;

    public HeartBeatClientRunnable(String ip, int port, String message) {
        this.ip = ip;
        this.port = port;
        this.message = message;
    }

    @Override
    public void run() {
        try {
            // create socket with a timeout of 2 seconds
            Socket toServer = new Socket();
            toServer.connect(new InetSocketAddress(ip, port), 2000);
            PrintWriter printWriter = new PrintWriter(toServer.getOutputStream(), true);

            // send the message forward
            printWriter.print(message + "\n");
            printWriter.flush();

            // close printWriter and socket
            printWriter.close();
            toServer.close();
        } catch (IOException e) {
        }
    }
}
