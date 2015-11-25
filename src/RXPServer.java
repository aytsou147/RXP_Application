import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * RXP Server
 */
public class RXPServer extends Thread {
    private static final int PACKET_SIZE = 512;
    private static final int DATA_SIZE   = 496;
    private static final int MAX_SEQ_NUM = (int) 0xFFFF;

    private DatagramSocket serverSocket;
    private DatagramPacket sendPacket, receivePacket;
    private InetAddress serverIpAddress, clientIpAddress;
    private int serverPort, clientNetPort;
    private int clientRXPPort;

    private int windowSize;
    private Random rand;

    private ServerState state;
    private int seqNum, ackNum;
    private String pathName="";
    private ArrayList<byte[]> bytesReceived;
    private byte[] fileData;
    private boolean timedTaskRun= false;

    private HashMap<Integer, String> challengeMap = new HashMap<>();

    private boolean closeReq = false;
    private boolean isBusy = false;

    // Default constructor
    public RXPServer() {
        bytesReceived = new ArrayList<> ();
        serverPort = 3250;
        try {
//            serverIpAddress = InetAddress.getLocalHost();
            this.serverIpAddress = InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        state = ServerState.CLOSED;
    }

    public RXPServer(int serverPort, String clientIpAddress, int clientNetPort) {

        bytesReceived = new ArrayList<>();
        this.serverPort = serverPort;
        this.clientNetPort = clientNetPort;
        try {
//            this.serverIpAddress = InetAddress.getLocalHost();
            this.serverIpAddress = InetAddress.getByName("127.0.0.1");
            this.clientIpAddress = InetAddress.getByName(clientIpAddress);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        state = ServerState.CLOSED;
//        Random rand = new Random();
//        seqNum = rand.nextInt(MAX_SEQ_NUM);
    }

    public void run() {
        while(true) {
            connect();
        }
    }

    public void createSocket() {
        try {
            serverSocket = new DatagramSocket(serverPort, serverIpAddress);
            serverSocket.setSoTimeout(5000);
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    public void connect() {
        byte[] arr = new byte[PACKET_SIZE];
        receivePacket = new DatagramPacket(arr, PACKET_SIZE);
        boolean hashAckSent = false;

        // handshake
        while (state == ServerState.CLOSED || state == ServerState.CHALLENGE_SENT) {
            try {
                // Receive Packet
                serverSocket.receive(receivePacket);
                System.out.println("Packet received");

                // Get Header of Packet
                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

                //Checksum validation
                if (!RXPHelpers.isValidPacketHeader(receivePacket)) {
                    System.out.println("Dropping invalid packet");
                    continue;
                }

                // HANDSHAKE PT 1: Receive SYN, send SYN+ACK and challenge string
                if (receiveHeader.isSYN() && !receiveHeader.isACK()) {
                    sendChallenge(receiveHeader);
                    state = ServerState.CHALLENGE_SENT;
                }

                // HANDSHAKE PT 2: Receive ACK and challenge hash, send ACK
                if (receiveHeader.isACK() && !receiveHeader.isSYN()) {
                    verifyChallenge(receivePacket);
                    hashAckSent = true;
                }
            } catch (SocketTimeoutException s) {
                if (hashAckSent) {
                    break;
                }

                if (state == ServerState.CLOSED) {
                    System.out.println("Waiting for client...");
                } else {
                    System.out.println("Timed out");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        while (state == ServerState.ESTABLISHED) {
            try {
                serverSocket.receive(receivePacket);
                System.out.println("Packet received");

                //Checksum validation
                if (!RXPHelpers.isValidPacketHeader(receivePacket)) {
                    System.out.println("Dropping invalid packet");
                    continue;
                }
                if (!RXPHelpers.isValidPorts(receivePacket, serverPort, clientRXPPort)) {
                    System.out.println("Dropping packet of incorrect ports");
                    continue;
                }

                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

                if (receiveHeader.isGET()) {
                    isBusy = true;
                    if (sendFile(receivePacket)) {
                        System.out.println("Sent file!");
                        if (closeReq) {
                            serverDisconnect();
                        }
                    } else {
                        System.out.println("Failed to send file");
                    }
                    isBusy = false;
                }

                if (receiveHeader.isPOST()) {
                    isBusy = true;
                    receiveFile(receivePacket);
                    if (closeReq) {
                        serverDisconnect();
                    }
                    isBusy = false;
                }

                if (receiveHeader.isFIN() && !receiveHeader.isACK()) {
                    respondToCloseReq();
                }
            } catch (SocketTimeoutException s) {
                System.out.println("Timed out");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (state == ServerState.CLOSE_WAIT) {
            System.out.println("Connection closed successfully!");
            state = ServerState.CLOSED;
        }
    }

    /**
     * Received FIN from client, sends FIN ACK back, transitions state to CLOSE_WAIT
     */
    private void respondToCloseReq() {
        System.out.println("Acknowledging client's close request...");
        RXPHeader sendHeader = RXPHelpers.initHeader(serverPort, clientRXPPort, seqNum, ackNum);
        sendHeader.setFlags(true, false, true, false, false, false); // ACK, FIN

        byte[] sendData = new byte[DATA_SIZE];
        sendHeader.setChecksum(sendData);
        sendHeader.setWindow(sendData.length);

        DatagramPacket sendPacket = RXPHelpers.preparePacket(clientIpAddress, clientNetPort, sendHeader, sendData);
        boolean finAckSent = false;

        while (true) {
            try {
                serverSocket.send(sendPacket);
                finAckSent = true;
                serverSocket.receive(receivePacket);
            } catch (SocketTimeoutException s) {
                if (finAckSent) {
                    break;
                }
                System.out.println("Timeout, resending..");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        state = ServerState.CLOSE_WAIT;
    }

    private boolean receiveFile(DatagramPacket receivePacket) {
        // Get received packet info
        RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);
        byte[] filePath = RXPHelpers.extractData(receivePacket); //get the data from the packet

        String fileString = null;
        try {
            fileString = new String(filePath, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        RXPHeader sendHeader = RXPHelpers.initHeader(serverPort, clientRXPPort, seqNum, ackNum);
        sendHeader.setFlags(true, false, false, false, true, false); // ACK, POST

        byte[] sendData = new byte[DATA_SIZE];

        sendHeader.setChecksum(sendData);
        sendHeader.setWindow(sendData.length);

        DatagramPacket sendPacket = RXPHelpers.preparePacket(clientIpAddress, clientNetPort, sendHeader, sendData);

        int currPacket = 0;
        int tries = 0;
        boolean finDownload = false;
        boolean closeRequest = false;
        bytesReceived = new ArrayList<byte[]>();

        while (true) {
            try {
                serverSocket.send(sendPacket);
                serverSocket.receive(receivePacket);

                receiveHeader = RXPHelpers.getHeader(receivePacket);
                if (!RXPHelpers.isValidPacketHeader(receivePacket)) {
                    System.out.println("Dropping corrupted packet");
                    continue;
                }
                if (!RXPHelpers.isValidPorts(receivePacket, serverPort, clientRXPPort)) {
                    System.out.println("Dropping packet of incorrect ports");
                    continue;
                }

                if (receiveHeader.isFIN()) {    //client wants to terminate
                    closeRequest = true;
                }

                if (receiveHeader.isFIN() && closeRequest) {
                    break;
                }

                if (ackNum == receiveHeader.getSeqNum()) {
                    System.out.println("Correct packet");
                    sendPacket = receiveDataPacket(receivePacket, currPacket);
                    currPacket++;
                }

                if (receiveHeader.isLAST()) {
                    finDownload = true;
                }
            } catch (SocketTimeoutException s) {
                if (finDownload) {
                    break;
                }

                System.out.println("Timeout, resending..");
                if (tries++ >= 5) {
                    System.out.println("Download could not be started");
                    return false;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        System.out.println("Finished downloading");
        boolean resultOfAssemble = RXPHelpers.assembleFile(bytesReceived, fileString);
        fileData = null;
        bytesReceived = new ArrayList<byte[]>();
        if (closeRequest) {
            respondToCloseReq();
        }
        return resultOfAssemble;
    }

    /**
     * starts and carries out upload transfer
     */
    public boolean sendFile(DatagramPacket receivePacket) {

        // Get received packet info
        RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);
        byte[] filePath = RXPHelpers.extractData(receivePacket); //get the data from the packet

        String fileString = null;
        try {
            fileString = new String(filePath, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        System.out.println(fileString);

        fileData = RXPHelpers.getFileBytes(fileString);

        if (fileData == null) {
            return false;
        }

        int numPackets = (fileData.length / DATA_SIZE);
        if (fileData.length % DATA_SIZE > 0) numPackets += 1; //1 extra packet if there's leftover data

        int currPacket = 0;
        DatagramPacket sendingPacket;
//        DatagramPacket receivePacket = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);

        while (currPacket < numPackets) {
            sendingPacket = createDataPacket(currPacket);
            try {
                System.out.println("Created: " + seqNum + ", " + ackNum);

                System.out.println("Sending: " + RXPHelpers.getHeader(sendingPacket).getSeqNum() + ", " + RXPHelpers.getHeader(sendingPacket).getAckNum());

                serverSocket.send(sendingPacket);

                System.out.println("Sent");
                serverSocket.receive(receivePacket);
                receiveHeader = RXPHelpers.getHeader(receivePacket);

                System.out.println("Received: " + receiveHeader.getSeqNum() + ", " + receiveHeader.getAckNum());

                if (!RXPHelpers.isValidPacketHeader(receivePacket)) {   //got a corrupted packet
                    System.out.println("Dropping invalid packet");
                    continue;
                }
                if (!RXPHelpers.isValidPorts(receivePacket, serverPort, clientRXPPort)) {
                    System.out.println("Dropping packet of incorrect ports");
                    continue;
                }

//                if (receiveHeader.isFIN()) {    //client wants to terminate
//                    tearDown();
//                }

                if (receiveHeader.isACK() && receiveHeader.isLAST()) {
                    break;
                }

                if (seqNum == receiveHeader.getAckNum()) { //getting ack for previous packet
                    System.out.println("Already got this ack.");
                    continue;
                }

                if (seqNum + 1 == receiveHeader.getAckNum()) {  //got the ack for this packet
                    System.out.println("Correct ack");
                    seqNum = (receiveHeader.getSeqNum() + 1) % MAX_SEQ_NUM;
                    ackNum = receiveHeader.getAckNum();
                    currPacket++;
                }
            } catch (SocketTimeoutException s) {
                System.out.println("Timeout, resending..");
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

        RXPHeader header = RXPHelpers.initHeader(serverPort, clientRXPPort, seqNum, ackNum);

        int data_length;
        if (bytesRemaining <= DATA_SIZE) { //utilized for last segment of data
            System.out.println(">>>>>>>>>>>>LAST<<<<<<<<<<<");
            data_length = bytesRemaining;
            header.setFlags(false, false, false, false, false, true); // LAST flag
        } else {
            data_length = DATA_SIZE;
            header.setFlags(false, false, false, false, false, false);
        }
        header.setWindow(data_length);
        byte[] data = new byte[data_length];
        System.arraycopy(fileData, initByteIndex * DATA_SIZE, data, 0, data_length);
        header.setChecksum(data);

        return RXPHelpers.preparePacket(clientIpAddress, clientNetPort, header, data);
    }

    /*
    * take received packets into byte array collection and prepare ack packet
    */
    private DatagramPacket receiveDataPacket(DatagramPacket receivePacket, int nextPacketNum) throws IOException {
        RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

        // extracts and adds data to ArrayList of byte[]s
        byte[] data = RXPHelpers.extractData(receivePacket);
        bytesReceived.add(data);

        ackNum = (receiveHeader.getSeqNum() + 1) % MAX_SEQ_NUM;
        seqNum = receiveHeader.getAckNum();
        RXPHeader ackHeader = RXPHelpers.initHeader(serverPort, clientRXPPort, seqNum, ackNum);
        if (receiveHeader.isLAST()) {
            ackHeader.setFlags(true, false, false, false, false, true); // ACK LAST
            System.out.println("Creating LAST ACK packet");
        } else {
            ackHeader.setFlags(true, false, false, true, false, false);    // ACK
        }

        byte[] dataBytes = ByteBuffer.allocate(4).putInt(nextPacketNum).array();
        ackHeader.setChecksum(dataBytes);
        ackHeader.setWindow(dataBytes.length);

        DatagramPacket sendPacket = RXPHelpers.preparePacket(clientIpAddress, clientNetPort, ackHeader, dataBytes);
        return sendPacket;
    }

    /**
     * After receiving the connection request (SYN), sends a SYN+ACK packet with a 32-bit challenge string in its data
     * @throws IOException
     */
    private void sendChallenge(RXPHeader receiveHeader) throws IOException {
        // Set up the header
        RXPHeader sendHeader = RXPHelpers.initHeader(serverPort, clientRXPPort, 0, 0);
        sendHeader.setFlags(true, true, false, false, false, false); // ACK, SYN

        // Set up the data
        String challenge = UUID.randomUUID().toString().replaceAll("-","") + UUID.randomUUID().toString().replaceAll("-","");
        challengeMap.put(receiveHeader.getSource(), challenge);

        //System.out.println("Source port: " + receiveHeader.getSource());
        //System.out.println("Challenge " + challenge + " was sent");

        byte[] sendData = challenge.getBytes();
        sendHeader.setChecksum(sendData);
        sendHeader.setWindow(sendData.length);
        // Make the packet
        DatagramPacket sendPacket = RXPHelpers.preparePacket(clientIpAddress, clientNetPort, sendHeader, sendData);

        // Send packet
        serverSocket.send(sendPacket);
    }

    private void verifyChallenge(DatagramPacket receivePacket) throws IOException {

        // Get received packet info
        RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);
        byte[] clientHash = RXPHelpers.extractData(receivePacket); //get the data from the packet

        // Delete this later
        String extracted = null;
        try {
            extracted = new String(clientHash, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
//        System.out.printf("Extracted " + extracted);

        ackNum = receiveHeader.getSeqNum();
        int sendAckNum = (ackNum + 1) % MAX_SEQ_NUM;
//        int receiveSeqNum = receiveHeader.getSeqNum();

        // Check Hash
        //System.out.println("Source port 2: " + receiveHeader.getSource());
        String serverChallenge = challengeMap.get(receiveHeader.getSource());

        System.out.println("Challenge: " + serverChallenge + " was taken from hashmap");

        byte[] serverHash = RXPHelpers.getHash(serverChallenge.getBytes());

        // Delete this later
        String bytesAsString = null;
        try {
            bytesAsString = new String(serverHash, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
//        System.out.printf("Setting up hash of %s\n", bytesAsString);

        // Confirmed match
        if (Arrays.equals(clientHash, serverHash)) {
            // Send ACK packet
            RXPHeader sendHeader = RXPHelpers.initHeader(serverPort, clientRXPPort, seqNum, sendAckNum);
            sendHeader.setFlags(true, false, false, false, false, false); // ACK
            byte[] sendData = new byte[DATA_SIZE];
            sendHeader.setChecksum(sendData);
            sendHeader.setWindow(sendData.length);
            DatagramPacket sendPacket = RXPHelpers.preparePacket(clientIpAddress, clientNetPort, sendHeader, sendData);
            serverSocket.send(sendPacket);
            state = ServerState.ESTABLISHED;
            clientRXPPort = receiveHeader.getSource();
        } else {
            // Refuse the connection
            System.out.println("Incorrect Auth");
        }
    }

    /**
     * Initiates disconnecting by sending a FIN to the client and expecting a FIN + ACK in return
     */
    private void serverDisconnect() {
        System.out.println("Beginning disconnection from server side...");

        RXPHeader sendHeader = RXPHelpers.initHeader(serverPort, clientRXPPort, seqNum, ackNum);
        sendHeader.setFlags(false, false, true, false, false, false); // FIN.

        byte[] sendData = new byte[DATA_SIZE];
        sendHeader.setWindow(sendData.length);
        sendHeader.setChecksum(sendData);

        // Make the packet
        DatagramPacket sendingPacket = RXPHelpers.preparePacket(clientIpAddress, clientNetPort, sendHeader, sendData);

        int tries = 0;
        while (true) {
            try {
                serverSocket.send(sendingPacket);
                serverSocket.receive(receivePacket);

                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

                if (!RXPHelpers.isValidPorts(receivePacket, serverPort, clientRXPPort)) {
                    System.out.println("Dropping packet of incorrect ports");
                    continue;
                }

                if (!RXPHelpers.isValidPacketHeader(receivePacket)) {
                    System.out.println("Dropping corrupted packet");
                    continue;
                }

                if (receiveHeader.isACK() && receiveHeader.isFIN()) {
                    System.out.println("Client acknowledged close with FIN ACK");
                    break;
                }
            } catch (SocketTimeoutException es) {
                System.out.println("Timeout, resending");
                if (tries++ >= 5) {
                    System.out.println("Unsuccessful request.");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        state = ServerState.CLOSED;
    }

    public void terminate() {
        if (isBusy) {
            closeReq = true;
            System.out.println("Waiting for transfer to finish!");
        } else {
            serverDisconnect();
        }
    }

    /**
     * tear down connection
     */
    public void tearDown() {
        System.out.println("Shutting down");
        System.exit(0);
    }
}