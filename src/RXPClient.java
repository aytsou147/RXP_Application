import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Random;

public class RXPClient {

    private static final int PACKET_SIZE = 512;
    private static final int DATA_SIZE = 496;
    private static final int HEADER_SIZE = 16;
    private static final int MAX_SEQ_NUM = (int) 0xFFFF;

    private ClientState state;

    private int clientPort, serverPort;
    private InetAddress clientIpAddress, serverIpAddress;
    private DatagramSocket clientSocket;
    private Random rand;

    private int timeout = 5000;    // ms

    private byte[] window = new byte[MAX_SEQ_NUM];
    private int seqNum, ackNum, windowSize, bytesRemaining;
    private String pathName = "";
    private byte[] fileData;
    private ArrayList<byte[]> bytesReceived;
    private String fileName;

//    public RXPClient() {
//        this.clientPort = 3251;
//        this.serverPort = 3252;
//        try {
//            this.clientIpAddress = InetAddress.getLocalHost();
//            this.serverIpAddress = InetAddress.getLocalHost();
//        } catch (UnknownHostException e) {
//            e.printStackTrace();
//        }
//        state = ClientState.CLOSED;
//    }

    public RXPClient(int clientPort, String serverIpAddress, int serverPort) {
        this.clientPort = clientPort;
        this.serverPort = serverPort;
        try {
            this.clientIpAddress = InetAddress.getLocalHost();
            this.serverIpAddress = InetAddress.getByName(serverIpAddress);
        } catch (UnknownHostException e) {
            e.printStackTrace();
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
    public boolean setup() {
        try {
            clientSocket = new DatagramSocket(clientPort, clientIpAddress);
        } catch (SocketException e) {
            e.printStackTrace();
        }

        byte[] receiveMessage = new byte[PACKET_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveMessage, receiveMessage.length);

        // Setup Initializing Header
        RXPHeader synHeader = new RXPHeader();
        synHeader.setSource(clientPort);
        synHeader.setDestination(serverPort);
        synHeader.setSeqNum(seqNum);
        synHeader.setAckNum(ackNum);
        synHeader.setFlags(false, true, false, false, false, false); //setting SYN flag on
        synHeader.setWindow(DATA_SIZE);
        byte[] data = new byte[DATA_SIZE];
        synHeader.setChecksum(data);
        byte[] headerBytes = synHeader.getHeaderBytes();
        byte[] packet = RXPHelpers.combineHeaderData(headerBytes, data);

        DatagramPacket setupPacket = new DatagramPacket(packet, PACKET_SIZE, serverIpAddress, serverPort);

        // Sending SYN packet and receiving SYN ACK with challenge string

        try {
            clientSocket.setSoTimeout(timeout);
        } catch (SocketException e1) {
            e1.printStackTrace();
        }


        int tries = 0;
        state = ClientState.SYN_SENT;
        while (state != ClientState.ESTABLISHED) {
            try {
                clientSocket.send(setupPacket);
                clientSocket.receive(receivePacket);
                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);
                if (!RXPHelpers.isValidPacketHeader(receivePacket))    //Corrupted
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
        RXPHeader hashHeader = new RXPHeader();
        hashHeader.setSource(clientPort);
        hashHeader.setDestination(serverPort);
        hashHeader.setSeqNum(seqNum);
        hashHeader.setAckNum(ackNum);
        hashHeader.setFlags(true, false, false, false, false, false); //setting ACK flag on
        hashHeader.setWindow(DATA_SIZE);
        byte[] datahash = RXPHelpers.getHash(RXPHelpers.extractData(receivePacket));
        hashHeader.setChecksum(data);
        byte[] hashHeaderBytes = hashHeader.getHeaderBytes();
        byte[] hashpacket = RXPHelpers.combineHeaderData(hashHeaderBytes, datahash);

        DatagramPacket hashPacket = new DatagramPacket(hashpacket, PACKET_SIZE, serverIpAddress, serverPort);

        // Sending ACK packet with hash and receiving ACK for establishment

        try {
            clientSocket.setSoTimeout(timeout);
        } catch (SocketException e1) {
            e1.printStackTrace();
        }


        tries = 0;
        state = ClientState.HASH_SENT;
        while (state != ClientState.ESTABLISHED) {
            try {
                clientSocket.send(hashPacket);
                clientSocket.receive(receivePacket);
                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);
                if (!RXPHelpers.isValidPacketHeader(receivePacket))    //Corrupted
                {
                    System.out.println("CORRUPTED");
                    continue;
                }

                // Assuming valid and ACK
                if (receiveHeader.isACK() && !receiveHeader.isFIN()) {
                    System.out.println("Established");
                    state = ClientState.ESTABLISHED;
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
    **
    *
     */
    public void sendName(String s) {
        byte[] name = s.getBytes(Charset.forName("UTF-8"));
        RXPHeader nameHeader = new RXPHeader();

        nameHeader.setSource(clientPort);
        nameHeader.setDestination(serverPort);
        nameHeader.setSeqNum(0);
        nameHeader.setAckNum(0);
        nameHeader.setFlags(false, false, false, false, true, false); // POST
        nameHeader.setWindow(name.length); //TODO why set the window size to the length of the filename?
        byte[] sendData = name;
        nameHeader.setChecksum(sendData);
        byte[] sendHeaderbytes = nameHeader.getHeaderBytes();
        byte[] namePacket = RXPHelpers.combineHeaderData(sendHeaderbytes, sendData);


        DatagramPacket sendingPacket = new DatagramPacket(namePacket, PACKET_SIZE, serverIpAddress, serverPort);
        DatagramPacket receivePacket = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);

        int tries = 0;
        while (true) {
            try {
                clientSocket.send(sendingPacket);
                clientSocket.receive(receivePacket);

                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

                if (!RXPHelpers.isValidPacketHeader(receivePacket)) {
                    continue;
                }
                if (receiveHeader.isACK() && !receiveHeader.isFIN()) {
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
     * Starts sending data transfer
     */
    public boolean upload(byte[] file) {
        fileData = file;
        bytesRemaining = fileData.length;
        int packetNumber = (fileData.length / DATA_SIZE);
        if (fileData.length % DATA_SIZE > 0) packetNumber += 1;

        int currPacket = 0;
        DatagramPacket sendingPacket;
        DatagramPacket receivePacket = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);

        while (currPacket < packetNumber) {
            sendingPacket = createPacket(currPacket);
            try {
                clientSocket.send(sendingPacket);
                clientSocket.receive(receivePacket);
                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

                if (!RXPHelpers.isValidPacketHeader(receivePacket)) {   //got a corrupted packet
                    System.out.println("Dropping invalid packet");
                    continue;
                }
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
        return true;
    }

    private DatagramPacket createPacket(int startByteIndex) {
        // Setup header for the data packet
        int byteLocation = startByteIndex * DATA_SIZE;
        int bytesRemaining = fileData.length - byteLocation;


        RXPHeader header = new RXPHeader();
        header.setSource(clientPort);
        header.setDestination(serverPort);
        header.setSeqNum(seqNum);
        header.setAckNum((ackNum + 1) % MAX_SEQ_NUM);
        int data_length;
        if (bytesRemaining <= DATA_SIZE) { //utilized for last segment of data
            data_length = bytesRemaining;
            header.setFlags(false, false, false, false, false, true); // LAST flag
        } else {
            data_length = DATA_SIZE;
            header.setFlags(false, false, false, false, false, false);
        }
        header.setWindow(data_length); //TODO why is this the window size

        byte[] data = new byte[data_length];
        header.setChecksum(data);
        System.arraycopy(fileData, startByteIndex * DATA_SIZE, data, 0, data_length);

        byte[] headerBytes = header.getHeaderBytes();
        byte[] packetBytes = RXPHelpers.combineHeaderData(headerBytes, data);

        DatagramPacket dataPacket = new DatagramPacket
                (
                        packetBytes,
                        PACKET_SIZE,
                        serverIpAddress,
                        serverPort
                );
        return dataPacket;
    }


    public boolean download(String fileName) {
        //Send GET packet with filename
        byte[] receiveMessage = new byte[PACKET_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveMessage, receiveMessage.length);

        // Setup Initializing Header
        RXPHeader requestHeader = new RXPHeader();
        requestHeader.setSource(clientPort);
        requestHeader.setDestination(serverPort);
        requestHeader.setSeqNum(seqNum);
        requestHeader.setAckNum((ackNum + 1) % MAX_SEQ_NUM);
        requestHeader.setFlags(false, false, false, true, false, false); // GET
        requestHeader.setWindow(fileName.getBytes().length);
        byte[] data = fileName.getBytes();
        requestHeader.setChecksum(data);

        byte[] headerBytes = requestHeader.getHeaderBytes();
        byte[] sendPacket = RXPHelpers.combineHeaderData(headerBytes, data);

        DatagramPacket requestPacket = new DatagramPacket(sendPacket, sendPacket.length, serverIpAddress, serverPort);
        int currPacket = 0;
        int tries = 0;
        boolean finDownload = false;
        while (!finDownload) {
            try {
                clientSocket.send(requestPacket);
                clientSocket.receive(receivePacket);
//				System.out.print(currPacket + " " + seqNum + " ---- " + ackNum + " \n");
                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);
                boolean isLast = receiveHeader.isLAST();
                if (!RXPHelpers.isValidPacketHeader(receivePacket)) {
//					System.out.println("Dropping corrupted packet);
                    continue;
                }
                // Assuming valid and acked
                if (!receiveHeader.isACK()) {
                    continue; //got ack packet for filename request, continue to download
                }

                if (seqNum + 1 == receiveHeader.getAckNum()) {
                    requestPacket = receiveDataPacket(receivePacket, currPacket, true);
                    clientSocket.send(requestPacket);
                    this.fileName = fileName;
                    currPacket++;

                    if (receiveHeader.isLAST()) {
                        finDownload = isLast;
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
        return assembleFile();
    }


    private DatagramPacket receiveDataPacket(DatagramPacket receivePacket, int nextPacketNum, boolean first) throws IOException {
        RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

        if (!first) {
            // extracts and adds data to ArrayList of byte[]s
            byte[] data = RXPHelpers.extractData(receivePacket);
            bytesReceived.add(data);
        } else {
            bytesReceived = new ArrayList<byte[]>();
        }

        RXPHeader ackHeader = new RXPHeader();
        ackHeader.setSource(clientPort);
        ackHeader.setDestination(serverPort);
        ackNum = receiveHeader.getSeqNum();
        seqNum = (seqNum + 1) % MAX_SEQ_NUM;
        ackHeader.setSeqNum(seqNum);
        ackHeader.setAckNum((ackNum + 1) % MAX_SEQ_NUM);
        if (receiveHeader.isLAST()) {
            ackHeader.setFlags(true, false, false, false, false, true); // ACK LAST
        } else {
            ackHeader.setFlags(true, false, false, true, false, false);    // ACK
        }

        byte[] dataBytes = ByteBuffer.allocate(4).putInt(nextPacketNum).array(); //TODO what is this for
        ackHeader.setChecksum(dataBytes);
        ackHeader.setWindow(dataBytes.length); //TODO why?
        byte[] ackHeaderBytes = ackHeader.getHeaderBytes();
        byte[] packet = RXPHelpers.combineHeaderData(ackHeaderBytes, dataBytes);


        DatagramPacket sendPacket = new DatagramPacket
                (
                        packet,
                        PACKET_SIZE,
                        serverIpAddress,
                        serverPort
                );
        return sendPacket;
    }


    private boolean assembleFile() {
        int bufferLength = bytesReceived.size();
        int lastByteArrayLength = bytesReceived.get(bufferLength - 1).length;    // Length of last data
        int fileSize = (bufferLength - 1) * DATA_SIZE + lastByteArrayLength;    // number of bytes in file

        fileData = new byte[fileSize];
        for (int i = 0; i < bufferLength - 1; i++) {
            System.arraycopy(bytesReceived.get(i), 0, fileData, i * DATA_SIZE, DATA_SIZE);
        }

        // Copy last data
        System.arraycopy(bytesReceived.get(bufferLength - 1), 0, fileData, (bufferLength - 1) * DATA_SIZE, lastByteArrayLength);

        String fileDir = System.getProperty("user.dir") + "/" + fileName;

        RXPHelpers.getFileFromBytes(fileDir, fileData);

        //clearing buffer
        fileData = null;
        bytesReceived = new ArrayList<byte[]>();
        return true;
    }


    /**
     * tear down connection
     */
    public void tearDown() {
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
