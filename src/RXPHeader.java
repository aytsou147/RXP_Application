public class RXPHeader {
    static final int HEADERLENGTH = 16;

    /*
     * Header Field offsets
     */
    static final int SRC = 0;
    static final int DST = 2;
    static final int SEQ = 4;
    static final int ACK = 6;
    static final int WIN = 8;
    static final int FLAG = 10;
    static final int CHKSUM = 12;

    private byte[] header;

    public RXPHeader() {
        this(new byte[16]);
    }

    public RXPHeader(byte[] headerArray) {
        if (headerArray.length != 16) {
            header = new byte[16];
        } else {
            header = headerArray;
        }
    }

    public int getSource() {
        return (int) (header[SRC] << 8 & 0xFF00 | header[SRC + 1] & 0x00FF);
    }

    public void setSource(int portNumber) {
        header[SRC] = (byte) ((portNumber & 0xFF00) >> 8);
        header[SRC + 1] = (byte) (portNumber & 0x00FF);
    }

    public int getDestination() {
        return (int) (header[DST] << 8 & 0xFF00 | header[DST + 1] & 0x00FF);
    }

    public void setDestination(int portNumber) {
        header[DST] = (byte) ((portNumber & 0xFF00) >> 8);
        header[DST + 1] = (byte) (portNumber & 0x00FF);
    }

    public int getSeqNum() {
        return (int) (header[SEQ] << 8 & 0xFF00 |
                header[SEQ + 1] & 0x00FF);
    }

    public void setSeqNum(int sequenceNumber) {
        header[SEQ] = (byte) ((sequenceNumber & 0xFF00) >> 8);
        header[SEQ + 1] = (byte) (sequenceNumber & 0x00FF);
    }

    public int getAckNum() {
        return (int) (header[ACK] << 8 & 0xFF00 |
                header[ACK + 1] & 0x00FF);
    }

    public void setAckNum(int ackNumber) {
        header[ACK] = (byte) ((ackNumber & 0x0000FF00) >> 8);
        header[ACK + 1] = (byte) (ackNumber & 0x000000FF);
    }

    public int getWindow() {
        return (int) (header[WIN] << 8 & 0xFF00 | header[WIN + 1] & 0x00FF);
    }

    public void setWindow(int windowSize) {
        header[WIN] = (byte) ((windowSize & 0xFF00) >> 8);
        header[WIN + 1] = (byte) (windowSize & 0x00FF);
    }

    public void setFlags(boolean ACK, boolean SYN, boolean FIN, boolean GET, boolean POST) {
        byte flag = 0;
        if (ACK) flag |= (byte) (1 << 7);
        if (SYN) flag |= (byte) (1 << 6);
        if (FIN) flag |= (byte) (1 << 5);
        if (GET) flag |= (byte) (1 << 4);
        if (POST) flag |= (byte) (1 << 3);

        header[FLAG] = flag;
    }

    public boolean isACK() {
        return ((header[FLAG] & 0b10000000) != 0);
    }

    public boolean isSYN() {
        return ((header[FLAG] & 0b01000000) != 0);
    }

    public boolean isFIN() {
        return ((header[FLAG] & 0b00100000) != 0);
    }

    public boolean isGET() {
        return ((header[FLAG] & 0b00010000) != 0);
    }

    public boolean isPOST() {
        return ((header[FLAG] & 0b00001000) != 0);
    }

    public int getChecksum() {
        return (int) (header[CHKSUM] << 24 & 0xFF000000 |
                header[CHKSUM + 1] << 16 & 0x00FF0000 |
                header[CHKSUM + 2] << 8 & 0x0000FF00 |
                header[CHKSUM + 3] & 0x000000FF);
    }

    public void setChecksum(int checksum) {
        header[CHKSUM] = (byte) ((checksum & 0xFF000000) >> 24);
        header[CHKSUM + 1] = (byte) ((checksum & 0x00FF0000) >> 16);
        header[CHKSUM + 2] = (byte) ((checksum & 0x0000FF00) >> 8);
        header[CHKSUM + 3] = (byte) (checksum & 0x000000FF);
    }


    public byte[] getHeaderBytes() {
        return header;
    }
}
