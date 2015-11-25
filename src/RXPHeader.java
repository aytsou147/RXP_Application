public class RXPHeader {
    private static final int HEADERLENGTH = 16;

    //Header offsets
    private static final int SRC = 0;
    private static final int DST = 2;
    private static final int SEQ = 4;
    private static final int ACK = 6;
    private static final int SEGLEN = 8;
    private static final int FLAG = 10;
    private static final int CHECKSUM = 12; //is four bytes

    @SuppressWarnings("CanBeFinal")
    private byte[] header;

    public RXPHeader() {
        this(new byte[HEADERLENGTH]);
    }

    public RXPHeader(byte[] headerArr) {
        if (headerArr.length != HEADERLENGTH) {
            header = new byte[HEADERLENGTH];
        } else {
            header = headerArr;
        }
    }

    public int getSource() {
        return header[SRC] << 8 & 0xFF00 | header[SRC + 1] & 0x00FF;
    }

    public void setSource(int srcPort) {
        header[SRC] = (byte) ((srcPort & 0xFF00) >> 8);
        header[SRC + 1] = (byte) (srcPort & 0x00FF);
    }

    public int getDestination() {
        return header[DST] << 8 & 0xFF00 | header[DST + 1] & 0x00FF;
    }

    public void setDestination(int dstPort) {
        header[DST] = (byte) ((dstPort & 0xFF00) >> 8);
        header[DST + 1] = (byte) (dstPort & 0x00FF);
    }

    public int getSeqNum() {
        return header[SEQ] << 8 & 0xFF00 |
                header[SEQ + 1] & 0x00FF;
    }

    public void setSeqNum(int seqNum) {
        header[SEQ] = (byte) ((seqNum & 0xFF00) >> 8);
        header[SEQ + 1] = (byte) (seqNum & 0x00FF);
    }

    public int getAckNum() {
        return header[ACK] << 8 & 0xFF00 |
                header[ACK + 1] & 0x00FF;
    }

    public void setAckNum(int ackNum) {
        header[ACK] = (byte) ((ackNum & 0x0000FF00) >> 8);
        header[ACK + 1] = (byte) (ackNum & 0x000000FF);
    }

    public int getSegmentLength() {
        return header[SEGLEN] << 8 & 0xFF00 | header[SEGLEN + 1] & 0x00FF;
    }

    public void setSegmentLength(int segmentLength) {
        header[SEGLEN] = (byte) ((segmentLength & 0xFF00) >> 8);
        header[SEGLEN + 1] = (byte) (segmentLength & 0x00FF);
    }

    public void setFlags(boolean ACK, boolean SYN, boolean FIN, boolean GET, boolean POST, boolean LAST) {
        byte flag = 0;
        if (ACK) flag |= (byte) (1 << 7);
        if (SYN) flag |= (byte) (1 << 6);
        if (FIN) flag |= (byte) (1 << 5);
        if (GET) flag |= (byte) (1 << 4);
        if (POST) flag |= (byte) (1 << 3);
        if (LAST) flag |= (byte) (1 << 2);

        header[FLAG] = flag;
    }

    public boolean isACK() {
        return (header[FLAG] & 0b10000000) != 0;
    }

    public boolean isSYN() {
        return (header[FLAG] & 0b01000000) != 0;
    }

    public boolean isFIN() {
        return (header[FLAG] & 0b00100000) != 0;
    }

    public boolean isGET() {
        return (header[FLAG] & 0b00010000) != 0;
    }

    public boolean isPOST() {
        return (header[FLAG] & 0b00001000) != 0;
    }

    public boolean isLAST() {
        return (header[FLAG] & 0b00000100) != 0;
    }

    public int getChecksum() {
        return (int) header[CHECKSUM] << 24 & 0xFF000000 |
                header[CHECKSUM + 1] << 16 & 0x00FF0000 |
                header[CHECKSUM + 2] << 8 & 0x0000FF00 |
                header[CHECKSUM + 3] & 0x000000FF;
    }

    public void setChecksum(byte[] data) {
        int checksum = RXPHelpers.makeChecksum(data);
        header[CHECKSUM] = (byte) (checksum >> 24);
        header[CHECKSUM + 1] = (byte) (checksum >> 16);
        header[CHECKSUM + 2] = (byte) (checksum >> 8);
        header[CHECKSUM + 3] = (byte) (checksum);
    }

    public byte[] getHeaderBytes() {
        return header;
    }
}
