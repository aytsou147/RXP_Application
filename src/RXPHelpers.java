import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Checksum;


public class RXPHelpers {

    private static final int PACKET_SIZE = 512;
    private static final int DATA_SIZE = 496;
    private static final int HEADER_SIZE = 16;

    /**
     * Sets up the header using passed-in information, EXCEPT for Flags.
     *
     * @param srcPort
     * @param destPort
     * @param seqNum
     * @param ackNum
     * @return header
     */
    public static RXPHeader initHeader(int srcPort, int destPort, int seqNum, int ackNum) {
        RXPHeader header = new RXPHeader();
        header.setSource(srcPort);
        header.setDestination(destPort);
        header.setSeqNum(seqNum);
        header.setAckNum(ackNum);
        header.setSegmentLength(DATA_SIZE);
        return header;
    }

    /**
     * Prepares a packet by combining the passed-in header and data and putting it in a packet
     * Data passed-in should already be ready for combining
     *
     * @param destIP
     * @param destPort
     * @param header
     * @param data
     * @return sendPacket
     */
    public static DatagramPacket preparePacket(InetAddress destIP, int destPort, RXPHeader header, byte[] data) {
        byte[] packetBytes = new byte[PACKET_SIZE];

        System.arraycopy(header.getHeaderBytes(), 0, packetBytes, 0, HEADER_SIZE);
        System.arraycopy(data, 0, packetBytes, HEADER_SIZE, data.length);

        return new DatagramPacket(packetBytes, PACKET_SIZE, destIP, destPort);
    }

    public static byte[] getData(DatagramPacket receivePacket) {
        RXPHeader receiveHeader = getHeader(receivePacket);
        int data_length = receiveHeader.getSegmentLength();
        byte[] extractedData = new byte[data_length];
        byte[] packet = receivePacket.getData();

        System.arraycopy(packet, HEADER_SIZE, extractedData, 0, data_length);

        return extractedData;
    }

    public static boolean passChecksum(DatagramPacket packet) {
        RXPHeader header = getHeader(packet);
        int headerChecksum = header.getChecksum();
        byte[] data = getData(packet);
        int ourChecksum = makeChecksum(data);
        //System.out.printf("Comparing received checksum:\n %d to calculated checksum:\n %d\n", headerChecksum, ourChecksum);
        return headerChecksum == ourChecksum;
    }

    public static boolean isValidPorts(DatagramPacket packet, int dstport, int srcport) {
        RXPHeader header = getHeader(packet);
        return header.getSource() == srcport && header.getDestination() == dstport;
    }

    public static RXPHeader getHeader(DatagramPacket receivePacket) {
        return new RXPHeader(Arrays.copyOfRange(receivePacket.getData(), 0, HEADER_SIZE));
    }

    public static byte[] getFileBytes(String pathName) {
        Path path = Paths.get(pathName);
        byte[] data = null;
        try {
            data = Files.readAllBytes(path);
        } catch (NoSuchFileException e1) {
            System.out.println("File doesn't exist");
        } catch (IOException e) {
            System.err.println("File could not be read");
            e.printStackTrace();
        }
        return data;
    }

    public static byte[] getHash(byte[] data) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
            md.update(data);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean assembleFile(ArrayList<byte[]> bytesReceived, String fileName) {
        fileName = "downloaded_" + fileName;

        int bufferSize = bytesReceived.size();
        int lastByteArrayLength = bytesReceived.get(bufferSize - 1).length;    // Length of last data
        int fileSize = (bufferSize - 1) * DATA_SIZE + lastByteArrayLength;    // number of bytes in file

        byte[] fileData = new byte[fileSize];
        for (int i = 0; i < bufferSize - 1; i++) {
            System.arraycopy(bytesReceived.get(i), 0, fileData, i * DATA_SIZE, DATA_SIZE);
        }

        // Copy last data
        System.arraycopy(bytesReceived.get(bufferSize - 1), 0, fileData, (bufferSize - 1) * DATA_SIZE, lastByteArrayLength);

        String fileDir = System.getProperty("user.dir") + "/" + fileName;

        File file = new File(fileDir);
        try (FileOutputStream fout = new FileOutputStream(file)) {
            // if file doesn't exists, then create it
            if (!file.exists()) {
                file.createNewFile();
            }
            fout.write(fileData);
            fout.flush();
            fout.close();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static int makeChecksum(byte[] data) {
        Checksum result = new CRC32();
        result.update(data, 0, data.length);
        //System.out.printf("Made checksum: %d\n", (int) result.getValue());
        return (int) (result.getValue() >>> 32) + (int) result.getValue();
//        return (int) result.getValue();
    }

    public static String btyeArrToStr(byte[] bytes) {
        try {
            return new String(bytes, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return "";
        }
    }
}
