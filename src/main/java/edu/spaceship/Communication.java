package edu.spaceship;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The one place that manages TCP socket writers for public/private message delivery over TLS. */
public final class Communication {
    private final Map<String, PrintWriter> clients = new ConcurrentHashMap<>();

    public void register(String id, PrintWriter writer) {
        clients.put(id, writer);
    }
    public void unregister(String id) {
        clients.remove(id);
    }

    /** Find the player ID associated with a given PrintWriter. Used on disconnect. */
    public String findPlayerId(PrintWriter writer) {
        for (Map.Entry<String, PrintWriter> entry : clients.entrySet()) {
            if (entry.getValue() == writer) return entry.getKey();
        }
        return null;
    }

    public void sendPrivate(String id, String type, String text, String role, String phase, int integrity) {
        PrintWriter client = clients.get(id);
        if (client != null) {
            safeSend(client, type + "|" + text + "|" + role + "|" + phase + "|" + integrity);
        }
    }

    public void broadcast(String type, String text, String phase, int integrity) {
        clients.forEach((id, client) -> safeSend(client, type + "|" + text + "||" + phase + "|" + integrity));
    }

    private void safeSend(PrintWriter client, String message) {
        try { synchronized (client) { client.println(message); client.flush(); } } catch (RuntimeException ignored) { /* disconnected stream */ }
    }
}
