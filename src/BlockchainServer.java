import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;

public class BlockchainServer {
    public static void main(String[] args) {
    	
        if (args.length != 3) {
            return;
        }

        int localPort = 0;
        int remotePort = 0;
        String remoteHost = null;


        try {
            localPort = Integer.parseInt(args[0]);
            remoteHost = args[1];
            remotePort = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            return;
        }

        Blockchain blockchain = new Blockchain();
        HashMap<ServerInfo, Date> serverStatus = new HashMap<ServerInfo, Date>();
        serverStatus.put(new ServerInfo(remoteHost, remotePort), new Date());
        PeriodicCommitRunnable pcr = new PeriodicCommitRunnable(blockchain);
        Thread pct = new Thread(pcr);
        pct.start();

        // catch up?
        Block headBlock = null;
		Socket toServer1 = null;
		int bcSize = 0;
		try {

			// create socket
			toServer1 = new Socket();
			toServer1.connect(new InetSocketAddress(remoteHost, remotePort));
			PrintWriter printWriter = new PrintWriter(toServer1.getOutputStream(), true);

			// send catch up message
			printWriter.print("cu\n");
			printWriter.flush();

			ObjectOutputStream oos = new ObjectOutputStream(toServer1.getOutputStream());
			oos.flush();
			ObjectInputStream ois = new ObjectInputStream(toServer1.getInputStream());
			headBlock = (Block) ois.readObject();

			ois.close();
			oos.close();

		} catch (IllegalArgumentException e) {
		} catch (IOException e) {

			/*
            System.err.println("IOEXCEPTION");
            e.printStackTrace();
			 */
		} catch (ClassNotFoundException e) {
		} finally {
			try {
				if (toServer1 != null) {
					toServer1.close();
				}
			} catch (IOException e) {
			}
		}

		Block blockA = headBlock;
		Socket toServer2 = null;
		while (blockA != null) {
			try {
				bcSize++;
				byte[] prevHash = blockA.getPreviousHash();
				if (prevHash == null) {
					break;
				}

				// create socket
				toServer2 = new Socket();
				toServer2.connect(new InetSocketAddress(remoteHost, remotePort));
				PrintWriter printWriter = new PrintWriter(toServer2.getOutputStream(), true);

				printWriter.print("cu|" + Base64.getEncoder().encodeToString(prevHash) + "\n");
				printWriter.flush();

				ObjectOutputStream oos = new ObjectOutputStream(toServer2.getOutputStream());
				oos.flush();
				ObjectInputStream ois = new ObjectInputStream(toServer2.getInputStream());

				Block blockB = (Block) ois.readObject();
				if (blockB == null) {
					blockA = null;
					oos.close();
					ois.close();
					printWriter.close();
					break;
				}

				blockA.setPreviousBlock(blockB);
				blockA = blockB;
				ois.close();
				oos.close();
				printWriter.close();
			} catch (IllegalArgumentException e) {
			} catch (IOException e) {
				/*
                System.err.println("IOEXCEPTION");
                e.printStackTrace();
				 */
			} catch (ClassNotFoundException e) {
			} finally {
				try {
					if (toServer2 != null) {
						toServer2.close();
					}
				} catch (IOException e) {
				}
			}
		}

		blockchain.setHead(headBlock);
		blockchain.setLength(bcSize);

        // start up PeriodicPrinter
        new Thread(new PeriodicPrinterRunnable(serverStatus)).start();

        // start up PeriodicHeartBeat
        new Thread(new PeriodicHeartBeatRunnable(serverStatus, localPort)).start();

        // start up PeriodicLatestBlock
        new Thread(new PeriodicLatestBlockRunnable(serverStatus, localPort, blockchain)).start();

        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(localPort);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new BlockchainServerRunnable(clientSocket, blockchain, serverStatus)).start();
            }
        } catch (IllegalArgumentException e) {
        } catch (IOException e) {
        } finally {
            try {
                pcr.setRunning(false);
                pct.join();
                if (serverSocket != null)
                    serverSocket.close();
            } catch (IOException e) {
            } catch (InterruptedException e) {
            }
        }
    }
}

