import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class RXPServerApplication {

//    private static final String NETEMUIP = "127.0.0.1";
//    private static final int NETEMUPORT = 8000;
//    private static final int SERVERPORT = 3251;
    private static RXPServer server;

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("fxa-server")) {
            if (args.length > 3) {
                try {
                    //Format of command fxa-server A B C
                    //A is the port the server will bind to
                    int serverPort = Integer.parseInt(args[1]);

                    //B is the IP address of NetEMU
                    String netEmuIpAddress = args[2];

                    //C is the UDP port of NetEMU
                    int netEmuPort = Integer.parseInt(args[3]);

                    System.out.println("Initializing RXP Server...");
                    server = new RXPServer(serverPort, netEmuIpAddress, netEmuPort);
                    server.createSocket();
                    System.out.println("Initialization Complete");
                } catch(NumberFormatException e){
                    System.err.println("The port argument must be a valid port number.");
                    System.exit(1);
                } catch(IllegalArgumentException e){
                    System.err.println("The second argument must be a valid IP address.");
                    System.exit(1);
                }
            } else {
                System.err.println("Not enough arguments.");
                System.exit(1);
            }
        } else {
            System.err.println("fta-server must be run as first command in the format of fxa-server serverPort netEmuIp netEmuPort");
            System.exit(1);
        }

        long end = System.currentTimeMillis();
        InputStreamReader fileInputStream = new InputStreamReader(System.in);
        BufferedReader bufferedReader = new BufferedReader(fileInputStream);

        try {
            String s = "";

            while((System.currentTimeMillis()>=end)) {
                s = "";
                server.connect();
                if (bufferedReader.ready()) {
                    s += bufferedReader.readLine();
                    System.out.println("here");

                    System.out.println(s);
                    if (s.equalsIgnoreCase("terminate")) {
                        if (server.terminate()) {
                            System.out.println("Server termination successful");
                            System.exit(0);
                        } else {
                            System.out.println("Server termination failed");
                        }
                    } else {
                        System.err.println("Invalid command");
                    }
                }
            }
            bufferedReader.close();
        } catch (java.io.IOException e) {
            System.err.println("Server could not be shut down");
            e.printStackTrace();
        }
    }
}
