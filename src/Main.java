import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.CommandCall;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.Properties;

/**
 * iBridge JDBC backend: runs SQL (JDBC) and CL (CommandCall) on an IBM i via
 * the JTOpen jt400 driver, talking to the IBM i host servers (signon 8476,
 * database 8471, remote command 8475) instead of SSH.
 *
 * POST /run  header: x-api-key: <APP_KEY>
 * body: {"host","port","username","password","kind":"sql"|"cl","command"}
 * reply: {"ok":true,"output":"..."} or {"ok":false,"message":"..."}
 * Credentials arrive per request, are used for one connection, never stored.
 */
public class Main {
    static final String APP_KEY = System.getenv().getOrDefault("APP_KEY", "ibridge_demo_key_2026");
    static final int MAX_CHARS = 20000;

    // ---- Persistent 5250 sessions (stay signed in) ----
    static final long SESSION_IDLE_MS = 10 * 60 * 1000L;
    static final int MAX_SESSIONS = 20;

    static class LiveSession {
        final Tn5250Run conn;
        volatile long lastUsed;
        LiveSession(Tn5250Run c) { conn = c; lastUsed = System.currentTimeMillis(); }
    }

    static final java.util.concurrent.ConcurrentHashMap<String, LiveSession> SESSIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    static void sweepSessions() {
        long now = System.currentTimeMillis();
        SESSIONS.forEach((token, s) -> {
            if (now - s.lastUsed > SESSION_IDLE_MS && SESSIONS.remove(token, s)) {
                s.conn.close();
                System.out.println("session expired: " + token.substring(0, 8));
            }
        });
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        DriverManager.setLoginTimeout(25);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", ex -> respond(ex, 200, "{\"ok\":true}"));
        server.createContext("/run", Main::handleRun);
        server.createContext("/session/open", Main::handleSessionOpen);
        server.createContext("/session/run", Main::handleSessionRun);
        server.createContext("/session/close", Main::handleSessionClose);
        server.createContext("/session/screen", Main::handleSessionScreen);
        server.createContext("/session/keys", Main::handleSessionKeys);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        Thread sweeper = new Thread(() -> {
            while (true) {
                try { Thread.sleep(30_000); sweepSessions(); } catch (Exception ignore) {}
            }
        });
        sweeper.setDaemon(true);
        sweeper.start();
        System.out.println("ibridge-jdbc listening on " + port);
    }

    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    static boolean authed(HttpExchange ex) {
        return APP_KEY.equals(ex.getRequestHeaders().getFirst("x-api-key"));
    }

    /** POST /session/open {host, username, password} -> {ok, session} */
    static void handleSessionOpen(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, err("POST required.")); return; }
        if (!authed(ex)) { respond(ex, 401, err("unauthorized")); return; }
        String body = readBody(ex);
        String host = jsonStr(body, "host");
        String username = jsonStr(body, "username");
        String password = jsonStr(body, "password");
        if (host == null || !host.matches("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?") || host.length() > 253) {
            respond(ex, 422, err("Invalid host.")); return;
        }
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            respond(ex, 422, err("Username and password are required.")); return;
        }
        if (SESSIONS.size() >= MAX_SESSIONS) { respond(ex, 503, err("Too many open sessions. Try again shortly.")); return; }
        try {
            Tn5250Run conn = Tn5250Run.open(host, username, password);
            String token = java.util.UUID.randomUUID().toString();
            SESSIONS.put(token, new LiveSession(conn));
            respond(ex, 200, "{\"ok\":true,\"session\":" + jsonQuote(token) + "}");
        } catch (Tn5250Run.AuthFail af) {
            respond(ex, 502, "{\"ok\":false,\"auth\":true,\"message\":" + jsonQuote(String.valueOf(af.getMessage())) + "}");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.length() > 1500) msg = msg.substring(0, 1500);
            respond(ex, 502, err("5250 session failed: " + msg));
        }
    }

    /** POST /session/run {session, kind, command} -> run inside the live session */
    static void handleSessionRun(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, err("POST required.")); return; }
        if (!authed(ex)) { respond(ex, 401, err("unauthorized")); return; }
        String body = readBody(ex);
        String token = jsonStr(body, "session");
        String kind = jsonStr(body, "kind");
        String command = jsonStr(body, "command");
        if (command == null || command.trim().isEmpty()) { respond(ex, 422, err("Command is empty.")); return; }
        LiveSession s = token == null ? null : SESSIONS.get(token);
        if (s == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"session_expired\",\"message\":\"Session expired or not found. Sign in again.\"}");
            return;
        }
        if (System.currentTimeMillis() - s.lastUsed > SESSION_IDLE_MS) {
            if (SESSIONS.remove(token, s)) s.conn.close();
            respond(ex, 404, "{\"ok\":false,\"error\":\"session_expired\",\"message\":\"Session expired. Sign in again.\"}");
            return;
        }
        try {
            Tn5250Run.Result res = s.conn.runCommand(kind == null ? "cl" : kind, command.trim());
            s.lastUsed = System.currentTimeMillis();
            respond(ex, 200, resultJson(res));
        } catch (Exception e) {
            // A failed run usually means the connection dropped: retire the session.
            if (SESSIONS.remove(token, s)) s.conn.close();
            String msg = e.getMessage();
            if (msg != null && msg.length() > 1500) msg = msg.substring(0, 1500);
            respond(ex, 502, "{\"ok\":false,\"error\":\"session_dead\",\"message\":" + jsonQuote("Session failed: " + msg) + "}");
        }
    }

    /** POST /session/close {session} -> sign off now */
    static void handleSessionClose(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, err("POST required.")); return; }
        if (!authed(ex)) { respond(ex, 401, err("unauthorized")); return; }
        String body = readBody(ex);
        String token = jsonStr(body, "session");
        if (token != null) {
            LiveSession s = SESSIONS.remove(token);
            if (s != null) s.conn.close();
        }
        respond(ex, 200, "{\"ok\":true}");
    }

    /** Shared live-session lookup: writes the error response and returns null when unusable. */
    static LiveSession requireSession(HttpExchange ex, String token) throws IOException {
        LiveSession s = token == null ? null : SESSIONS.get(token);
        if (s == null) {
            respond(ex, 404, "{\"ok\":false,\"error\":\"session_expired\",\"message\":\"Session expired or not found. Sign in again.\"}");
            return null;
        }
        if (System.currentTimeMillis() - s.lastUsed > SESSION_IDLE_MS) {
            if (SESSIONS.remove(token, s)) s.conn.close();
            respond(ex, 404, "{\"ok\":false,\"error\":\"session_expired\",\"message\":\"Session expired. Sign in again.\"}");
            return null;
        }
        return s;
    }

    static String snapshotJson(Tn5250Run.Snapshot snap) {
        StringBuilder jb = new StringBuilder("{\"ok\":true,\"screen\":{\"rows\":[");
        for (int i = 0; i < snap.rows.length; i++) {
            if (i > 0) jb.append(',');
            jb.append(jsonQuote(snap.rows[i]));
        }
        jb.append("],\"fields\":[");
        for (int i = 0; i < snap.fieldPos.size(); i++) {
            int[] p = snap.fieldPos.get(i);
            if (i > 0) jb.append(',');
            jb.append("{\"i\":").append(p[0])
              .append(",\"row\":").append(p[1])
              .append(",\"col\":").append(p[2])
              .append(",\"len\":").append(p[3])
              .append(",\"value\":").append(jsonQuote(snap.fieldVal.get(i)))
              .append(",\"bypass\":").append(snap.fieldBypass.get(i))
              .append('}');
        }
        jb.append("]}}");
        return jb.toString();
    }

    /** POST /session/screen {session} -> current 5250 screen (interactive terminal) */
    static void handleSessionScreen(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, err("POST required.")); return; }
        if (!authed(ex)) { respond(ex, 401, err("unauthorized")); return; }
        String body = readBody(ex);
        LiveSession s = requireSession(ex, jsonStr(body, "session"));
        if (s == null) return;
        try {
            Tn5250Run.Snapshot snap = s.conn.snapshot();
            s.lastUsed = System.currentTimeMillis();
            respond(ex, 200, snapshotJson(snap));
        } catch (Exception e) {
            respond(ex, 502, err("Screen read failed: " + e.getMessage()));
        }
    }

    /** POST /session/keys {session, fields:{idx:value}, aid} -> send keys, return new screen */
    static void handleSessionKeys(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, err("POST required.")); return; }
        if (!authed(ex)) { respond(ex, 401, err("unauthorized")); return; }
        String body = readBody(ex);
        String token = jsonStr(body, "session");
        LiveSession s = requireSession(ex, token);
        if (s == null) return;
        String aid = jsonStr(body, "aid");
        java.util.Map<Integer, String> values = new java.util.HashMap<>();
        // Extract the flat "fields" object: {"0":"text","3":"more"}
        int fi = body.indexOf("\"fields\"");
        if (fi >= 0) {
            int open = body.indexOf('{', fi + 8);
            if (open >= 0) {
                int depth = 0, end = -1;
                for (int i = open; i < body.length(); i++) {
                    char ch = body.charAt(i);
                    if (ch == '{') depth++;
                    else if (ch == '}' && --depth == 0) { end = i; break; }
                }
                if (end > open) {
                    String inner = body.substring(open, end + 1);
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("\"(\\d+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(inner);
                    while (m.find()) {
                        String raw = m.group(2);
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < raw.length(); k++) {
                            char ch = raw.charAt(k);
                            if (ch == '\\' && k + 1 < raw.length()) {
                                char e = raw.charAt(++k);
                                switch (e) {
                                    case 'n' -> sb.append('\n');
                                    case 't' -> sb.append('\t');
                                    case 'r' -> sb.append('\r');
                                    case 'u' -> { sb.append((char) Integer.parseInt(raw.substring(k + 1, k + 5), 16)); k += 4; }
                                    default -> sb.append(e);
                                }
                            } else sb.append(ch);
                        }
                        values.put(Integer.parseInt(m.group(1)), sb.toString());
                    }
                }
            }
        }
        try {
            Tn5250Run.Snapshot snap = s.conn.interact(values, aid == null ? "ENTER" : aid);
            s.lastUsed = System.currentTimeMillis();
            respond(ex, 200, snapshotJson(snap));
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.length() > 800) msg = msg.substring(0, 800);
            respond(ex, 502, err("Key send failed: " + msg));
        }
    }

    static String resultJson(Tn5250Run.Result res) {
        StringBuilder jb = new StringBuilder("{\"ok\":true,\"via\":\"tn5250\",\"output\":");
        jb.append(jsonQuote(res.text));
        if (res.columns != null && res.rows != null) {
            jb.append(",\"columns\":[");
            for (int i = 0; i < res.columns.length; i++) {
                if (i > 0) jb.append(',');
                jb.append(jsonQuote(res.columns[i]));
            }
            jb.append("],\"rows\":[");
            for (int ri = 0; ri < res.rows.size(); ri++) {
                if (ri > 0) jb.append(',');
                jb.append('[');
                String[] row = res.rows.get(ri);
                for (int ci = 0; ci < row.length; ci++) {
                    if (ci > 0) jb.append(',');
                    jb.append(jsonQuote(row[ci]));
                }
                jb.append(']');
            }
            jb.append(']');
        }
        jb.append('}');
        return jb.toString();
    }

    static void handleRun(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, err("POST required.")); return; }
        String key = ex.getRequestHeaders().getFirst("x-api-key");
        if (!APP_KEY.equals(key)) { respond(ex, 401, err("unauthorized")); return; }
        String body;
        try (InputStream in = ex.getRequestBody()) { body = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        String host = jsonStr(body, "host");
        String username = jsonStr(body, "username");
        String password = jsonStr(body, "password");
        String kind = jsonStr(body, "kind");
        String command = jsonStr(body, "command");
        if (host == null || !host.matches("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?") || host.length() > 253) {
            respond(ex, 422, err("Invalid host.")); return;
        }
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            respond(ex, 422, err("Username and password are required.")); return;
        }
        if (command == null || command.trim().isEmpty()) { respond(ex, 422, err("Command is empty.")); return; }
        String mode = jsonStr(body, "mode");
        if ("5250".equals(mode)) {
            try {
                Tn5250Run.Result res = Tn5250Run.runEx(host, username, password, kind, command.trim());
                respond(ex, 200, resultJson(res));
            } catch (Tn5250Run.AuthFail af) {
                respond(ex, 502, "{\"ok\":false,\"auth\":true,\"message\":" + jsonQuote(String.valueOf(af.getMessage())) + "}");
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
                respond(ex, 502, err("5250 session failed: " + msg));
            }
            return;
        }
        try {
            String out = "cl".equals(kind)
                    ? runCl(host, username, password, command.trim())
                    : runSql(host, username, password, command.trim());
            respond(ex, 200, "{\"ok\":true,\"output\":" + jsonQuote(out) + "}");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
            respond(ex, 502, err(msg));
        }
    }

    static String runSql(String host, String user, String password, String sql) throws Exception {
        Properties p = new Properties();
        p.put("user", user);
        p.put("password", password);
        p.put("prompt", "false");
        p.put("translate binary", "true");
        p.put("naming", "sql");
        p.put("errors", "full");
        p.put("login timeout", "20");
        Class.forName("com.ibm.as400.access.AS400JDBCDriver");
        try (Connection c = DriverManager.getConnection("jdbc:as400://" + host, p);
             Statement st = c.createStatement()) {
            st.setMaxRows(200);
            st.setQueryTimeout(45);
            boolean hasRs = st.execute(sql);
            if (!hasRs) {
                return "(statement completed, update count " + st.getUpdateCount() + ")";
            }
            try (ResultSet rs = st.getResultSet()) {
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= cols; i++) {
                    if (i > 1) sb.append(" | ");
                    sb.append(md.getColumnLabel(i));
                }
                sb.append('\n');
                int rows = 0;
                while (rs.next()) {
                    rows++;
                    for (int i = 1; i <= cols; i++) {
                        if (i > 1) sb.append(" | ");
                        String v = rs.getString(i);
                        sb.append(v == null ? "*NULL" : v.trim());
                    }
                    sb.append('\n');
                    if (sb.length() > MAX_CHARS) { sb.append("... (truncated)\n"); break; }
                }
                sb.append("(").append(rows).append(" row").append(rows == 1 ? "" : "s").append(")");
                return sb.toString().trim();
            }
        }
    }

    static String runCl(String host, String user, String password, String command) throws Exception {
        AS400 system = new AS400(host, user, password);
        system.setGuiAvailable(false);
        try {
            CommandCall cmd = new CommandCall(system);
            boolean ok = cmd.run(command);
            StringBuilder sb = new StringBuilder();
            for (AS400Message m : cmd.getMessageList()) {
                sb.append(m.getID()).append("  ").append(m.getText()).append('\n');
            }
            String msgs = sb.toString().trim();
            if (msgs.isEmpty()) {
                msgs = ok
                        ? "(CL command completed with no messages. Note: display output from CL commands is spooled on IBM i and is not returned over this connection - SQL samples return full results.)"
                        : "(CL command failed with no messages returned.)";
            }
            return msgs;
        } finally {
            system.disconnectAllServices();
        }
    }

    // --- minimal JSON helpers (string fields only, escape-aware) ---

    static String jsonStr(String json, String key) {
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length() || json.charAt(j) != '"') return null;
        StringBuilder sb = new StringBuilder();
        for (int k = j + 1; k < json.length(); k++) {
            char ch = json.charAt(k);
            if (ch == '"') return sb.toString();
            if (ch == '\\' && k + 1 < json.length()) {
                char e = json.charAt(++k);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> { sb.append((char) Integer.parseInt(json.substring(k + 1, k + 5), 16)); k += 4; }
                    default -> sb.append(e);
                }
            } else sb.append(ch);
        }
        return sb.toString();
    }

    static String jsonQuote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch)); else sb.append(ch); }
            }
        }
        return sb.append('"').toString();
    }

    static String err(String message) {
        return "{\"ok\":false,\"message\":" + jsonQuote(message.length() > 600 ? message.substring(0, 600) : message) + "}";
    }

    static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }
}
