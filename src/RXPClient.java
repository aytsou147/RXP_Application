import java.io.IOException;
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
        synHeader.setWindow(data.length);

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
                if (!RXPHelpers.isValidPacketHeader(receiveSetupPacket)) {
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
        byte[] challenge = RXPHelpers.extractData(receiveSetupPacket);
        byte[] datahash = RXPHelpers.getHash(challenge);
        hashHeader.setWindow(datahash.length);
        hashHeader.setChecksum(datahash);
        DatagramPacket hashPacket = RXPHelpers.preparePacket(serverIpAddress, serverNetPort, hashHeader, datahash);


        // Sending ACK packet with hash and receiving ACK for establishment
        tries = 0;
        state = ClientState.HASH_SENT;
        while (state != ClientState.ESTABLISHED) {
            try {
                clientSocket.send(hashPacket);
                clientSocket.receive(receiveSetupPacket);
                RXPHeader receiveHeader = RXPHelpers.getHeader(receiveSetupPacket);
                if (!RXPHelpers.isValidPacketHeader(receiveSetupPacket)) {
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
        nameHeader.setWindow(sendData.length);
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

                if (!RXPHelpers.isValidPacketHeader(receivedPacket)) {
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
     * @param file byte array of the file that will be split into pieces for sending
     * @return success/failure
     */
    public boolean upload(byte[] file) {
        fileData = file;
        int totalPackets = (fileData.length / DATA_SIZE);
        if (fileData.length % DATA_SIZE > 0) totalPackets += 1; //1 extra packet if there's leftover data

        int packetIndex = 0;
        DatagramPacket uploaderPacket;
        DatagramPacket packetResponse = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);

        while (packetIndex < totalPackets) {
            uploaderPacket = formDataPacket(packetIndex);
            try {
                clientSocket.send(uploaderPacket);
                clientSocket.receive(packetResponse);
                RXPHeader headerResponse = RXPHelpers.getHeader(packetResponse);

                if (!RXPHelpers.isValidPacketHeader(packetResponse)) {
                    System.out.println("Dropping invalid packet");
                    continue;
                }
                if (!RXPHelpers.isValidPorts(packetResponse, clientPort, serverRXPPort)) {
                    System.out.println("Dropping packet of incorrect ports");
                    continue;
                }
                if (headerResponse.isFIN()) {    //server wants to terminate
                    closeRequested = true;
                    continue;
                }

                if (headerResponse.isACK() && headerResponse.isLAST()) {
                    System.out.println("Finished Uploading!");
                    break;
                }

                if (seqNum == headerResponse.getAckNum()) { //getting ack for previous packet
                    continue; //avoid duplication
                }
                if (seqNum + 1 == headerResponse.getAckNum()) {  //got the ack for this packet
                    seqNum = (seqNum + 1) % SEQ_NUM_MAX;
                    ackNum = headerResponse.getSeqNum();
                    packetIndex++;
                }
            } catch (SocketTimeoutException s) {
                System.out.println("Timeout: resend");
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        fileData = null;
        if (closeRequested) serverDisconnect();
        return true;
    }

    /**
    * creates packets of indexed bytes of file
     * @param initByteIndex tells function what relative index to make packet for
     * @return DatagramPacket with segmented data at specified index
     */
    private DatagramPacket formDataPacket(int initByteIndex) {
        System.out.printf("Creating data packet # %d \n", initByteIndex);
        // Setup header for the data packet
        int byteIndex = initByteIndex * DATA_SIZE;
        int bytesRemaining = fileData.length - byteIndex;

        RXPHeader header = RXPHelpers.initHeader(clientPort, serverRXPPort, seqNum, (ackNum + 1) % SEQ_NUM_MAX);

        int data_length = DATA_SIZE;
        if (bytesRemaining <= DATA_SIZE) { //utilized for last segment of data
            data_length = bytesRemaining;
            header.setFlags(false, false, false, false, false, true); // LAST flag
            //System.out.println("Creating LAST packet");
        }
        header.setWindow(data_length);
        byte[] data = new byte[data_length];
        System.arraycopy(fileData, initByteIndex * DATA_SIZE, data, 0, data_length);
        header.setChecksum(data);

        // Make the packet
        return RXPHelpers.preparePacket(serverIpAddress, serverNetPort, header, data);
    }

    /**
     * request download of specified filename and carry out download
     * @param fileName of file to request
     * @return success/failure
     */
    public boolean download(String fileName) {
        //Send GET packet with filename
        byte[] receiveMessage = new byte[PACKET_SIZE];
        DatagramPacket receivePacket = new DatagramPacket(receiveMessage, receiveMessage.length);
        RXPHeader headerResponse;

        // Setup Initializing Header

        RXPHeader requestHeader = RXPHelpers.initHeader(clientPort, serverRXPPort, seqNum, ackNum);
        requestHeader.setFlags(false, false, false, true, false, false); // GET
        requestHeader.setWindow(fileName.getBytes().length);
        byte[] data = fileName.getBytes();
        requestHeader.setChecksum(data);

        // Make the packet
        DatagramPacket requestPacket = RXPHelpers.preparePacket(serverIpAddress, serverNetPort, requestHeader, data);

        int currPacket = 0;
        int tries = 0;
        boolean finDL = false;
        bytesReceived = new ArrayList<byte[]>();
        while (true) {
            try {
                System.out.println("Sending: " + seqNum + ", " + ackNum);

                clientSocket.send(requestPacket);

                clientSocket.receive(receivePacket);
                headerResponse = RXPHelpers.getHeader(receivePacket);
                if (!RXPHelpers.isValidPacketHeader(receivePacket)) {
                    System.out.println("Dropping corrupted packet");
                    continue;
                }
                if (!RXPHelpers.isValidPorts(receivePacket, clientPort, serverRXPPort)) {
                    System.out.println("Dropping packet of incorrect ports");
                    continue;
                }
                if (headerResponse.isFIN()) {    //server wants to terminate
                    closeRequested = true;
                }

                if (headerResponse.isFIN() && closeRequested) {
                    break;
                }
//				System.out.print(currPacket + " " + seqNum + " ---- " + ackNum + " \n");

                //System.out.println("Receiving: " + receiveHeader.getSeqNum() + ", " + receiveHeader.getAckNum());
                //System.out.println("Global: " + seqNum + ", " + ackNum);

                if (headerResponse.isACK()) {
                    System.out.println("Is ACK, Skip");
                    continue; //got ack packet for some reason, this isn't our desired data
                }

                if (ackNum == headerResponse.getSeqNum()) {
                    //System.out.println("Right packet");
                    requestPacket = receiveData(receivePacket, currPacket);
                    currPacket++;
                }

                if (headerResponse.isLAST()) {
                    finDL = true;
                }
            } catch (SocketTimeoutException s) {
                // Once we send the last packet, we wait for it to timeout. If we receive another LAST, then the LASTACK we sent was lost
                if (finDL) {
                    break;
                }

                System.out.println("Timeout, resending..");
                if (tries++ >= MAX_TRIES) {
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
        if (closeRequested) serverDisconnect();
        return resultOfAssemble;
    }

    /**
     * take received packets into byte array collection and prepare ack packet
     * @param receivePacket packet data to analyze
     * @param receivePacket the value we want to ACK back for next packet
     * @return DatagramPacket of packet with ack for this data
     */
    private DatagramPacket receiveData(DatagramPacket receivePacket, int nextPacketNum) {
        RXPHeader headerResponse = RXPHelpers.getHeader(receivePacket);

        // extracts and adds data to ArrayList of byte[]s
        byte[] data = RXPHelpers.extractData(receivePacket);
        bytesReceived.add(data);

        ackNum = (headerResponse.getSeqNum() + 1) % SEQ_NUM_MAX;
        seqNum = headerResponse.getAckNum();
        RXPHeader ackHeader = RXPHelpers.initHeader(clientPort, serverRXPPort, seqNum, ackNum);

        if (headerResponse.isLAST()) {
            ackHeader.setFlags(true, false, false, false, false, true); // ACK LAST
        } else {
            ackHeader.setFlags(true, false, false, false, false, false);    // ACK
        }

        byte[] dataArray = ByteBuffer.allocate(4).putInt(nextPacketNum).array();
        ackHeader.setChecksum(dataArray);
        ackHeader.setWindow(dataArray.length);
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
        finHeader.setWindow(sendData.length);
        finHeader.setChecksum(sendData);
        // Make the packet
        DatagramPacket finPacket = RXPHelpers.preparePacket(serverIpAddress, serverNetPort, finHeader, sendData);
        DatagramPacket packetResponse = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);

        // send fin packet to server
        //if we recieve a FIN ACK, we're done and we can close.
        int tries = 0;
        state = ClientState.CLOSE_REQ;
        while (true) {
            try {
                clientSocket.send(finPacket);
                clientSocket.receive(packetResponse);

                RXPHeader headerResponse = RXPHelpers.getHeader(packetResponse);

                if (!RXPHelpers.isValidPacketHeader(packetResponse)) {
                    System.out.println("Dropping corrupted packet");
                    continue;
                }
                if (!RXPHelpers.isValidPorts(packetResponse, clientPort, serverRXPPort)) {
                    System.out.println("Dropping packet of incorrect ports");
                    continue;
                }

                //check for fin ack
                if (headerResponse.isACK() && headerResponse.isFIN()) {
                    System.out.println("Server acknowledged close with FIN ACK");
                    state = ClientState.CLOSED;
                    break;
                }
            } catch (SocketTimeoutException es) {
                //timeout, send fin packet again
                System.out.println("Timeout, resending");
                if (tries++ >= MAX_TRIES) {
                    System.out.println("Unsuccessful request.");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * respond to server disconnection request
     */
    private void serverDisconnect() {
        state = ClientState.CLOSE_WAIT;
        System.out.println("Beginning disconnection from client side");
        RXPHeader finHeader = RXPHelpers.initHeader(clientPort, serverRXPPort, 0, 0);
        finHeader.setFlags(true, false, true, false, false, false); // FIN. ACK
        byte[] sendData = new byte[DATA_SIZE];
        finHeader.setWindow(sendData.length);
        finHeader.setChecksum(sendData);
        // Make the packet
        DatagramPacket finackPacket = RXPHelpers.preparePacket(serverIpAddress, serverNetPort, finHeader, sendData);

        DatagramPacket packetResponse = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);
        while (true) {
            try {
                clientSocket.send(finackPacket);
                clientSocket.receive(packetResponse);
                if (!RXPHelpers.isValidPacketHeader(packetResponse)) {
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