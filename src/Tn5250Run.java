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

    /**
     * Wait until the screen content changes from {@code before} (or timeout).
     * A plain stability wait can return in the dead gap between sending ENTER
     * and the host painting the response screen — the pre-key screen looks
     * "stable" — so after sending keys we first require a change.
     */
    private boolean waitChanged(String before, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (!text().equals(before)) return true;
            Thread.sleep(200);
        }
        return !text().equals(before);
    }

    private void sendLine(String s) {
        scr.sendKeys(s);
        scr.sendKeys(KeyMnemonic.ENTER);
    }

    /**
     * Type a command at a ===> command line. The host RETAINS the previous
     * command text in that field when the menu redisplays (harmless in a
     * fresh one-shot session, corrupting in a persistent session: the new
     * command would overwrite only its own length and keep the old tail).
     * HOME + ERASE_EOF clears the field first.
     */
    private void typeAtCommandLine(String s) {
        scr.sendKeys(KeyMnemonic.HOME);
        scr.sendKeys(KeyMnemonic.ERASE_EOF);
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

    /** A run's result: clean text plus, for SQL, the parsed result table.
     *  When a CL command opens an interactive display, {@code interactive}
     *  carries the live screen instead — the session stays on that screen. */
    public static class Result {
        public String text;
        public String[] columns;
        public java.util.List<String[]> rows;
        public Snapshot interactive;
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

    /**
     * Wait until QSH prints our completion sentinel. A db2 query's own screen
     * output is redirected to a file, so the screen sits perfectly still while
     * a slow query runs — a plain stability wait returns in under a second and
     * the old code then read an unfinished (or not yet created) file, which
     * surfaced as "(no output)". The sentinel is printed by the same shell
     * command line only after db2 exits, so this waits for real completion.
     * Matches ^DONE-<digits>$ on a full line, so the typed echo itself
     * ("echo DONE-$?") can never false-positive.
     */
    private boolean waitForDone(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        java.util.regex.Pattern done = java.util.regex.Pattern.compile("^DONE-\\d+$");
        while (true) {
            for (String line : text().split("\n")) {
                if (done.matcher(line.trim()).matches()) return true;
            }
            if (System.currentTimeMillis() >= end) return false;
            try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
        }
    }

    private String runSqlQsh(String command) throws Exception {
        typeAtCommandLine("QSH");
        // Wait FOR the QSH prompt itself (the "$"), not mere stability: the
        // menu screen is perfectly "stable" in the gap before QSH paints,
        // which made repeat SQL runs in a session fail with "did not start".
        if (!waitFor("$", 30000)) {
            throw new Exception("QSH did not start. Screen: " + trimBlock(text()));
        }
        waitStable(5000);
        String sql = command.replace("\"", "").replace(";", "");
        String file = "/tmp/ibridge_out.txt";
        // The sentinel prints only after db2 exits: the screen is static while
        // a slow query (e.g. SYSTEM_STATUS_INFO) runs, so a stability wait
        // alone returns almost immediately and reads a not-yet-written file.
        String beforeDb2 = text();
        sendLine("db2 \"" + sql + "\" > " + file + " 2>&1; echo DONE-$?");
        waitChanged(beforeDb2, 15000);
        if (!waitForDone(180000)) {
            throw new Exception("db2 did not finish within 180s. Screen: " + trimBlock(text()));
        }
        String beforeWc = text();
        sendLine("wc -l < " + file);
        // waitChanged first: a stability wait alone can return in the dead gap
        // between ENTER and the host painting, re-reading the previous chunk.
        waitChanged(beforeWc, 15000);
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
                String beforeSed = text();
                sendLine("sed -n '" + a + "," + b + "p' " + file);
                waitChanged(beforeSed, 15000);
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
        String before = text();
        typeAtCommandLine(command);
        waitChanged(before, 8000);
        return runClBody();
    }

    /**
     * Session-mode CL: if the command opens an INTERACTIVE display (a screen
     * with real input fields beyond the command line — WRKUSRJOB and kin),
     * hand the live screen to the terminal instead of flattening it to text.
     * Otherwise behave like a normal run and return the cleaned text.
     */
    private Result runClSmart(String command) throws Exception {
        String before = text();
        typeAtCommandLine(command);
        waitChanged(before, 8000);
        String t = waitStable(20000);
        Snapshot snap = snapshot();
        int inputs = 0;
        for (Boolean b : snap.fieldBypass) if (!b) inputs++;
        // Temporary diagnostics (visible in backend logs): what the screen's
        // fields actually look like, to ground the interactive rule in data.
        StringBuilder dbg = new StringBuilder("runClSmart cmd=").append(command)
                .append(" inputs=").append(inputs).append(" fields=");
        for (int i = 0; i < snap.fieldPos.size(); i++) {
            int[] p = snap.fieldPos.get(i);
            dbg.append('[').append(p[0]).append(" r").append(p[1]).append(" c").append(p[2])
               .append(" len").append(p[3]).append(" byp=").append(snap.fieldBypass.get(i)).append(']');
        }
        System.out.println(dbg);
        boolean mainMenu = t.contains("IBM i Main Menu");
        boolean interactiveText = t.contains("Type options, press Enter") || t.contains("Type choices, press Enter");
        Result r = new Result();
        if (!mainMenu && (interactiveText || inputs >= 2)) {
            r.text = "(interactive screen)";
            r.interactive = snap;
            return r;
        }
        r.text = runClBody();
        return r;
    }

    /** Page through display output after a CL command was typed; returns cleaned text. */
    private String runClBody() throws Exception {
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

    /** One-shot: open a session, run one command, sign off. */
    public static Result runEx(String host, String user, String password, String kind, String command) throws Exception {
        Tn5250Run r = open(host, user, password);
        try {
            return r.runCommand(kind, command, false);
        } finally {
            r.close();
        }
    }

    /**
     * Connect and sign on, returning a live session that stays signed in
     * (persistent-session mode). The password is used only for the sign-on
     * and is not retained. Throws AuthFail on a sign-on rejection.
     */
    public static Tn5250Run open(String host, String user, String password) throws Exception {
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
            String signonScreen = r.text();
            r.scr.sendKeys(KeyMnemonic.ENTER);
            r.waitChanged(signonScreen, 20000);
            String after = r.waitStable(30000);
            // Between sign-on and the initial menu, IBM i may interpose a
            // Display Program Messages screen (messages waiting in the user's
            // message queue) or a "Press ENTER to continue" screen. Step
            // through those — ENTER first, PF3 if ENTER doesn't move it —
            // bounded, until a command line appears or the screen turns out
            // to be something we don't recognize.
            for (int steps = 0; steps < 6 && !after.contains("===>")
                    && !after.contains("Your user name:") && !after.contains("Sign On"); steps++) {
                boolean steppable = after.contains("Display Program Messages")
                        || after.contains("Display Messages")
                        || after.contains("Press ENTER to continue")
                        || after.contains("Press Enter to continue");
                if (!steppable) break;
                String beforeStep = after;
                r.scr.sendKeys(KeyMnemonic.ENTER);
                if (!r.waitChanged(beforeStep, 8000)) {
                    r.scr.sendKeys(KeyMnemonic.PF3);
                    r.waitChanged(beforeStep, 8000);
                }
                after = r.waitStable(15000);
            }
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
            return r;
        } catch (Exception e) {
            try { session.disconnect(); } catch (Exception ignore) {}
            throw e;
        }
    }

    // ---- Interactive terminal (Step 1): screen snapshots + keystrokes ----

    public static class Snapshot {
        public String[] rows;
        public java.util.List<int[]> fieldPos = new ArrayList<>(); // {index, row, col, len}
        public java.util.List<String> fieldVal = new ArrayList<>();
        public java.util.List<Boolean> fieldBypass = new ArrayList<>();
    }

    /** Current screen as text rows plus its input fields (for the web terminal). */
    public synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.rows = text().split("\n", -1);
        ScreenField[] fs = scr.getScreenFields().getFields();
        if (fs != null) {
            for (int i = 0; i < fs.length; i++) {
                s.fieldPos.add(new int[]{i, fs[i].startRow(), fs[i].startCol(), fs[i].getLength()});
                String v = fs[i].getString();
                s.fieldVal.add(v == null ? "" : v);
                s.fieldBypass.add(fs[i].isBypassField());
            }
        }
        return s;
    }

    private static KeyMnemonic mnemonic(String aid) {
        if (aid == null) return KeyMnemonic.ENTER;
        switch (aid.toUpperCase()) {
            case "ENTER": return KeyMnemonic.ENTER;
            case "CLEAR": return KeyMnemonic.CLEAR;
            case "HELP": return KeyMnemonic.HELP;
            case "PRINT": return KeyMnemonic.PRINT;
            case "PAGE_UP": case "ROLL_UP": return KeyMnemonic.PAGE_UP;
            case "PAGE_DOWN": case "ROLL_DOWN": return KeyMnemonic.PAGE_DOWN;
            case "ATTN": return KeyMnemonic.ATTN;
            case "RESET": return KeyMnemonic.RESET;
            default:
                if (aid.toUpperCase().startsWith("PF")) {
                    try {
                        int n = Integer.parseInt(aid.substring(2));
                        switch (n) {
                            case 1: return KeyMnemonic.PF1; case 2: return KeyMnemonic.PF2;
                            case 3: return KeyMnemonic.PF3; case 4: return KeyMnemonic.PF4;
                            case 5: return KeyMnemonic.PF5; case 6: return KeyMnemonic.PF6;
                            case 7: return KeyMnemonic.PF7; case 8: return KeyMnemonic.PF8;
                            case 9: return KeyMnemonic.PF9; case 10: return KeyMnemonic.PF10;
                            case 11: return KeyMnemonic.PF11; case 12: return KeyMnemonic.PF12;
                            case 13: return KeyMnemonic.PF13; case 14: return KeyMnemonic.PF14;
                            case 15: return KeyMnemonic.PF15; case 16: return KeyMnemonic.PF16;
                            case 17: return KeyMnemonic.PF17; case 18: return KeyMnemonic.PF18;
                            case 19: return KeyMnemonic.PF19; case 20: return KeyMnemonic.PF20;
                            case 21: return KeyMnemonic.PF21; case 22: return KeyMnemonic.PF22;
                            case 23: return KeyMnemonic.PF23; case 24: return KeyMnemonic.PF24;
                        }
                    } catch (Exception ignore) {}
                }
                return KeyMnemonic.ENTER;
        }
    }

    /** Apply edited field values, send an AID key, return the new screen. */
    public synchronized Snapshot interact(java.util.Map<Integer, String> values, String aid) throws Exception {
        ScreenField[] fs = scr.getScreenFields().getFields();
        if (fs != null && values != null) {
            for (java.util.Map.Entry<Integer, String> e : values.entrySet()) {
                int i = e.getKey();
                if (i >= 0 && i < fs.length && !fs[i].isBypassField()) {
                    fs[i].setString(e.getValue() == null ? "" : e.getValue());
                }
            }
        }
        String before = text();
        scr.sendKeys(mnemonic(aid));
        waitChanged(before, 5000);
        waitStable(12000);
        return snapshot();
    }

    /** Run one command in this live session; the session stays signed on. */
    public synchronized Result runCommand(String kind, String command, boolean sessionMode) throws Exception {
        // If a previous run left the session inside QSH, exit back to the OS
        // command line first (the QSH screen carries an ===> line of its own,
        // which would fool the check below).
        if (text().contains("QSH Command Entry")) {
            sendLine("exit");
            waitStable(10000);
        }
        // If the terminal left the session inside a display screen, back out
        // with PF3 (up to 3 screens) before expecting a command line.
        if (!text().contains("===>")) {
            for (int i = 0; i < 3 && !text().contains("===>"); i++) {
                scr.sendKeys(KeyMnemonic.PF3);
                waitStable(8000);
            }
            if (!text().contains("===>")) {
                throw new Exception("Session is inside a display screen with no command line. Use F3 in the terminal to exit it first.");
            }
        }
        if ("sql".equals(kind)) {
            String out = runSqlQsh(command);
            Result parsed = parseDb2(out);
            if (parsed != null) return parsed;
            Result plain = new Result();
            plain.text = out;
            return plain;
        }
        if (sessionMode) return runClSmart(command);
        Result plain = new Result();
        plain.text = runCl(command);
        return plain;
    }

    /** Sign off and disconnect (best effort). */
    public void close() {
        try {
            if (text().contains("===>")) {
                scr.sendKeys("SIGNOFF");
                scr.sendKeys(KeyMnemonic.ENTER);
                Thread.sleep(1000);
            }
        } catch (Exception ignore) {}
        try { session.disconnect(); } catch (Exception ignore) {}
    }
}
