import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;


public class RXPHelpers {

    private static final int PACKET_SIZE = 512;
    private static final int DATA_SIZE = 496;
    private static final int HEADER_SIZE = 16;


    public static byte[] combineHeaderData(byte[] headerBytes, byte[] data) {

        byte[] packetBytes = new byte[PACKET_SIZE];
        System.arraycopy(headerBytes, 0, packetBytes, 0, HEADER_SIZE);
        System.arraycopy(data, 0, packetBytes, HEADER_SIZE, data.length);

        return packetBytes;
    }

    public static byte[] extractData(DatagramPacket receivePacket) {
        RXPHeader receiveHeader = getHeader(receivePacket);
        int data_length = receiveHeader.getWindow();
        //TODO what the heck is going on here
        byte[] extractedData = new byte[data_length];
        byte[] packet = receivePacket.getData();

        System.arraycopy(packet, HEADER_SIZE, extractedData, 0, data_length);

        return extractedData;
    }


    public static boolean isValidPacketHeader(DatagramPacket packet) {
        RXPHeader header = getHeader(packet);
        int headerChecksum = header.getChecksum();
        byte[] data = extractData(packet);
        //TODO make checksum from the extracted data to make comparision

        return (headerChecksum == Checksum(data));
    }

    public static RXPHeader getHeader(DatagramPacket receivePacket) {
        return new RXPHeader(Arrays.copyOfRange(receivePacket.getData(), 0, HEADER_SIZE));
    }


    public static DatagramPacket setHeader(DatagramPacket packet, RXPHeader header) {
        byte[] headerBytes = header.getHeaderBytes();
        byte[] packetData = packet.getData();
        System.arraycopy(headerBytes, 0, packetData, 0, HEADER_SIZE);
        packet.setData(packetData);

        return packet;
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

    public static File getFileFromBytes(String pathname, byte[] data) {
        File file = new File(pathname);
        try (FileOutputStream fop = new FileOutputStream(file)) {
            // if file doesn't exists, then create it
            if (!file.exists()) {
                file.createNewFile();
            }
            fop.write(data);
            fop.flush();
            fop.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return file;
    }

    public int CheckSum(byte[] data) {
        //TODO checksum algorithm
        int result;
        return result;
    }
}
