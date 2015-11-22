import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class RXPClient {

    private static final int PACKET_SIZE = 512;
    private static final int DATA_SIZE = 496;
    private static final int HEADER_SIZE = 16; //TODO
    private static final int MAX_SEQ_NUM = (int) 0xFFFF;

    private ClientState state;

    private int clientPort, serverPort;
    private InetAddress clientIpAddress, serverIpAddress;
    private DatagramSocket clientSocket;
    private Random rand;

    private int timeout = 5000;    // ms

    //private byte[] window = new byte[MAX_SEQ_NUM];
    private int seqNum, ackNum, windowSize, bytesRemaining;
    private String pathName = "";
    private byte[] fileData;
    private boolean timedTaskRun = false;
    private ArrayList<byte[]> bytesReceived;
    private String fileName;

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
            this.clientIpAddress = InetAddress.getLocalHost();
            this.serverIpAddress = InetAddress.getByName(serverIpAddress);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        rand = new Random();
        seqNum = rand.nextInt(MAX_SEQ_NUM);
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
        RXPHeader liveHeader = new RXPHeader();
        liveHeader.setSource(clientPort);
        liveHeader.setDestination(serverPort);
        liveHeader.setSeqNum(seqNum);
        liveHeader.setAckNum(ackNum);
        liveHeader.setFlags(true, false, false, false, false); //setting LIVE flag on
        liveHeader.setChecksum(PRECHECKSUM);
        liveHeader.setWindow(DATA_SIZE);
        byte[] data = new byte[DATA_SIZE];
        byte[] headerBytes = liveHeader.getHeaderBytes();
        byte[] packet = RXPHelpers.combineHeaderData(headerBytes, data);

        DatagramPacket setupPacket = new DatagramPacket(packet, PACKET_SIZE, serverIpAddress, serverPort);

        // Sending LIVE packet and receiving ACK

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

                // Assuming valid and Acknowledged
                if (receiveHeader.isLive() && receiveHeader.isAck() && !receiveHeader.isLast()) {
					System.out.println("Received challenge");
                    //setupPacket = handShakeLiveAck(receivePacket); //TODO
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

    private DatagramPacket handShakeHash(DatagramPacket receivePacket) throws IOException {
        RXPHeader ackHeader = new RXPHeader();
        ackHeader.setSource(clientPort);
        ackHeader.setDestination(serverPort);
        ackHeader.setChecksum(PRECHECKSUM);
        ackHeader.setSeqNum(seqNum);
        ackHeader.setAckNum(0);
        ackHeader.setFlags(false, false, false, false, false); // ACK TODO
        ackHeader.setWindow(DATA_SIZE);
        byte[] sendData = new byte[DATA_SIZE];
        byte[] liveAckHeaderBytes = ackHeader.getHeaderBytes();
        byte[] packet = RXPHelpers.combineHeaderData(liveAckHeaderBytes, sendData);

        state = ClientState.HASH_SENT;

        byte[] ackHeaderBytes = ackHeader.getHeaderBytes();
        DatagramPacket ackPacket = new DatagramPacket
                (
                        packet,
                        PACKET_SIZE,
                        serverIpAddress,
                        serverPort
                );
        return ackPacket;
    }


    public void sendName(String s) {
        byte[] name = s.getBytes(Charset.forName("UTF-8"));
        RXPHeader nameHeader = new RXPHeader();

        nameHeader.setSource(clientPort);
        nameHeader.setDestination(serverPort);
        nameHeader.setChecksum(PRECHECKSUM);
        nameHeader.setSeqNum(0);
        nameHeader.setAckNum(0);
        nameHeader.setFlags(false, false, false, true, false); // LIVE FIRST
        nameHeader.setWindow(name.length);
        byte[] sendData = name;
        byte[] liveAckHeaderBytes = nameHeader.getHeaderBytes();
        byte[] packet = RXPHelpers.combineHeaderData(liveAckHeaderBytes, sendData);


        DatagramPacket sendingPacket = new DatagramPacket(packet, PACKET_SIZE, serverIpAddress, serverPort);
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
                if (receiveHeader.isAck() && receiveHeader.isFirst() && !receiveHeader.isLive() && !receiveHeader.isLast() && !receiveHeader.isDie()) {
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
    public boolean startUpload(byte[] file) {
        fileData = file;
        bytesRemaining = fileData.length;
        int packetNumber = (fileData.length / DATA_SIZE) + ((fileData.length % DATA_SIZE > 0) ? 1 : 0);
        int currPacket = 0;
        DatagramPacket sendingPacket;
        DatagramPacket receivePacket = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);

        while (currPacket < packetNumber) {
            sendingPacket = createPacket(currPacket);
            try {
                clientSocket.send(sendingPacket);
                clientSocket.receive(receivePacket);
                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

                if (!RXPHelpers.isValidPacketHeader(receivePacket)) {
//					System.out.println("Is not valid");
                    continue;
                }
                if (checkServerRequestsTermination(receivePacket)) {
                    terminateFromServer();
                }
                if (seqNum == receiveHeader.getAckNum()) {
                    continue;
                }
                if (seqNum + 1 == receiveHeader.getAckNum()) {
                    if (!receiveHeader.isLive() && receiveHeader.isAck() && !receiveHeader.isDie() && !receiveHeader.isLast()) {
                        //System.out.println("is not live");
                        seqNum = (seqNum + 1) % MAX_SEQ_NUM;
                        ackNum = receiveHeader.getSeqNum();
                        sendingPacket = createPacket(++currPacket);
                    } else if (!receiveHeader.isLive() && receiveHeader.isAck() && !receiveHeader.isDie() && receiveHeader.isLast() && !receiveHeader.isFirst()) {
                        seqNum = (seqNum + 1) % MAX_SEQ_NUM;
                        ackNum = receiveHeader.getSeqNum();
                        currPacket++;
                        return true;
                    }
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
        int bytesRemaining = fileData.length - startByteIndex * DATA_SIZE;
        int data_length = (bytesRemaining <= DATA_SIZE) ? bytesRemaining : DATA_SIZE;

        RXPHeader header = new RXPHeader();
        header.setSource(clientPort);
        header.setDestination(serverPort);
        header.setSeqNum(seqNum);
        header.setAckNum((ackNum + 1) % MAX_SEQ_NUM);
        header.setWindow(data_length);
        header.setFlags(false, false, false, false, false);
        header.setChecksum(PRECHECKSUM);
        header.setWindow(data_length);
        if (bytesRemaining <= DATA_SIZE) { // last packet
            header.setFlags(false, false, false, false, true);
        }
        byte[] data = new byte[data_length];
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


    public boolean startDownload(String fileName) {
        //Send packet over requesting from server to download it
        byte[] receiveMessage = new byte[PACKET_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveMessage, receiveMessage.length);

        // Setup Initializing Header
        RXPHeader dlHeader = new RXPHeader();
        dlHeader.setSource(clientPort);
        dlHeader.setDestination(serverPort);
        dlHeader.setSeqNum(seqNum);
        dlHeader.setAckNum((ackNum + 1) % MAX_SEQ_NUM);
        dlHeader.setFlags(true, true, false, true, false); // LIVE DIE FIRST
        dlHeader.setChecksum(PRECHECKSUM);
        dlHeader.setWindow(fileName.getBytes().length);
        byte[] data = fileName.getBytes();

        byte[] headerBytes = dlHeader.getHeaderBytes();
        byte[] sendPacket = RXPHelpers.combineHeaderData(headerBytes, data);

        DatagramPacket dlPacket = new DatagramPacket(sendPacket, sendPacket.length, serverIpAddress, serverPort);
        int currPacket = 0;
        int tries = 0;
        boolean finishedDownloading = false;
        while (!finishedDownloading) {
            try {
                clientSocket.send(dlPacket);
                clientSocket.receive(receivePacket);
//				System.out.print(currPacket + " " + seqNum + " ---- " + ackNum + " \n");
                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);
                boolean isLast = receiveHeader.isLast();
                if (!RXPHelpers.isValidPacketHeader(receivePacket)) {
//					System.out.println("CORRUPTED in " + state);
                    continue;
                }
                // Assuming valid and Acknowledged\
                if (!receiveHeader.isAck()) {
                    continue;
                }
//				boolean value = checkServerRequestsTermination(receivePacket);
//				System.out.println("Vaue of termination is: " + value);
//				if(checkServerRequestsTermination(receivePacket)){
//					terminateFromServer();
//				}
                if (receiveHeader.isLive() && receiveHeader.isAck() && receiveHeader.isFirst() && !receiveHeader.isDie() && !receiveHeader.isLast()) {
                    dlPacket = receiveDataPacket(receivePacket, currPacket, true);
                    this.fileName = fileName;
                }
                if (seqNum + 1 == receiveHeader.getAckNum()) {
                    if (receiveHeader.isLive() && receiveHeader.isAck() && receiveHeader.isFirst() && !receiveHeader.isDie() && !receiveHeader.isLast()) {
                        dlPacket = receiveDataPacket(receivePacket, currPacket, true);
                        this.fileName = fileName;
                    }
                    // Downloading files
                    else if (!receiveHeader.isLive() && !receiveHeader.isDie() && receiveHeader.isFirst() && receiveHeader.isAck()) {
                        //System.out.println("Ack");
                        currPacket++;
                        dlPacket = receiveDataPacket(receivePacket, currPacket, false);
                        finishedDownloading = isLast;
                    }
                    // Cannot find file
                    else if (receiveHeader.isDie() && receiveHeader.isFirst() && receiveHeader.isAck()) {
                        return false;
                    } else {
                        //System.out.println("SKIPP");
                    }
                } else {
                    //System.out.println("SKIPP");
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
        System.out.println("Finishes downloading");
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

        RXPHeader dataAckHeader = new RXPHeader();
        dataAckHeader.setSource(clientPort);
        dataAckHeader.setDestination(serverPort);
        dataAckHeader.setChecksum(PRECHECKSUM);

        ackNum = receiveHeader.getSeqNum();
        seqNum = (seqNum + 1) % MAX_SEQ_NUM;
        dataAckHeader.setSeqNum(seqNum);
        dataAckHeader.setAckNum((ackNum + 1) % MAX_SEQ_NUM);
        dataAckHeader.setFlags(true, false, false, true, false);    // ACK
        if (receiveHeader.isLast()) {
            dataAckHeader.setFlags(false, false, true, false, true); // ACK LAST
        }

        byte[] dataBytes = ByteBuffer.allocate(4).putInt(nextPacketNum).array();
        dataAckHeader.setWindow(dataBytes.length);
        byte[] dlAckHeaderBytes = dataAckHeader.getHeaderBytes();
        byte[] packet = RXPHelpers.combineHeaderData(dlAckHeaderBytes, dataBytes);


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
     * Once data transfer stops, performs connection teardown
     */
    public void teardown() {
        byte[] receiveMessage = new byte[PACKET_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveMessage, receiveMessage.length);

        // Setup header for the DIE packet
        RXPHeader dieHeader = new RXPHeader();
        dieHeader.setSource(clientPort);
        dieHeader.setDestination(serverPort);
        dieHeader.setSeqNum(0); //should have last seq num
        dieHeader.setAckNum(0);
        dieHeader.setFlags(false, true, false, false, false); //setting DIE flag on
        dieHeader.setChecksum(PRECHECKSUM);
        byte[] headerBytes = dieHeader.getHeaderBytes();

        DatagramPacket teardownPacket = new DatagramPacket(headerBytes, HEADER_SIZE, serverIpAddress, serverPort);

        // Sending DIE packet and receiving ACK

        int tries = 0;
        state = ClientState.DIE_WAIT_1;
        while (state != ClientState.SERVER_ACK_SENT) {
            try {
                System.out.println("tries:" + tries);
                clientSocket.send(teardownPacket);
                clientSocket.receive(receivePacket);

                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

                //System.out.println(receiveHeader.getSeqNum());
                if (!RXPHelpers.isValidPacketHeader(receiveHeader)) {
                    continue;
                }
                if (receiveHeader.isDie() && receiveHeader.isAck() && !receiveHeader.isLast()) {
                    //System.out.println("ACK from server has been sent. State is now: SERVER_ACK_SENT");
                    state = ClientState.SERVER_ACK_SENT;
                }
            } catch (SocketTimeoutException s) {
                System.out.println("Timeout, resending");
                if (tries++ >= 5) {
                    System.out.println("Unsuccessful Connection");
                    return;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        //entering the state where it waits for server to send DIE
        state = ClientState.DIE_WAIT_2;
        //System.out.println("State: DIE_WAIT_2");
        tries = 0;
        Timer timer = null;
        while (state != ClientState.TIME_WAIT || timedTaskRun) {
            try {
                clientSocket.receive(receivePacket);
                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);
                if (!RXPHelpers.isValidPacketHeader(receiveHeader)) {
                    continue;
                }
                if (receiveHeader.isDie() && receiveHeader.isLast()) {
                    sendCloseAckState(); //sends the final ACK
                    if (timer == null) {
                        timer = new Timer();
                        timer.schedule(new timedWaitTeardown(), 5 * 100); //timedwaitTeardown changes state and closes socket
                    }
                }
            } catch (SocketTimeoutException s) {
                System.out.println("Timeout, resending");
                if (tries++ >= 5) {
                    System.out.println("Unsuccessful Connection");
                    return;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        //System.out.println("exit teardown");
    }


    /**
     * This method takes a packet and creates it for the last
     * ACK packet sent in the 4-way handshake in the connection teardown
     *
     * @param receivePacket
     * @return
     */
    private void sendCloseAckState() throws IOException {
        //makes new ACK header
        RXPHeader ackHeader = new RXPHeader();
        ackHeader.setSource(clientPort);
        ackHeader.setDestination(serverPort);
        ackHeader.setChecksum(PRECHECKSUM);
        ackHeader.setSeqNum(0);
        ackHeader.setAckNum(0);

        state = ClientState.TIME_WAIT;
        ackHeader.setFlags(false, true, true, false, true); //die, ack, last flags on

        byte[] ackHeaderBytes = ackHeader.getHeaderBytes();

        DatagramPacket ackPacket = new DatagramPacket
                (
                        ackHeaderBytes,
                        HEADER_SIZE,
                        serverIpAddress,
                        serverPort
                );
        clientSocket.send(ackPacket);
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

    public boolean terminateFromServer() {
        state = ClientState.DIE_WAIT_1;
        byte[] arr = new byte[PACKET_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(arr, arr.length);

        int tries = 0;
        while (state != ClientState.CLOSED) {
            try {
                sendAckCloseState(); //send the DIE, ACK
                sendDieCloseState(); //send the DIE, LAST
                clientSocket.receive(receivePacket);

                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket); // receive the last DIE, ACK

                // Checksum validation for data received from client
                if (!RXPHelpers.isValidPacketHeader(receiveHeader)) {
                    // is not Valid Packet, send back
                    // set same flags but ack is false
                    // send same packet as received
                    // so client will resend with same everything
                    continue;
                }

                if (!receiveHeader.isLive() && receiveHeader.isDie() && !receiveHeader.isAck() && !receiveHeader.isLast() && !receiveHeader.isFirst()) {
                    continue;
                }

                if (!receiveHeader.isLive() && receiveHeader.isDie() && receiveHeader.isAck() && receiveHeader.isLast() && !receiveHeader.isFirst()) {
                    state = ClientState.CLOSED;
                    clientSocket.close();
                    System.out.println("Socket closed");
                    return true;
                }

            } catch (SocketTimeoutException s) {
                //System.out.println("Have not received DIE from Server for termination from the server");
                if (tries++ >= 5) {
                    System.out.println("Unable to terminate the server...");
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    private void sendAckCloseState() throws IOException {
        // DIE Ack Header
        RXPHeader dieAckHeader = new RXPHeader();
        dieAckHeader.setSource(clientPort);
        dieAckHeader.setDestination(serverPort);
        dieAckHeader.setChecksum(PRECHECKSUM);
        dieAckHeader.setSeqNum(0);
        dieAckHeader.setAckNum(0);
        dieAckHeader.setFlags(false, true, true, false, false); //ack, die flags ondieHeader.setWindow(DATA_SIZE);
        byte[] data = new byte[DATA_SIZE];
        dieAckHeader.setHashCode(CheckSum.getHashCode(data));
        byte[] headerBytes = dieAckHeader.getHeaderBytes();
        byte[] packet = RXPHelpers.combineHeaderData(headerBytes, data);

        DatagramPacket sendPacket = new DatagramPacket(packet, PACKET_SIZE, serverIpAddress, serverPort);

        clientSocket.send(sendPacket);

    }

    private void sendDieCloseState() throws IOException {
        // Setup header for the DIE packet
        RXPHeader dieHeader = new RXPHeader();
        dieHeader.setSource(clientPort);
        dieHeader.setDestination(serverPort);
        dieHeader.setSeqNum(seqNum); //should have last seq num
        dieHeader.setAckNum(ackNum); //?????? What should these be after the ACK
        dieHeader.setFlags(false, true, false, false, true); //setting DIE flag on
        dieHeader.setChecksum(PRECHECKSUM);
        dieHeader.setWindow(DATA_SIZE);
        byte[] data = new byte[DATA_SIZE];
        dieHeader.setHashCode(CheckSum.getHashCode(data));
        byte[] headerBytes = dieHeader.getHeaderBytes();
        byte[] packet = RXPHelpers.combineHeaderData(headerBytes, data);

        DatagramPacket terminatePacket = new DatagramPacket(packet, PACKET_SIZE, serverIpAddress, serverPort);


        state = ClientState.LAST_ACK;

        clientSocket.send(terminatePacket);
        //System.out.println("Server DIE has been sent");
    }

    public boolean checkServerRequestsTermination() {
        byte[] receiveMessage = new byte[PACKET_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveMessage, receiveMessage.length);
        try {
            clientSocket.receive(receivePacket);
            if (!RXPHelpers.isValidPacketHeader(receivePacket))    //Corrupted
            {
                //System.out.println("RECEIVED");
                return false;
            }

            RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);
            // Assuming valid and Acknowledged, server has sent DIEco
            if (receiveHeader.isDie() && !receiveHeader.isAck() && !receiveHeader.isLast() && !receiveHeader.isFirst() && !receiveHeader.isLive()) {
                return true;
            }
        } catch (IOException e) {
            //System.err.println("Unable to terminate");
        }

        return false;


    }

    public boolean checkServerRequestsTermination(DatagramPacket receivePacket) {
        if (!RXPHelpers.isValidPacketHeader(receivePacket))    //Corrupted
        {
            return false;
        }
        RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);
        // Assuming valid and Acknowledged, server has sent DIE
        return (receiveHeader.isDie() && !receiveHeader.isAck() && !receiveHeader.isLast() && !receiveHeader.isFirst()
                && !receiveHeader.isLive());

    }

    /**
     * A private class that consists of the task to close down the
     * client socket after the timed wait
     * <p>
     * This only ocurs after the client has received the last DIE from the server
     *
     * @author Eileen
     */
    private class timedWaitTeardown extends TimerTask {
        public void run() {
            state = ClientState.CLOSED;
            clientSocket.close();
            timedTaskRun = true;
        }
    }
}
