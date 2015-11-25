import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Scanner;


/**
 * The client application that runs the client
 */
public class RXPClientApplication {
    private static RXPClient client;
    public static void main(String[] args) {
        //take in arguments
        Scanner scan = new Scanner(System.in);
        boolean connected = false;
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
                    client = new RXPClient(clientPort, netEmuIpAddress, netEmuPort);
                    System.out.println("Initialized RXP Client");
                } catch (NumberFormatException e) {
                    System.err.println("The port number must be a valid port number.");
                    System.exit(1);
                } catch (IllegalArgumentException e) {
                    System.err.println("Invalid IP address.");
                    System.exit(1);
                }

            } else {
                System.err.println("Not enough arguments");
                System.exit(1);
            }
        } else {
            System.err.println("Use format: fxa-client X[client port] A[NetEmu IP] P[NetEmu Port]");
            System.exit(1);
        }

        long endTimeCount = System.currentTimeMillis();
        InputStreamReader fileInputStream = new InputStreamReader(System.in);
        BufferedReader buffRead = new BufferedReader(fileInputStream);
        try {
            String commandEntries;

            while ((System.currentTimeMillis() >= endTimeCount)) {
                commandEntries = "";
                if (buffRead.ready()) {
                    commandEntries += buffRead.readLine();
                    System.out.println(commandEntries);
                    String[] split = commandEntries.split("\\s+");
                    if (split.length > 0 && !commandEntries.equals("disconnect")) {
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
                                if (split.length < 1) {
                                    System.err.println("You need another argument after put");
                                } else {
                                    String fileName = split[1];

                                    String filePath = System.getProperty("user.dir") + "/" + fileName;
                                    System.out.println(fileName);
                                    System.out.println(filePath);
                                    boolean success = false;
                                    byte[] file = RXPHelpers.fileToBytes(filePath);
                                    if (file != null) {
                                        if (client.sendFileNameUpload(fileName)) {
                                            success = client.upload(RXPHelpers.fileToBytes(filePath));
                                        }
                                    } else {
                                        System.out.println("File does not exist");
                                    }

                                    if (success) {
                                        System.out.println("Successfully uploaded");
                                    } else {
                                        System.out.println("Upload failed");
                                    }

                                }
                                break;
                            }
                            case "get": {
                                if (split.length > 1) {
                                    String pathName = split[1];
                                    //download file from server
                                    if (!client.download(pathName)) {
                                        System.out.println("Download failed");
                                    } else {
                                        System.out.println("Downloaded!");
                                    }

                                } else {
                                    System.err.println("Need arg after get: filename");
                                }
                                break;
                            }

                            default: {
                                System.err.println("Command invalid");
                                break;
                            }
                        }
                    } else if (commandEntries.equalsIgnoreCase("disconnect")) {
                        if (client.getClientState() == ClientState.CLOSED) {
                            System.out.println("Connection does not exist.");
                            scan.close();
                            System.exit(0);
                        }
                        System.out.println("Disconnecting");
                        client.clientDisconnect();
                        scan.close();
                        System.exit(0);

                    } else {
                        System.err.println("Command invalid.");
                        System.exit(1);
                        break;
                    }
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            buffRead.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.exit(0);
    }
}