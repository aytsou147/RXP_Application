import java.io.*;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class RXPClientApplication {

    private static final String NETEMUIP = "127.0.0.1";
    private static final int NETEMUPORT = 8000;
    private static final int CLIENTPORT = 3250;
    private static int netEmuPort, clientPort;
    private static RTPClient client;

    public static void main(String[] args) {
        //scan in arguments
        Scanner scan = new Scanner(System.in);

        if (args.length > 0 && args[0].equalsIgnoreCase("FXA-client")) {
            if (args.length > 3) {
                try {
                    //fxa-client X A P
                    //X is the port the client will bind to
                    clientPort = Integer.parseInt(args[1]);

                    //A is the IP address of NetEMU
                    netEmuIpAddress = args[2];

                    //P is the UDP port of NetEMU
                    netEmuPort = Integer.parseInt(args[3]);

                    System.out.println("Initializing RXPClient...");
                    client = new RXPClient(clientPort, netEmuIpAddress, netEmuPort);
                    System.out.println("Initialization Complete");
                } catch (NumberFormatException e) {
                    System.err.println("The port argument must be a valid port number.");
                    System.exit(1);
                } catch (IllegalArgumentException e) {
                    System.err.println("The second argument must be a valid IP address.");
                    System.exit(1);
                }

            } else {
                System.err.println("Not enough arguments.");
                System.exit(1);
            }
        } else {
            System.err.println("fxa-client must be run as first command in the format of fxa-client X A P");
            System.exit(1);
        }
        long end = System.currentTimeMillis();
        InputStreamReader fileInputStream = new InputStreamReader(System.in);
        BufferedReader bufferedReader = new BufferedReader(fileInputStream);
        try {
            String s = new String("");

            while ((System.currentTimeMillis() >= end)) {
                if (connected) {
                    if (client.checkServerRequestsTermination()) {
                        if (client.terminateFromServer()) {
                            System.out.println("Server was successfully terminated..");
                            System.exit(0);
                        } else {
                            System.out.println("Server was not terminated");
                        }
                    }
                }
                s = "";
                if (bufferedReader.ready()) {
                    s += bufferedReader.readLine();
                    System.out.println(s);
                    String[] split = s.split("\\s+");
                    if (split.length > 0 && !s.equals("disconnect")) {
                        switch (split[0]) {
                            case "connect": {
                                if (!connected && client.setup()) {
                                    System.out.println("Client has successfully connected to the server");
                                    connected = true;
                                } else {
                                    System.out.println("Cannot connect");
                                }
                                break;
                            }
                            case "post": {
                                if (split.length > 1) {
                                    String fileName = split[1];

                                    String filePath = System.getProperty("user.dir") + "/" + fileName;
                                    System.out.println(filePath);
                                    boolean success = false;
                                    byte[] file = getFileBytes(filePath);
                                    if (file != null) {
                                        client.sendName(fileName);
                                        success = client.startUpload(getFileBytes(filePath));
                                    } else {
                                        System.out.println("File does not exist");
                                    }

                                    if (success) {
                                        System.out.println("Successfully uploaded");
                                    } else {
                                        System.out.println("Upload failed");
                                    }

                                } else {
                                    System.err.println("You need another argument after get");
                                }
                                break;
                            }
                            case "get": {
                                if (split.length > 1) {
                                    String pathName = split[1];
                                    downloaded = client.startDownload(pathName); //TODO implement this once this is finished
                                    //download file from server
                                    if (!downloaded) {
                                        System.out.println("File didn't download");
                                    } else {
                                        System.out.println("Successfully uploaded");
                                    }

                                } else {
                                    System.err.println("You need another argument after post");
                                }
                                break;
                            }
                            case "window": {
                                if (split.length > 1) {
                                    try {
                                        int size = Integer.parseInt(split[1]);
                                        client.setWindowSize(size);  //change client's max window size
                                    } catch (NumberFormatException e) {
                                        System.err.println("Invalid window size.");
                                    }
                                } else {
                                    System.err.println("You need another argument after window");
                                }
                                break;
                            }
                            default: {
                                System.err.println("Invalid command.");
                                break;
                            }
                        }
                    } else if (s.equalsIgnoreCase("disconnect")) {
                        System.out.println("Disconnecting...");
                        client.teardown();
                        scan.close();
                        while (client.getClientState() != ClientState.CLOSED) {
                        }
                    } else {
                        System.err.println("Invalid command.");
                        System.exit(1);
                        break;
                    }
                }


            }

        } catch (IOException e) {

        }


        try {
            bufferedReader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.exit(0);
    }

    public static byte[] getFileBytes(String pathName) {
        Path path = Paths.get(pathName);
        byte[] data = null;
        try {
            data = Files.readAllBytes(path);
        } catch (NoSuchFileException e1) {
            return null;
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
}