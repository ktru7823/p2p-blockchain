import java.io.*;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;

public class BlockchainServerRunnable implements Runnable {

	private Socket clientSocket;
	private Blockchain blockchain;
	private HashMap<ServerInfo, Date> serverStatus;

	public BlockchainServerRunnable(Socket clientSocket, Blockchain blockchain,
			HashMap<ServerInfo, Date> serverStatus) {
		this.clientSocket = clientSocket;
		this.blockchain = blockchain;
		this.serverStatus = serverStatus;
	}

	public void run() {
		try {
			serverHandler(clientSocket.getInputStream(), clientSocket.getOutputStream());
			clientSocket.close();
		} catch (IOException e) {
		}
	}

	public void serverHandler(InputStream clientInputStream, OutputStream clientOutputStream) {

		BufferedReader inputReader = new BufferedReader(new InputStreamReader(clientInputStream));
		PrintWriter outWriter = new PrintWriter(clientOutputStream, true);

		String localIP = (((InetSocketAddress) clientSocket.getLocalSocketAddress()).getAddress()).toString()
				.replace("/", "");
		String remoteIP = (((InetSocketAddress) clientSocket.getRemoteSocketAddress()).getAddress()).toString()
				.replace("/", "");

		int localPort = clientSocket.getLocalPort();

		try {
			while (true) {
				String inputLine = inputReader.readLine();
				if (inputLine == null) {
					break;
				}

				String[] tokens = inputLine.split("\\|");

				switch (tokens[0]) {
				case "tx":
					if (blockchain.addTransaction(inputLine))
						outWriter.print("Accepted\n\n");
					else
						outWriter.print("Rejected\n\n");
					outWriter.flush();
					break;
				case "pb":
					outWriter.print(blockchain.toString() + "\n");
					outWriter.flush();
					break;
				case "cc":
					return;
				case "hb": {
					int remotePort = Integer.parseInt(tokens[1]);

					serverStatus.put(new ServerInfo(remoteIP, remotePort), new Date());
					if (tokens[2].equals("0")) {
						ArrayList<Thread> threadArrayList = new ArrayList<>();
						for (ServerInfo si : serverStatus.keySet()) {
							String thisIP = si.getHost();
							int thisPort = si.getPort();

							if ((thisIP.equals(remoteIP) && thisPort == remotePort)
									|| thisIP.equals(localIP) && thisPort == localPort) {
								continue;
							}
							Thread thread = new Thread(new HeartBeatClientRunnable(thisIP, thisPort,
									"si|" + localPort + "|" + remoteIP + "|" + remotePort));
							threadArrayList.add(thread);
							thread.start();
						}
						for (Thread thread : threadArrayList) {
							thread.join();
						}
					}
					break;
				}
				case "si": {
					int sPortNum = Integer.parseInt(tokens[1]);
					String pAddress = tokens[2];
					int pPortNum = Integer.parseInt(tokens[3]);

					ServerInfo pServer = new ServerInfo(pAddress, pPortNum);
					if (!serverStatus.keySet().contains(pServer)) {
						serverStatus.put(pServer, new Date());

						// relay
						ArrayList<Thread> threadArrayList = new ArrayList<>();
						for (ServerInfo si : serverStatus.keySet()) {
							String thisIP = si.getHost();
							int thisPort = si.getPort();
							if ((thisIP.equals(pAddress) && thisPort == pPortNum)
									|| (thisIP.equals(localIP) && thisPort == localPort)
									|| (thisIP.equals(remoteIP) && thisPort == sPortNum)) {
								continue;
							}
							Thread thread = new Thread(new HeartBeatClientRunnable(thisIP, thisPort,
									"si|" + localPort + "|" + pAddress + "|" + pPortNum));
							threadArrayList.add(thread);
							thread.start();
						}
						for (Thread thread : threadArrayList) {
							thread.join();
						}
					}
					break;
				}
				case "lb": {
					int remotePort = Integer.parseInt(tokens[1]);
					int remoteBlockchainSize = Integer.parseInt(tokens[2]);
					byte[] remoteHash = Base64.getDecoder().decode(tokens[3]);

					if (remoteBlockchainSize == 0) {
						return;
					}
					int localBlockchainSize = blockchain.getLength();

					boolean remoteSmallerHash = false;

					if (localBlockchainSize == remoteBlockchainSize) {
						byte[] localHash = blockchain.getHead().calculateHash();

						for (int i = 0; i < 32; i++) {
							if (localHash[i] == remoteHash[i]) {
								continue;
							}

							if (remoteHash[i] > localHash[i]) {
								break;
							}

							if (localHash[i] > remoteHash[i] && i == 31) {
								remoteSmallerHash = true;
							}
						}
					}

					if (localBlockchainSize < remoteBlockchainSize || remoteSmallerHash) {
						catchup(remoteIP, remotePort, remoteBlockchainSize);
					}

					return;
				}

				case "cu": {

					try {
						ObjectOutputStream oos = new ObjectOutputStream(clientOutputStream);
						oos.flush();

						try {
							if (tokens.length == 1) {
								Block headBlock = blockchain.getHead();
								oos.writeObject(headBlock);
							} else if (tokens.length == 2) {
								Block currentBlock = blockchain.getHead();

								byte[] requestedHash = Base64.getDecoder().decode(tokens[1]);

								for (int i = 0; i < blockchain.getLength(); i++) {
									if (Arrays.equals(currentBlock.calculateHash(), requestedHash)) {
										break;
									}
									currentBlock = currentBlock.getPreviousBlock();
								}

								if (Arrays.equals(currentBlock.calculateHash(), requestedHash)) {
									oos.writeObject(currentBlock);
								}
							}
						} catch (NullPointerException e) {
							oos.writeObject(null);
						}

						oos.flush();
						oos.close();
					} catch (IllegalArgumentException e) {
					} catch (IOException e) {
						/*
						System.err.println("cu: IOEXCEPTION");
						e.printStackTrace();
						*/
					}
					return;
				}
				default:
					outWriter.print("Error\n\n");
					outWriter.flush();
				}
			}
		} catch (IOException e) {
		} catch (InterruptedException e) {
		}
	}

	synchronized private void catchup(String remoteIP, int remotePort, int remoteBlockchainSize) {

		if (remoteBlockchainSize == 0) {
			return;
		}

		Block headBlock = null;
		Socket toServer1 = null;
		int blocksAdded = 0;
		try {
			// create socket
			toServer1 = new Socket();
			toServer1.connect(new InetSocketAddress(remoteIP, remotePort));
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
			printWriter.close();
		} catch (IllegalArgumentException e) {
		} catch (IOException e) {
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
		
		if (blockchain.getHead() == null) {
			blockchain.setHead(headBlock);
		}
		blocksAdded++;

		Socket toServer2 = null;
		for (int i = 1; i < remoteBlockchainSize; i++) {
			if (blockA == null) {
				break;
			}
			
			try {

				byte[] prevHash = blockA.getPreviousHash();
				if (prevHash == null) {
					this.blockchain.setLength(0);
					break;
				}
				if (Arrays.equals(blockchain.getHead().calculateHash(), prevHash)) {
					blockA.setPreviousBlock(blockchain.getHead());
					blockA = null;
					break;
				}

				// create socket
				toServer2 = new Socket();
				toServer2.connect(new InetSocketAddress(remoteIP, remotePort));
				PrintWriter printWriter = new PrintWriter(toServer2.getOutputStream(), true);

				printWriter.print("cu|" + Base64.getEncoder().encodeToString(prevHash) + "\n");
				printWriter.flush();

				ObjectOutputStream oos = new ObjectOutputStream(toServer2.getOutputStream());
				oos.flush();
				ObjectInputStream ois = new ObjectInputStream(toServer2.getInputStream());

				Block blockB = (Block) ois.readObject();
				if (blockB == null) {
					blockA = null;
					ois.close();
					oos.close();
					printWriter.close();
					break;
				}
				blockA.setPreviousBlock(blockB);
				blockA = blockB;
				blocksAdded++;
				ois.close();
				oos.close();
				printWriter.close();
			} catch (IllegalArgumentException e) {
			} catch (IOException e) {
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
		int newLength = blockchain.getLength() + blocksAdded;
		blockchain.setLength(newLength);

	}
}
