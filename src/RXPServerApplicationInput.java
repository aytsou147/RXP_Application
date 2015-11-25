import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Created by Anthony on 11/25/2015.
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
                        if (RXPServerApplication.server.terminate()) {
                            System.out.println("Server termination successful");
                            System.exit(0);
                        } else {
                            System.out.println("Waiting for transfer to finish!");
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
