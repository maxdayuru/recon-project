import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


public class portscannerenhanced {

    static class PortResult {
        final int port;
        final boolean open;
        final String message;

        PortResult(int port, boolean open, String message) {
            this.port = port;
            this.open = open;
            this.message = message;
        }
    }

    static class PortCheckTask implements Callable<PortResult> {
        private final String host;
        private final int port;
        private final int timeout;

        PortCheckTask(String host, int port, int timeout) {
            this.host = host;
            this.port = port;
            this.timeout = timeout;
        }

        @Override
        public PortResult call() {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), timeout);
                return new PortResult(port, true, "OPEN");
            } catch (IOException e) {
                return new PortResult(port, false, "closed/filtered");
            }
        }
    }

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        System.out.println("=== PortScannerEnhanced ===");
        String host = askString(sc, "Enter host (IP or hostname) [default: localhost]: ", "localhost");

        int startPort = askInt(sc, "Start port [default: 1]: ", 1, 1, 65535);
        int endPort = askInt(sc, "End port [default: 1024]: ", 1024, 1, 65535);
        if (endPort < startPort) {
            System.out.println("End port must be >= start port. Swapping values.");
            int t = startPort; startPort = endPort; endPort = t;
        }

        int timeout = askInt(sc, "Timeout in ms for each port [default: 200]: ", 200, 1, 10000);
        int threads = askInt(sc, "Number of worker threads [default: 100]: ", 100, 1, 1000);

        String showAllInput = askString(sc, "Show closed ports too? (y/N) [default: N]: ", "N");
        boolean showAll = showAllInput.equalsIgnoreCase("y");

        sc.close();

        System.out.printf("Scanning %s ports %d-%d with timeout %dms using %d threads...%n",
                host, startPort, endPort, timeout, threads);

        List<PortResult> results = runScan(host, startPort, endPort, timeout, threads, showProgress(true));
        long openCount = results.stream().filter(r -> r.open).count();

        // Print results according to user preference
        System.out.println("\n--- Results ---");
        for (PortResult r : results) {
            if (r.open) {
                System.out.printf("Port %5d : %s%n", r.port, r.message);
            } else if (showAll) {
                System.out.printf("Port %5d : %s%n", r.port, r.message);
            }
        }
        System.out.printf("%nScan finished. Open ports: %d out of %d.%n", openCount, results.size());

        // Save open ports to file
        if (openCount > 0) {
            String filename = String.format("ports-open-%s.txt", host.replaceAll("[^a-zA-Z0-9._-]", "_"));
            try {
                saveOpenPortsToFile(filename, results, host);
                System.out.println("Open ports saved to: " + filename);
            } catch (IOException e) {
                System.err.println("Failed to write results to file: " + e.getMessage());
            }
        } else {
            System.out.println("No open ports found; nothing was written to file.");
        }
    }

    // Run the scan and return list of port results (ordered by port)
    private static List<PortResult> runScan(String host, int startPort, int endPort, int timeout,
                                            int threads, boolean showProgress) {
        int total = endPort - startPort + 1;
        ExecutorService exe = Executors.newFixedThreadPool(threads);
        List<Future<PortResult>> futures = new ArrayList<>(total);
        AtomicInteger done = new AtomicInteger(0);
        Instant t0 = Instant.now();

        // submit tasks
        for (int p = startPort; p <= endPort; p++) {
            PortCheckTask task = new PortCheckTask(host, p, timeout);
            Future<PortResult> f = exe.submit(() -> {
                PortResult res = task.call();
                int d = done.incrementAndGet();
                if (showProgress && d % Math.max(1, total/50) == 0) {
                    // progress update ~50 ticks
                    double pct = (d * 100.0) / total;
                    System.out.printf("Progress: %3.0f%% (%d/%d)%n", pct, d, total);
                }
                return res;
            });
            futures.add(f);
        }

        // collect results in port order
        List<PortResult> results = new ArrayList<>(total);
        for (Future<PortResult> f : futures) {
            try {
                results.add(f.get()); // blocking but tasks run concurrently
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.add(new PortResult(-1, false, "interrupted"));
            } catch (ExecutionException e) {
                results.add(new PortResult(-1, false, "error: " + e.getCause()));
            }
        }

        exe.shutdown();

        Instant t1 = Instant.now();
        System.out.printf("Elapsed time: %d ms%n", Duration.between(t0, t1).toMillis());
        return results;
    }

    // Saves open ports to a file in a simple human-readable format
    private static void saveOpenPortsToFile(String filename, List<PortResult> results, String host) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(filename))) {
            w.write("Open ports for host: " + host + System.lineSeparator());
            for (PortResult r : results) {
                if (r.open) {
                    w.write(String.format("%d%n", r.port));
                }
            }
        }
    }

    // Helper to read an integer with validation and default
    private static int askInt(Scanner sc, String prompt, int defaultValue, int min, int max) {
        while (true) {
            System.out.print(prompt);
            String line = sc.nextLine().trim();
            if (line.isEmpty()) return defaultValue;
            try {
                int v = Integer.parseInt(line);
                if (v < min || v > max) {
                    System.out.printf("Please enter a number between %d and %d.%n", min, max);
                    continue;
                }
                return v;
            } catch (NumberFormatException e) {
                System.out.println("Not a valid integer. Try again.");
            }
        }
    }

    // Helper to read a string with default
    private static String askString(Scanner sc, String prompt, String defaultValue) {
        System.out.print(prompt);
        String line = sc.nextLine().trim();
        return line.isEmpty() ? defaultValue : line;
    }

    // Small helper to decide whether to show progress (you can customize)
    private static boolean showProgress(boolean defaultValue) {
        return defaultValue;
    }
}
 
