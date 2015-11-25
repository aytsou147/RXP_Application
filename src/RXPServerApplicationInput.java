import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Handles the user command line input while the server is running.
 * This is a separate thread than the server.
 */
public class RXPServerApplicationInput extends Thread {
    public void run() {
        long end = System.currentTimeMillis();
        InputStreamReader fileInputStream = new InputStreamReader(System.in);
        BufferedReader bufferedReader = new BufferedReader(fileInputStream);

        try {
            String s = "";

            while((System.currentTimeMillis()>=end)) {
                s = "";
                if (bufferedReader.ready()) {
                    s += bufferedReader.readLine();
                    System.out.println("here");

                    System.out.println(s);
                    if (s.equalsIgnoreCase("terminate")) {
                        RXPServerApplication.server.terminate();
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
