import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Scanner;


public class RXPClientApplication {

    private static final String NETEMUIP = "127.0.0.1";
    private static final int NETEMUPORT = 5000;
    private static final int CLIENTPORT = 8080;
    private static RXPClient client;

    public static void main(String[] args) {
        //scan in arguments
        Scanner scan = new Scanner(System.in);
        boolean connected = false;
        boolean downloaded = false;
        if (args.length > 0 && args[0].equalsIgnoreCase("FXA-client")) {
            if (args.length > 3) {
                try {
                    //fxa-client X A P
                    //X is the port the client will bind to
                    int clientPort = Integer.parseInt(args[1]);

                    //A is the IP address of NetEMU
                    String netEmuIpAddress = args[2];

                    //P is the UDP port of NetEMU
                    int netEmuPort = Integer.parseInt(args[3]);

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
            System.err.println("Use format: fxa-client X[client port] A[NetEmu IP] P[NetEmu Port]");
            System.exit(1);
        }
        long end = System.currentTimeMillis();
        InputStreamReader fileInputStream = new InputStreamReader(System.in);
        BufferedReader bufferedReader = new BufferedReader(fileInputStream);
        try {
            String s = "";

            while ((System.currentTimeMillis() >= end)) {
                if (connected) {
                    if (client.getClientState() == ClientState.CLOSE_REQ || client.getClientState() == ClientState.CLOSE_WAIT) {
                        System.out.println("Connection Terminating");
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
                                System.out.println("Attempting to connect");
                                if (!connected && client.setupRXP()) {
                                    System.out.println("Client has successfully connected to the server");
                                    connected = true;
                                } else {
                                    System.out.println("Cannot connect");
                                }
                                break;
                            }
                            case "put": {
                                if (split.length > 1) {
                                    String fileName = split[1];

                                    String filePath = System.getProperty("user.dir") + "/" + fileName;
                                    System.out.println(fileName);
                                    System.out.println(filePath);
                                    boolean success = false;
                                    byte[] file = RXPHelpers.getFileBytes(filePath);
                                    if (file != null) {
                                        if (client.sendName(fileName)) {
                                            success = client.upload(RXPHelpers.getFileBytes(filePath));
                                        }
                                    } else {
                                        System.out.println("File does not exist");
                                    }

                                    if (success) {
                                        System.out.println("Successfully uploaded");
                                    } else {
                                        System.out.println("Upload failed");
                                    }

                                } else {
                                    System.err.println("You need another argument after put");
                                }
                                break;
                            }
                            case "get": {
                                if (split.length > 1) {
                                    String pathName = split[1];
                                    downloaded = client.download(pathName); //TODO implement this once this is finished
                                    //download file from server
                                    if (!downloaded) {
                                        System.out.println("File didn't download");
                                    } else {
                                        System.out.println("Successfully downloaded");
                                    }

                                } else {
                                    System.err.println("You need another argument after get");
                                }
                                break;
                            }
                            case "window": {
                                if (split.length > 1) {
                                    try {
                                        System.out.printf("Previous window size was: %d \n", client.getWindowSize());
                                        int size = Integer.parseInt(split[1]);
                                        client.setWindowSize(size);  //change client's window size
                                    } catch (NumberFormatException e) {
                                        System.err.println("Invalid win size.");
                                    }
                                } else {
                                    System.err.println("You need another argument after window");
                                }
                                break;
                            }
                            default: {
                                System.err.println("Invalid command");
                                break;
                            }
                        }
                    } else if (s.equalsIgnoreCase("disconnect")) {
                        System.out.println("Disconnecting...");
                        client.clientDisconnect();
                        scan.close();
                        System.exit(0);
//                        while (client.getClientState() != ClientState.CLOSED) {
//                        }
                    } else {
                        System.err.println("Invalid command.");
                        System.exit(1);
                        break;
                    }
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            bufferedReader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.exit(0);
    }
}