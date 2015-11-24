import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;

public class RXPClient {

    private static final int PACKET_SIZE = 512;
    private static final int DATA_SIZE = 496;
    private static final int MAX_SEQ_NUM = 65536; //2^16, or max of 2 bytes

    private ClientState state;

    private int clientPort;
    private int serverPort;
    private int serverRXPPort;
    private InetAddress clientIpAddress;
    private InetAddress serverIpAddress;
    private DatagramSocket clientSocket;

    //private byte[] window = new byte[MAX_SEQ_NUM]; //TODO window sliding
    private int seqNum, ackNum, windowSize;
    private byte[] fileData;
    private ArrayList<byte[]> bytesReceived;

    public RXPClient() {
        this.clientPort = 3251;
        this.serverPort = 3252;
        try {
            this.clientIpAddress = InetAddress.getLocalHost();
            this.serverIpAddress = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        state = ClientState.CLOSED;
    }

    public RXPClient(int clientPort, String serverIpAddress, int serverPort) {
        this.clientPort = clientPort;
        this.serverPort = serverPort;
        try {
//            this.clientIpAddress = InetAddress.getLocalHost();
            this.clientIpAddress = InetAddress.getByName("127.0.0.1");
            this.serverIpAddress = InetAddress.getByName(serverIpAddress);
        } catch (UnknownHostException e) {
            e.printStackTrace();
            System.out.println("Wasn't able to bind IP addresses");
        }
        seqNum = 0;
        ackNum = 0;
        state = ClientState.CLOSED;
    }

    /**
     * performs handshake
     *
     * @throws IOException
     */
    public boolean setupRXP() {

        try {
            clientSocket = new DatagramSocket(clientPort, clientIpAddress);
            System.out.println("Set up clientSocket");
        } catch (SocketException e) {
            System.out.println("Couldn't setup clientSocket");
            e.printStackTrace();
        }

        byte[] receiveMessage = new byte[PACKET_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveMessage, receiveMessage.length);

        // Setup Initializing Header

        RXPHeader synHeader = RXPHelpers.initHeader(clientPort, serverPort, seqNum, ackNum);
        synHeader.setFlags(false, true, false, false, false, false); //setting SYN flag on
        byte[] data = new byte[DATA_SIZE];
        synHeader.setChecksum(data);
        // Make the packet
        DatagramPacket setupPacket = RXPHelpers.preparePacket(serverIpAddress, serverPort, synHeader, data);


        // Sending SYN packet and receiving SYN ACK with challenge string

        int timeout = 5000;
        try {
            clientSocket.setSoTimeout(timeout);
        } catch (SocketException e1) {
            e1.printStackTrace();
        }
        System.out.printf("Timer of %d setup.\n", timeout);

        int tries = 0;
        state = ClientState.SYN_SENT;
        while (state != ClientState.ESTABLISHED) {
            try {
//                System.out.println("ClientPort: " + clientSocket.getLocalPort()
//                        + "ClientIP: " + clientSocket.getLocalAddress()
//                        + " ClientIP2: " + clientSocket.getInetAddress()
//                        + clientIpAddress);
//                System.out.println("Server Port: " + clientSocket.getPort()
//                        + serverPort);
//                System.out.println(setupPacket.getAddress() + ":" + setupPacket.getPort());

                clientSocket.send(setupPacket);
                clientSocket.receive(receivePacket);
                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);
                if (!RXPHelpers.isValidPacketHeader(receivePacket)) //|| !RXPHelpers.isValidPort(receivePacket, clientPort, serverPort))    //Corrupted
                {
                    System.out.println("CORRUPTED");
                    continue;
                }


                // Assuming valid and SYN, ACK
                if (receiveHeader.isACK() && receiveHeader.isSYN() && !receiveHeader.isFIN()) {
                    System.out.println("Received challenge");
                    break;
                }
            } catch (SocketTimeoutException s) {
                System.out.println("Timeout, resending..");
                if (tries++ >= 5) {
                    System.out.println("Unsuccessful Connection");
                    return false;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }

        // Setup hash Header
        RXPHeader hashHeader = RXPHelpers.initHeader(clientPort, serverPort, seqNum, ackNum);
        hashHeader.setFlags(true, false, false, false, false, false); //setting ACK flag on
        String bytesAsString = null;
        byte[] challenge = RXPHelpers.extractData(receivePacket);
        try {
            bytesAsString = new String(challenge, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        System.out.printf("Working with challenge:%s\n", bytesAsString);

        byte[] datahash = RXPHelpers.getHash(challenge);
        hashHeader.setWindow(datahash.length);
        try {
            bytesAsString = new String(datahash, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        System.out.printf("Setting up hash of %s\n", bytesAsString);
        hashHeader.setChecksum(datahash);
        // Make the packet
        DatagramPacket hashPacket = RXPHelpers.preparePacket(serverIpAddress, serverPort, hashHeader, datahash);


        // Sending ACK packet with hash and receiving ACK for establishment
        tries = 0;
        state = ClientState.HASH_SENT;
        while (state != ClientState.ESTABLISHED) {
            try {
                clientSocket.send(hashPacket);
                clientSocket.receive(receivePacket);
                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);
//                if (!RXPHelpers.isValidPacketHeader(receivePacket))    //Corrupted
//                {
//                    System.out.println("Dropping corrupted packets");
//                    continue;
//                }

                // Assuming valid and ACK
                if (receiveHeader.isACK() && !receiveHeader.isFIN()) {
                    System.out.println("Established");
                    state = ClientState.ESTABLISHED;
                    serverRXPPort = receiveHeader.getSource();
                    break;
                }
            } catch (SocketTimeoutException s) {
                System.out.println("Timeout, resending..");
                if (tries++ >= 5) {
                    System.out.println("Unsuccessful Connection");
                    return false;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }

    /*
    * sends name to server to prep server to receive upload
    *
     */
    public void sendName(String s) {
        System.out.println("Attempting to send filename to upload to server");
        RXPHeader nameHeader = RXPHelpers.initHeader(clientPort, serverPort, 0, 0);
        nameHeader.setFlags(false, false, false, false, true, false); // POST
        byte[] sendData = s.getBytes(Charset.forName("UTF-8"));
        nameHeader.setWindow(sendData.length);
        nameHeader.setChecksum(sendData);
        // Make the packet
        DatagramPacket sendingPacket = RXPHelpers.preparePacket(serverIpAddress, serverPort, nameHeader, sendData);

        DatagramPacket receivePacket = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);

        int tries = 0;
        while (true) {
            try {
                clientSocket.send(sendingPacket);
                clientSocket.receive(receivePacket);

                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

//                if (!RXPHelpers.isValidPacketHeader(receivePacket) || !RXPHelpers.isValidPort(receivePacket, clientPort, serverRXPPort)) {
//                    System.out.println("Dropping corrupted packet");
//                    continue;
//                }
                if (receiveHeader.isACK() && !receiveHeader.isFIN()) {
                    System.out.println("Server acknowledged the filename.");
                    break;
                }
            } catch (SocketTimeoutException es) {
                System.out.println("Timeout, resending");
                if (tries++ >= 5) {
                    System.out.println("Unsuccessful Connection");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * starts and carries out upload transfer
     */
    public boolean upload(byte[] file) {
        fileData = file;
        int totalPackets = (fileData.length / DATA_SIZE);
        if (fileData.length % DATA_SIZE > 0) totalPackets += 1; //1 extra packet if there's leftover data

        int currPacket = 0;
        DatagramPacket sendingPacket;
        DatagramPacket receivePacket = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);

        while (currPacket < totalPackets) {
            sendingPacket = createDataPacket(currPacket);
            try {
                clientSocket.send(sendingPacket);
                clientSocket.receive(receivePacket);
                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

//                if (!RXPHelpers.isValidPacketHeader(receivePacket) || !RXPHelpers.isValidPort(receivePacket, clientPort, serverRXPPort)) {   //got a corrupted packet
//                    System.out.println("Dropping invalid packet");
//                    continue;
//                }
                if (receiveHeader.isFIN()) {    //server wants to terminate
                    tearDown();
                }
                if (seqNum == receiveHeader.getAckNum()) { //getting ack for previous packet
                    continue;
                }
                if (seqNum + 1 == receiveHeader.getAckNum()) {  //got the ack for this packet
                    seqNum = (seqNum + 1) % MAX_SEQ_NUM;
                    ackNum = receiveHeader.getSeqNum();
                    currPacket++;
                }
            } catch (SocketTimeoutException s) {
                System.out.println("Timeout, resending..");
                return false;
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        fileData = null;
        return true;
    }

    /*
    * creates packets of indexed bytes of file
     */
    private DatagramPacket createDataPacket(int initByteIndex) {
        System.out.printf("Creating data packet # %d \n", initByteIndex);
        // Setup header for the data packet
        int byteLocation = initByteIndex * DATA_SIZE;
        int bytesRemaining = fileData.length - byteLocation;

        RXPHeader header = RXPHelpers.initHeader(clientPort, serverPort, seqNum, (ackNum + 1) % MAX_SEQ_NUM);
        //TODO double check the seqNum and ackNum configuration ^

        int data_length;
        if (bytesRemaining <= DATA_SIZE) { //utilized for last segment of data
            data_length = bytesRemaining;
            header.setFlags(false, false, false, false, false, true); // LAST flag

        } else {
            data_length = DATA_SIZE;
        }
        header.setWindow(data_length);
        byte[] data = new byte[data_length];
        System.arraycopy(fileData, initByteIndex * DATA_SIZE, data, 0, data_length);
        header.setChecksum(data);

        // Make the packet
        DatagramPacket dataPacket = RXPHelpers.preparePacket(serverIpAddress, serverPort, header, data);
        return dataPacket;
    }

    /*
     * request download of specified filename and carry out download
     */
    public boolean download(String fileName) {
        //Send GET packet with filename
        byte[] receiveMessage = new byte[PACKET_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveMessage, receiveMessage.length);
        RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

        // Setup Initializing Header

        RXPHeader requestHeader = RXPHelpers.initHeader(clientPort, serverPort, seqNum, ackNum);
        requestHeader.setFlags(false, false, false, true, false, false); // GET
        requestHeader.setWindow(fileName.getBytes().length);
        byte[] data = fileName.getBytes();
        requestHeader.setChecksum(data);

        // Make the packet
        DatagramPacket requestPacket = RXPHelpers.preparePacket(serverIpAddress, serverPort, requestHeader, data);

        int currPacket = 0;
        int tries = 0;
        boolean finDownload = false;
        bytesReceived = new ArrayList<byte[]>();
        while (!finDownload) {
            try {

                System.out.println("Sending: " + seqNum + ", " + ackNum);

                clientSocket.send(requestPacket);
                System.out.println("Sent");

                clientSocket.receive(receivePacket);
                receiveHeader = RXPHelpers.getHeader(receivePacket);
//				System.out.print(currPacket + " " + seqNum + " ---- " + ackNum + " \n");

                System.out.println("Receiving: " + receiveHeader.getSeqNum() + ", " + receiveHeader.getAckNum());
                System.out.println("Global: " + seqNum + ", " + ackNum);

                boolean isLast = receiveHeader.isLAST();
//                if (!RXPHelpers.isValidPacketHeader(receivePacket) || !RXPHelpers.isValidPort(receivePacket, clientPort, serverRXPPort)) {
//					  System.out.println("Dropping corrupted packet);
//                    continue;
//                }
                // Assuming valid and acked
                if (receiveHeader.isACK()) {
                    System.out.println("Is ACK, Skip");
                    continue; //got ack packet for some reason, this isn't our desired data
                }
                if (ackNum == receiveHeader.getSeqNum()) {
                    System.out.println("Right packet");
                    requestPacket = receiveDataPacket(receivePacket, currPacket);
//                    clientSocket.send(requestPacket);
//                    this.fileName = fileName;
                    currPacket++;

                    if (receiveHeader.isLAST()) {
                        finDownload = true;
                        break;
                    }
                }

            } catch (SocketTimeoutException s) {
                System.out.println("Timeout, resending..");
                if (tries++ >= 5) {
                    System.out.println("Download could not be started");
                    return false;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        System.out.println("Finished downloading");
        boolean resultOfAssemble = RXPHelpers.assembleFile(bytesReceived, fileName);
        fileData = null;
        bytesReceived = new ArrayList<byte[]>();
        return resultOfAssemble;
    }

    /*
    ** take received packets into byte array collection and prepare ack packet
     */
    private DatagramPacket receiveDataPacket(DatagramPacket receivePacket, int nextPacketNum) throws IOException {
        RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

        // extracts and adds data to ArrayList of byte[]s
        byte[] data = RXPHelpers.extractData(receivePacket);
        bytesReceived.add(data);

        ackNum = (receiveHeader.getSeqNum() + 1) % MAX_SEQ_NUM;
        seqNum = receiveHeader.getAckNum();
        RXPHeader ackHeader = RXPHelpers.initHeader(clientPort, serverPort, seqNum, ackNum);
        //TODO ^^ verify acknum and seqnum changes and entries into initHeader
        if (receiveHeader.isLAST()) {
            ackHeader.setFlags(true, false, false, false, false, true); // ACK LAST
        } else {
            ackHeader.setFlags(true, false, false, true, false, false);    // ACK
        }

        byte[] dataBytes = ByteBuffer.allocate(4).putInt(nextPacketNum).array(); //TODO what is this for
        ackHeader.setChecksum(dataBytes);
        ackHeader.setWindow(dataBytes.length);

        DatagramPacket sendPacket = RXPHelpers.preparePacket(serverIpAddress, serverPort, ackHeader, dataBytes);
        return sendPacket;
    }

    /**
     * tear down connection
     */
    public void tearDown() {
        System.out.println("Shutting down");
        System.exit(0);
    }

    public ClientState getClientState() {
        return state;
    }

    public int getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(int window) {
        this.windowSize = window;
    }
}