import java.io.IOException;
import java.net.*;
import java.util.*;

/**
 * RXP Server
 */
public class RXPServer {
    private static final int CHECKSUM 	 = 13566144;
    private static final int PRECHECKSUM = 3251;
    private static final int PACKET_SIZE = 512;
    private static final int DATA_SIZE   = 496;
    private static final int HEADER_SIZE = 20;
    private static final int MAX_SEQ_NUM = (int) 0xFFFF;

    private DatagramSocket serverSocket;
    private DatagramPacket sendPacket, receivePacket;
    private InetAddress serverIpAddress, clientIpAddress;
    private int serverPort, clientPort;

    private int windowSize;
    private Random rand;

    private ServerState state;
    private int seqNum, ackNum;
    private String pathName="";
    private ArrayList<byte[]> bytesReceived;
    private byte[] fileData;
    private boolean timedTaskRun= false;

    private HashMap<Integer, String> challengeMap = new HashMap<>();

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

    public RXPServer(int serverPort, String clientIpAddress, int clientPort){

        bytesReceived = new ArrayList<>();
        this.serverPort = serverPort;
        this.clientPort = clientPort;
        try {
//            this.serverIpAddress = InetAddress.getLocalHost();
            this.serverIpAddress = InetAddress.getByName("127.0.0.1");
            this.clientIpAddress = InetAddress.getByName(clientIpAddress);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        state = ServerState.CLOSED;
        Random rand = new Random();
        seqNum = rand.nextInt(MAX_SEQ_NUM);
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

        // handshake
        while (state != ServerState.ESTABLISHED) {
            try {
                // Receive Packet
                serverSocket.receive(receivePacket);
                System.out.println("Packet received");

                // Get Header of Packet
                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

                // Checksum validation
//                if (!RXPHelpers.isValidPacketHeader(receivePacket)) {
////                    resendPacket(receivePacket, false);
//                    continue;
//                }

                // HANDSHAKE PT 1: Receive SYN, send SYN+ACK and challenge string
                if (receiveHeader.isSYN() && !receiveHeader.isACK()) {
                    sendChallenge(receiveHeader);
                    state = ServerState.CHALLENGE_SENT;
                }

                // HANDSHAKE PT 2: Receive ACK and challenge hash, send ACK
                if (receiveHeader.isACK() && !receiveHeader.isSYN()) {
                    verifyChallenge(receivePacket);
                }
            } catch (SocketTimeoutException s) {
                System.out.println("Timed out");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * After receiving the connection request (SYN), sends a SYN+ACK packet with a 32-bit challenge string in its data
     * @throws IOException
     */
    private void sendChallenge(RXPHeader receiveHeader) throws IOException {
        // Set up the header
        RXPHeader sendHeader = RXPHelpers.initHeader(serverPort, clientPort, 0, 0);
        sendHeader.setFlags(true, true, false, false, false, false); // ACK, SYN

        // Set up the data
        String challenge = UUID.randomUUID().toString().replaceAll("-","") + UUID.randomUUID().toString().replaceAll("-","");
        challengeMap.put(receiveHeader.getSource(), challenge);
        System.out.println("Source port: " + receiveHeader.getSource());

        byte[] sendData = challenge.getBytes();

        // Make the packet
        DatagramPacket sendPacket = RXPHelpers.preparePacket(clientIpAddress, clientPort, sendHeader, sendData);

        // Send packet
        serverSocket.send(sendPacket);
    }

    private void verifyChallenge(DatagramPacket receivePacket) throws IOException {

        // Get received packet info
        RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);
        byte[] clientHash = RXPHelpers.extractData(receivePacket); //get the data from the packet
        ackNum = receiveHeader.getSeqNum();
        int sendAckNum = (ackNum + 1) % MAX_SEQ_NUM;
//        int receiveSeqNum = receiveHeader.getSeqNum();

        // Check Hash
        System.out.println("Source port 2: " + receiveHeader.getSource());
        String serverChallenge = challengeMap.get(receiveHeader.getSource());
        byte[] serverHash = RXPHelpers.getHash(serverChallenge.getBytes());

        // Confirmed match
        if (Arrays.equals(clientHash, serverHash)) {
            // Send ACK packet
            RXPHeader sendHeader = RXPHelpers.initHeader(serverPort, clientPort, seqNum, sendAckNum);
            sendHeader.setFlags(true, false, false, false, false, false); // ACK
            byte[] sendData = new byte[DATA_SIZE];
            DatagramPacket sendPacket = RXPHelpers.preparePacket(clientIpAddress, clientPort, sendHeader, sendData);
            serverSocket.send(sendPacket);
            state = ServerState.ESTABLISHED;
        } else {
            // Refuse the connection
            System.out.println("Incorrect Auth");
        }
    }

    public boolean terminate() {
        return true;
    }
}