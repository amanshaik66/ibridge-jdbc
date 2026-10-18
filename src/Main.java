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

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        DriverManager.setLoginTimeout(25);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", ex -> respond(ex, 200, "{\"ok\":true}"));
        server.createContext("/run", Main::handleRun);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        System.out.println("ibridge-jdbc listening on " + port);
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
                String out = Tn5250Run.run(host, username, password, kind, command.trim());
                respond(ex, 200, "{\"ok\":true,\"via\":\"tn5250\",\"output\":" + jsonQuote(out) + "}");
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
