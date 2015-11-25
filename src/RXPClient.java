import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;

public class RXPClient {
    private static final int PACKET_SIZE = 512;
    private static final int DATA_SIZE = 496;
    private static final int SEQ_NUM_MAX = 65536; //2^16, or max of 2 bytes
    private static final int MAX_TRIES = 5;

    private ClientState state;

    private int clientPort;
    private int serverNetPort;
    private int serverRXPPort;
    private InetAddress clientIpAddress;
    private InetAddress serverIpAddress;
    private DatagramSocket clientSocket;

    private int seqNum = 0;
    private int ackNum = 0;

    private byte[] fileData;
    private ArrayList<byte[]> bytesReceived;
    private boolean closeRequested = false;

    public RXPClient(int clientPort, String serverIpAddress, int serverNetPort) {
        this.clientPort = clientPort;
        this.serverNetPort = serverNetPort;
        try {
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
     *@return success/failure
     */
    public boolean setupRXP() {

        try {
            clientSocket = new DatagramSocket(clientPort, clientIpAddress);
            System.out.println("Set up socket");
        } catch (SocketException e) {
            System.out.println("Couldn't setup clientSocket");
            e.printStackTrace();
        }

        byte[] receiveSetupMessage = new byte[PACKET_SIZE];
        DatagramPacket receiveSetupPacket = new DatagramPacket(receiveSetupMessage, receiveSetupMessage.length);

        // Setup Initializing Header

        RXPHeader synHeader = RXPHelpers.initHeader(clientPort, serverRXPPort, seqNum, ackNum);
        synHeader.setFlags(false, true, false, false, false, false); //setting SYN flag on
        byte[] data = new byte[DATA_SIZE];

        synHeader.setChecksum(data);
        synHeader.setSegmentLength(data.length);

        // Make the packet
        DatagramPacket setupPacket = RXPHelpers.preparePacket(serverIpAddress, serverNetPort, synHeader, data);


        // Sending SYN packet and receiving SYN ACK with challenge string

        int timeout = 5000;
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
                clientSocket.receive(receiveSetupPacket);
                RXPHeader receiveHeader = RXPHelpers.getHeader(receiveSetupPacket);
                if (!RXPHelpers.passChecksum(receiveSetupPacket)) {
                    System.out.println("Dropping invalid packet");
                    continue;
                }

                if (receiveHeader.isACK() && receiveHeader.isSYN()) {
                    //System.out.println("Received challenge");
                    break;
                }
            } catch (SocketTimeoutException s) {
                System.out.println("Timeout: resend");
                if (tries++ >= MAX_TRIES) {
                    System.out.println("Unsuccessful connect");
                    return false;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }

        // Setup hash Header
        RXPHeader hashHeader = RXPHelpers.initHeader(clientPort, serverRXPPort, seqNum, ackNum);
        hashHeader.setFlags(true, false, false, false, false, false); //setting ACK flag on
        byte[] challenge = RXPHelpers.getData(receiveSetupPacket);

        //System.out.printf("Working with challenge:%s\n", RXPHelpers.btyeArrToStr(challenge));

        byte[] datahash = RXPHelpers.getHash(challenge);

        if(datahash != null) {
            hashHeader.setSegmentLength(datahash.length);
        }

        //System.out.printf("Setting up hash of %s\n", RXPHelpers.btyeArrToStr(datahash));
        hashHeader.setChecksum(datahash);

        // Make the packet
        DatagramPacket hashPacket = RXPHelpers.preparePacket(serverIpAddress, serverNetPort, hashHeader, datahash);


        // Sending ACK packet with hash and receiving ACK for establishment
        tries = 0;
        state = ClientState.HASH_SENT;
        while (state != ClientState.ESTABLISHED) {
            try {
                clientSocket.send(hashPacket);
                clientSocket.receive(receiveSetupPacket);
                RXPHeader receiveHeader = RXPHelpers.getHeader(receiveSetupPacket);
                if (!RXPHelpers.passChecksum(receiveSetupPacket)) {
                    System.out.println("Dropping corrupted packets");
                    continue;
                }

                // Assuming valid and ACK
                if (receiveHeader.isACK() && !receiveHeader.isFIN()) {
                    System.out.println("Established connection");
                    state = ClientState.ESTABLISHED;
                    serverRXPPort = receiveHeader.getSource();
                    break;
                }
            } catch (SocketTimeoutException s) {
                System.out.println("Timeout: resend");
                if (tries++ >= MAX_TRIES) {
                    System.out.println("Connection failed");
                    return false;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }

    /**
     * sends name to server to prep server to receive upload
     * @param fileName of file client is going to send
     * @return success/failure
     */
    public boolean sendFileNameUpload(String fileName) {
        RXPHeader nameHeader = RXPHelpers.initHeader(clientPort, serverRXPPort, 0, 0);
        nameHeader.setFlags(false, false, false, false, true, false); // POST.
        byte[] sendData = fileName.getBytes(Charset.forName("UTF-8"));
        nameHeader.setSegmentLength(sendData.length);
        nameHeader.setChecksum(sendData);
        // Make the packet
        DatagramPacket namePacket = RXPHelpers.preparePacket(serverIpAddress, serverNetPort, nameHeader, sendData);
        DatagramPacket receivedPacket = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);
        System.out.printf("Sending filename: %s", fileName);
        int tries = 0;
        while (true) {
            try {
                clientSocket.send(namePacket);
                clientSocket.receive(receivedPacket);

                RXPHeader headerResponse = RXPHelpers.getHeader(receivedPacket);

                if (!RXPHelpers.passChecksum(receivedPacket)) {
                    System.out.println("Dropping corrupted packet");
                    continue;
                }
                if (!RXPHelpers.isValidPorts(receivedPacket, clientPort, serverRXPPort)) {
                    System.out.println("Dropping packet of incorrect ports");
                    continue;
                }
                if (headerResponse.isFIN()) {    //server wants to terminate
                    closeRequested = true;
                    continue;
                }

                if (headerResponse.isACK() && headerResponse.isPOST() && !headerResponse.isFIN()) {
                    //System.out.println("Server acknowledged the filename.");
                    break;
                }
            } catch (SocketTimeoutException es) {
                System.out.println("Timeout: resend");
                if (tries++ >= MAX_TRIES) {
                    System.out.println("Connection failed");
                    return false;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        if (closeRequested) serverDisconnect();
        return true;
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

                if (!RXPHelpers.passChecksum(receivePacket)) {
                    System.out.println("Dropping invalid packet");
                    continue;
                }
                if (!RXPHelpers.isValidPorts(receivePacket, clientPort, serverRXPPort)) {
                    System.out.println("Dropping packet of incorrect ports");
                    continue;
                }
                if (receiveHeader.isFIN()) {    //server wants to terminate
                    closeRequested = true;
                    continue;
                }

                if (receiveHeader.isACK() && receiveHeader.isLAST()) {
                    System.out.println("Finished Uploading!");
                    break;
                }

                if (seqNum == receiveHeader.getAckNum()) { //getting ack for previous packet
                    continue;
                }
                if (seqNum + 1 == receiveHeader.getAckNum()) {  //got the ack for this packet
                    seqNum = (seqNum + 1) % SEQ_NUM_MAX;
                    ackNum = receiveHeader.getSeqNum();
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
        seqNum = 0;
        ackNum = 0;
        if (closeRequested) serverDisconnect();
        return true;
    }

    /**
    * creates packets of indexed bytes of file
     */
    private DatagramPacket createDataPacket(int initByteIndex) {
        System.out.printf("Creating data packet # %d \n", initByteIndex);
        // Setup header for the data packet
        int byteLocation = initByteIndex * DATA_SIZE;
        int bytesRemaining = fileData.length - byteLocation;

        RXPHeader header = RXPHelpers.initHeader(clientPort, serverRXPPort, seqNum, (ackNum + 1) % SEQ_NUM_MAX);

        int data_length;
        if (bytesRemaining <= DATA_SIZE) { //utilized for last segment of data
            data_length = bytesRemaining;
            header.setFlags(false, false, false, false, false, true); // LAST flag
            //System.out.println("Creating LAST packet");
        } else {
            data_length = DATA_SIZE;
        }
        header.setSegmentLength(data_length);
        byte[] data = new byte[data_length];
        System.arraycopy(fileData, initByteIndex * DATA_SIZE, data, 0, data_length);
        header.setChecksum(data);

        // Make the packet
        return RXPHelpers.preparePacket(serverIpAddress, serverNetPort, header, data);
    }

    /**
     * request download of specified filename and carry out download
     * GET
     */
    public boolean download(String fileName) {
        //Send GET packet with filename
        byte[] receiveMessage = new byte[PACKET_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveMessage, receiveMessage.length);
        RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

        // Setup Initializing Header

        RXPHeader requestHeader = RXPHelpers.initHeader(clientPort, serverRXPPort, seqNum, ackNum);
        requestHeader.setFlags(false, false, false, true, false, false); // GET
        requestHeader.setSegmentLength(fileName.getBytes().length);
        byte[] data = fileName.getBytes();
        requestHeader.setChecksum(data);

        // Make the packet
        DatagramPacket requestPacket = RXPHelpers.preparePacket(serverIpAddress, serverNetPort, requestHeader, data);

        int currPacket = 0;
        int tries = 0;
        boolean finDL = false;
        bytesReceived = new ArrayList<>();
        while (true) {
            try {
                System.out.println("Sending: " + seqNum + ", " + ackNum);

                clientSocket.send(requestPacket);

                clientSocket.receive(receivePacket);
                receiveHeader = RXPHelpers.getHeader(receivePacket);

                if (!RXPHelpers.passChecksum(receivePacket)) {
                    System.out.println("Dropping corrupted packet");
                    continue;
                }
                if (!RXPHelpers.isValidPorts(receivePacket, clientPort, serverRXPPort)) {
                    System.out.println("Dropping packet of incorrect ports");
                    continue;
                }
                if (receiveHeader.isFIN()) {    //server wants to terminate
                    closeRequested = true;
                }

                if (receiveHeader.isFIN() && closeRequested) {
                    break;
                }
//				System.out.print(currPacket + " " + seqNum + " ---- " + ackNum + " \n");

                //System.out.println("Receiving: " + receiveHeader.getSeqNum() + ", " + receiveHeader.getAckNum());
                //System.out.println("Global: " + seqNum + ", " + ackNum);

//                boolean isLast = receiveHeader.isLAST();
                // Assuming valid and acked
                if (receiveHeader.isACK()) {
                    System.out.println("Is ACK, Skip");
                    continue; //got ack packet for some reason, this isn't our desired data
                }

                if (ackNum == receiveHeader.getSeqNum()) {
                    //System.out.println("Right packet");
                    requestPacket = receiveDataPacket(receivePacket, currPacket);
                    currPacket++;
                }

                if (receiveHeader.isLAST()) {
                    finDL = true;
                }
            } catch (SocketTimeoutException s) {
                // Once we send the last packet, we wait for it to timeout. If we receive another LAST, then the LASTACK we sent was lost
                if (finDL) {
                    break;
                }

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
        seqNum = 0;
        ackNum = 0;
        bytesReceived = new ArrayList<byte[]>();
        if (closeRequested) serverDisconnect();
        return resultOfAssemble;
    }

    /**
     * take received packets into byte array collection and prepare ack packet
     */
    private DatagramPacket receiveDataPacket(DatagramPacket receivePacket, int nextPacketNum) {
        RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

        // extracts and adds data to ArrayList of byte[]s
        byte[] data = RXPHelpers.getData(receivePacket);
        bytesReceived.add(data);

        ackNum = (receiveHeader.getSeqNum() + 1) % SEQ_NUM_MAX;
        seqNum = receiveHeader.getAckNum();
        RXPHeader ackHeader = RXPHelpers.initHeader(clientPort, serverRXPPort, seqNum, ackNum);

        if (receiveHeader.isLAST()) {
            ackHeader.setFlags(true, false, false, false, false, true); // ACK LAST
        } else {
            ackHeader.setFlags(true, false, false, false, false, false);    // ACK
        }

        byte[] dataArray = ByteBuffer.allocate(4).putInt(nextPacketNum).array();
        ackHeader.setChecksum(dataArray);
        ackHeader.setSegmentLength(dataArray.length);
        return RXPHelpers.preparePacket(serverIpAddress, serverNetPort, ackHeader, dataArray);
    }

    /**
     * Disconnect connection from client
     */
    public void clientDisconnect() {
        System.out.println("Beginning disconnection from client side");
        RXPHeader finHeader = RXPHelpers.initHeader(clientPort, serverRXPPort, 0, 0);
        finHeader.setFlags(false, false, true, false, false, false); // FIN.
        byte[] sendData = new byte[DATA_SIZE];
        finHeader.setSegmentLength(sendData.length);
        finHeader.setChecksum(sendData);
        // Make the packet
        DatagramPacket sendingPacket = RXPHelpers.preparePacket(serverIpAddress, serverNetPort, finHeader, sendData);
        DatagramPacket receivePacket = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);

        // send fin packet to server
        //if we recieve a FIN ACK, we're done and we can close.
        int tries = 0;
        state = ClientState.CLOSE_REQ;
        while (true) {
            try {
                clientSocket.send(sendingPacket);
                clientSocket.receive(receivePacket);

                RXPHeader receiveHeader = RXPHelpers.getHeader(receivePacket);

                if (!RXPHelpers.passChecksum(receivePacket)) {
                    System.out.println("Dropping corrupted packet");
                    continue;
                }
                if (!RXPHelpers.isValidPorts(receivePacket, clientPort, serverRXPPort)) {
                    System.out.println("Dropping packet of incorrect ports");
                    continue;
                }

                //check for fin ack
                if (receiveHeader.isACK() && receiveHeader.isFIN()) {
                    System.out.println("Server acknowledged close with FIN ACK");
                    state = ClientState.CLOSED;
                    break;
                }
            } catch (SocketTimeoutException es) {
                //timeout, send fin packet again
                System.out.println("Timeout, resending");
                if (tries++ >= 5) {
                    System.out.println("Unsuccessful request.");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        //System.exit(0);
    }

    /**
     *
     */
    private void serverDisconnect() {
        state = ClientState.CLOSE_WAIT;
        System.out.println("Beginning disconnection from client side");
        //while loop:
        // send fin packet to server
        //receive packet
        //check for fin ack
        //timeout, send fin packet again
        RXPHeader finHeader = RXPHelpers.initHeader(clientPort, serverRXPPort, 0, 0);
        finHeader.setFlags(true, false, true, false, false, false); // FIN. ACK
        byte[] sendData = new byte[DATA_SIZE];
        finHeader.setSegmentLength(sendData.length);
        finHeader.setChecksum(sendData);
        // Make the packet
        DatagramPacket finackPacket = RXPHelpers.preparePacket(serverIpAddress, serverNetPort, finHeader, sendData);

        DatagramPacket packetResponse = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);
        while (true) {
            try {
                clientSocket.send(finackPacket);
                clientSocket.receive(packetResponse);
                if (!RXPHelpers.passChecksum(packetResponse)) {
                    System.out.println("Dropping corrupted packet");
                    continue;
                }
                if (!RXPHelpers.isValidPorts(packetResponse, clientPort, serverRXPPort)) {
                    System.out.println("Dropping packet of incorrect ports");
                }
            } catch (SocketTimeoutException es) {
                System.out.println("Timeout into close.");
                break;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

//        state = ClientState.CLOSED;
        System.exit(1);
    }

    /**
     * @return client state
     */
    public ClientState getClientState() {
        return state;
    }
}