package edu.spaceship;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * One interactive player client using TLS sockets.
 * All communication is encrypted at the transport layer using TLS.
 * Start one JVM process per player, passing the server's host and port.
 */
public final class Player {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 50051;

        if (host.isBlank()) {
            System.out.print("Server host: ");
            try (Scanner sc = new Scanner(System.in)) {
                host = sc.nextLine().trim();
            }
        }

        // Connect first, then the server will prompt for the player name
        System.out.println("Connecting to " + host + ":" + port + "...");

        // Create SSL context with the server's certificate
        SSLContext sslContext = createSSLContext();
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        Socket socket;
        try {
            socket = sslContext.getSocketFactory().createSocket(host, port);
        } catch (IOException e) {
            System.err.println("Failed to connect to server at " + host + ":" + port + ". Make sure the server is running.");
            System.out.println("Disconnected from server.");
            return;
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

        // Server prompts for name interactively; read the prompt and send the name
        Scanner scanner = new Scanner(System.in);
        CountDownLatch registered = new CountDownLatch(1);
        AtomicReference<String> playerId = new AtomicReference<>();
        AtomicReference<String> roleText = new AtomicReference<>();

        String serverLine = in.readLine();
        // Server sends "Enter your name:" — read it and wait for user input
        while (serverLine != null && serverLine.startsWith("Enter your name:")) {
            System.out.print(serverLine + " ");
            String name = scanner.nextLine().trim();
            while (name.isEmpty()) {
                System.out.print("Name cannot be empty. Enter your name: ");
                name = scanner.nextLine().trim();
            }
            out.println(name);
            serverLine = in.readLine();
            // If name is taken, server re-prompts
            if (serverLine != null && serverLine.contains("already taken")) {
                System.out.println(serverLine);
                serverLine = in.readLine(); // Should be "Enter your name:" again
            } else if (serverLine != null && serverLine.startsWith("REGISTERED|")) {
                // Server sent REGISTERED — process it now since we already read it
                String[] parts = serverLine.split("\\|", -1);
                if (parts.length >= 3) {
                    playerId.set(parts[2]);
                    registered.countDown();
                }
                break;
            } else {
                break;
            }
        }

        // Read server messages in a background thread
        Thread reader = new Thread(() -> {
            try {
                while (true) {
                    String message = in.readLine();
                    if (message == null) break;

                    String[] parts = message.split("\\|", -1);
                    String type = parts[0];

                    if ("ERROR".equals(type)) {
                        System.err.println("[ERROR] " + (parts.length > 1 ? parts[1] : message));
                        continue;
                    }

                    if ("REGISTERED".equals(type)) {
                        if (parts.length >= 3) {
                            playerId.set(parts[2]);
                            registered.countDown();
                        }
                        continue;
                    }

                    String text = parts.length > 1 ? parts[1] : "";
                    int integrity = 0;
                    if (parts.length > 4) {
                        try { integrity = Integer.parseInt(parts[4]); } catch (NumberFormatException ignored) {}
                    }

                    if ("ROLE".equals(type)) {
                        roleText.set(text);
                    }

                    System.out.println("[" + type + "] " + text + (integrity > 0 ? " | integrity " + integrity + "%" : ""));
                    System.out.print("> ");
                    System.out.flush();
                }
            } catch (IOException e) {
                // Connection closed
            }
        });
        reader.setDaemon(true);
        reader.start();

        // Wait for registration
        if (!registered.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            System.err.println("Registration timed out.");
            socket.close();
            System.out.println("Disconnected from server.");
            return;
        }

        // Brief pause to let the ROLE message arrive from the server
        Thread.sleep(500);

        System.out.println("Connected to server (TLS encrypted).");

        // Display the role prominently after all initial output
        String role = roleText.get();
        if (role != null && !role.isEmpty()) {
            System.out.println();
            System.out.println("========================================");
            System.out.println("  YOUR ROLE: " + role);
            System.out.println("========================================");
            System.out.println();
            roleText.set(null); // Clear so reader thread doesn't duplicate
        }

        System.out.println("Commands: STATUS | REPAIR | SABOTAGE | CHAT <message> | WHISPER <player-id> <message> | VOTE <player-id> | VOTE SKIP | QUIT");
        System.out.print("> ");
        System.out.flush();

        // Main loop: read input and print prompt after sending each command
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.equalsIgnoreCase("QUIT")) break;
            if (line.isBlank()) continue;

            out.println(line);
            // Print prompt after sending command so the user always sees it,
            // even if no server message arrives to trigger the reader thread's prompt.
            System.out.print("> ");
            System.out.flush();
        }

        // Close the connection
        socket.close();
        System.out.println("Disconnected from server.");
    }

    /**
     * Creates an SSLContext that trusts the server's self-signed certificate.
     */
    private static SSLContext createSSLContext() throws Exception {
        Path certFile = Path.of("certs", "server.crt");

        // Load the server's certificate
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert;
        try (var is = Files.newInputStream(certFile)) {
            cert = (X509Certificate) cf.generateCertificate(is);
        }

        // Create a trust store containing only this certificate
        KeyStore trustStore = KeyStore.getInstance("JKS");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("server", cert);

        // Initialize TrustManager
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        // Create and return the SSLContext
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }
}
