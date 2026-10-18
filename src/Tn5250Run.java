import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.tn5250j.Session5250;
import org.tn5250j.SessionConfig;
import org.tn5250j.TN5250jConstants;
import org.tn5250j.framework.tn5250.Screen5250;
import org.tn5250j.framework.tn5250.ScreenField;
import org.tn5250j.keyboard.KeyMnemonic;

/**
 * Runs one command through a real IBM i 5250 (Telnet) session, the same
 * sign-on path a tn5250 emulator app uses: sign on, type the command at
 * the command line, page through display output, sign off.
 *
 * Results are returned as CLEAN WEB OUTPUT, not raw screens: SQL runs via
 * QSH db2 redirected to a file and paged back line-chunk by line-chunk;
 * CL display screens are captured without menu chrome and F-key legends.
 */
public class Tn5250Run {

    public static class AuthFail extends Exception {
        public AuthFail(String m) { super(m); }
    }

    private final Screen5250 scr;
    private final Session5250 session;

    private Tn5250Run(Session5250 s) {
        this.session = s;
        this.scr = s.getScreen();
    }

    private String text() {
        char[] c = scr.getCharacters();
        for (int i = 0; i < c.length; i++) if (c[i] == '\0') c[i] = ' ';
        int rows = scr.getRows(), cols = scr.getColumns();
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            int off = r * cols;
            if (off >= c.length) break;
            int len = Math.min(cols, c.length - off);
            sb.append(c, off, len);
            if (r < rows - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /** Wait until the screen text stops changing (or timeout). Returns the text. */
    private String waitStable(long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        String last = text(), cur;
        int stable = 0;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(250);
            cur = text();
            if (cur.equals(last)) {
                if (++stable >= 3) return cur;
            } else {
                stable = 0;
                last = cur;
            }
        }
        return last;
    }

    private boolean waitFor(String needle, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (text().contains(needle)) return true;
            Thread.sleep(250);
        }
        return text().contains(needle);
    }

    private void sendLine(String s) {
        scr.sendKeys(s);
        scr.sendKeys(KeyMnemonic.ENTER);
    }

    private static String lastNonBlank(String screenText) {
        String[] lines = screenText.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static String trimBlock(String s) {
        String[] lines = s.split("\n", -1);
        int a = 0, b = lines.length;
        while (a < b && lines[a].trim().isEmpty()) a++;
        while (b > a && lines[b - 1].trim().isEmpty()) b--;
        StringBuilder sb = new StringBuilder();
        for (int i = a; i < b; i++) {
            sb.append(lines[i].replaceAll("\\s+$", ""));
            if (i < b - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Extract just the latest command's output from a QSH screen: only the
     * lines below the most recent ">" echo (older scrollback on the same
     * screen, e.g. a wc count, must not leak into the result).
     */
    static String extractQsh(String screen) {
        String[] lines = screen.split("\n");
        int start = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith(">")) start = i + 1;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.isEmpty()) continue;
            if (t.equals("QSH Command Entry")) continue;
            if (t.equals("$")) continue;
            if (t.startsWith("===>")) continue;
            if (t.contains("F3=Exit") || t.contains("F13=Clear") || t.contains("F21=CL")) continue;
            sb.append(lines[i].replaceAll("\\s+$", "")).append('\n');
        }
        return sb.toString().trim();
    }

    private static String[] sliceBySpans(String line, java.util.List<int[]> spans) {
        String[] cells = new String[spans.size()];
        for (int c = 0; c < spans.size(); c++) {
            int a = spans.get(c)[0], b = spans.get(c)[1];
            String pad = line.length() < b ? line + " ".repeat(b - line.length()) : line;
            cells[c] = pad.substring(Math.min(a, pad.length()), Math.min(b, pad.length())).trim();
        }
        return cells;
    }

    /** A run's result: clean text plus, for SQL, the parsed result table. */
    public static class Result {
        public String text;
        public String[] columns;
        public java.util.List<String[]> rows;
    }

    /**
     * Parse db2 CLI text output into columns/rows using the dashed separator
     * line's column spans. Returns null when the text is not a result set
     * (e.g. an SQL error message).
     */
    static Result parseDb2(String text) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        int dashIdx = -1;
        java.util.List<int[]> spans = null;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.matches("^(-{2,}\\s+)*-{2,}\\s*$")) {
                dashIdx = i;
                spans = new ArrayList<>();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("-+").matcher(lines[i]);
                while (m.find()) spans.add(new int[]{m.start(), m.end()});
                break;
            }
        }
        if (dashIdx < 0 || spans == null || spans.isEmpty()) return null;
        String header = null;
        for (int i = dashIdx - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) { header = lines[i]; break; }
        }
        if (header == null) return null;
        Result r = new Result();
        r.text = text;
        r.columns = sliceBySpans(header, spans);
        r.rows = new ArrayList<>();
        for (int i = dashIdx + 1; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.matches("^\\d+\\s+RECORD\\(S\\)\\s+SELECTED\\.?$")) break;
            if (t.isEmpty()) continue;
            r.rows.add(sliceBySpans(lines[i], spans));
        }
        return r;
    }

    /** Drop F-key legend lines from a 5250 display screen. */
    static String stripLegends(String screen) {
        StringBuilder sb = new StringBuilder();
        for (String line : screen.split("\n")) {
            String t = line.trim();
            int legends = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("F\\d{1,2}=").matcher(t);
            while (m.find()) legends++;
            if (legends >= 2) continue;
            if (t.matches("^F\\d{1,2}=.*")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private String runSqlQsh(String command) throws Exception {
        sendLine("QSH");
        String qsh = waitStable(20000);
        if (!qsh.contains("$")) {
            throw new Exception("QSH did not start. Screen: " + trimBlock(qsh));
        }
        String sql = command.replace("\"", "").replace(";", "");
        String file = "/tmp/ibridge_out.txt";
        sendLine("db2 \"" + sql + "\" > " + file + " 2>&1");
        waitStable(45000);
        sendLine("wc -l < " + file);
        String wcClean = extractQsh(waitStable(15000));
        int total = -1;
        java.util.regex.Matcher wm = java.util.regex.Pattern.compile("\\d+").matcher(wcClean);
        if (wm.find()) {
            try { total = Integer.parseInt(wm.group()); } catch (Exception ignore) {}
        }
        StringBuilder all = new StringBuilder();
        if (total <= 0) {
            all.append(extractQsh(text()));
        } else {
            int chunk = 7;
            int cap = Math.min(total, 245);
            for (int a = 1; a <= cap; a += chunk) {
                int b = Math.min(a + chunk - 1, cap);
                sendLine("sed -n '" + a + "," + b + "p' " + file);
                String c = extractQsh(waitStable(15000));
                if (!c.isEmpty()) {
                    if (all.length() > 0) all.append('\n');
                    all.append(c);
                }
            }
            if (total > cap) all.append("\n... (").append(total - cap).append(" more lines not shown)");
        }
        sendLine("exit");
        waitStable(10000);
        String out = all.toString().trim();
        return out.isEmpty() ? "(no output)" : out;
    }

    private String runCl(String command) throws Exception {
        sendLine(command);
        List<String> screens = new ArrayList<>();
        String prev = "";
        for (int i = 0; i < 10; i++) {
            String t = waitStable(20000);
            if (!t.equals(prev)) {
                screens.add(t);
                prev = t;
            }
            if (t.contains("===>")) break;
            if (t.contains("Press ENTER to continue")) {
                scr.sendKeys(KeyMnemonic.ENTER);
            } else if (t.contains("F3=Exit")) {
                scr.sendKeys(KeyMnemonic.PF3);
            } else {
                scr.sendKeys(KeyMnemonic.ENTER);
            }
        }
        boolean hasDisplay = false;
        for (String s : screens) {
            if (!s.contains("IBM i Main Menu")) { hasDisplay = true; break; }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : screens) {
            boolean pureMenu = s.contains("IBM i Main Menu") && s.contains("Select one of the following");
            if (pureMenu && hasDisplay && screens.size() > 1) continue;
            String cleaned = trimBlock(stripLegends(s));
            if (!cleaned.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(cleaned);
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? trimBlock(text()) : out;
    }

    public static String run(String host, String user, String password, String kind, String command) throws Exception {
        return runEx(host, user, password, kind, command).text;
    }

    public static Result runEx(String host, String user, String password, String kind, String command) throws Exception {
        Properties props = new Properties();
        props.setProperty(TN5250jConstants.SESSION_HOST, host);
        props.setProperty(TN5250jConstants.SESSION_HOST_PORT, "23");
        props.setProperty(TN5250jConstants.SESSION_CODE_PAGE, "37");
        props.setProperty(TN5250jConstants.SESSION_SCREEN_SIZE, TN5250jConstants.SCREEN_SIZE_24X80_STR);

        SessionConfig cfg = new SessionConfig("ibridge", "ibridge");
        Session5250 session = new Session5250(props, "", "ibridge", cfg);
        Tn5250Run r = new Tn5250Run(session);
        try {
            session.connect();
            // PUB400 uses a custom sign-on display ("Your user name:" /
            // "Password (max. 128):") instead of the stock "Sign On" title.
            if (!r.waitFor("Your user name:", 45000) && !r.waitFor("Sign On", 5000)) {
                throw new Exception("5250 sign-on screen did not appear. Last screen: " + trimBlock(r.text()));
            }
            r.waitStable(5000);
            ScreenField[] f = r.scr.getScreenFields().getFields();
            if (f == null || f.length < 2) throw new Exception("Sign-on fields not found.");
            f[0].setString(user);
            f[1].setString(password);
            r.scr.sendKeys(KeyMnemonic.ENTER);
            String after = r.waitStable(30000);
            if (after.contains("Your user name:") || after.contains("Sign On")) {
                String msg = "";
                for (String line : after.split("\n")) {
                    String t = line.trim();
                    if (t.matches("(?i).*(CPF[0-9]{4}|incorrect|not correct|not valid|disabled|expired|error).*")) { msg = t; break; }
                }
                if (msg.isEmpty()) msg = lastNonBlank(after);
                throw new AuthFail(msg.isEmpty() ? "Sign-on rejected by the host." : msg);
            }
            if (!after.contains("===>")) {
                throw new Exception("Signed on, but no command line found. Last screen: " + trimBlock(after));
            }

            String out = "sql".equals(kind) ? r.runSqlQsh(command) : r.runCl(command);

            // Sign off (best effort)
            try {
                if (r.text().contains("===>")) {
                    r.scr.sendKeys("SIGNOFF");
                    r.scr.sendKeys(KeyMnemonic.ENTER);
                    Thread.sleep(1000);
                }
            } catch (Exception ignore) {}
            if ("sql".equals(kind)) {
                Result parsed = parseDb2(out);
                if (parsed != null) return parsed;
            }
            Result plain = new Result();
            plain.text = out;
            return plain;
        } finally {
            try { session.disconnect(); } catch (Exception ignore) {}
        }
    }
}
