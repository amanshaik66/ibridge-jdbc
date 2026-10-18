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
 * SQL runs through QSH's db2 command inside the session.
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

    public static String run(String host, String user, String password, String kind, String command) throws Exception {
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
            // Sign-on screen
            if (!r.waitFor("Sign On", 45000)) {
                throw new Exception("5250 sign-on screen did not appear. Last screen: " + trimBlock(r.text()));
            }
            r.waitStable(5000);
            ScreenField[] f = r.scr.getScreenFields().getFields();
            if (f == null || f.length < 2) throw new Exception("Sign-on fields not found.");
            f[0].setString(user);
            f[1].setString(password);
            r.scr.sendKeys(KeyMnemonic.ENTER);
            String after = r.waitStable(30000);
            if (after.contains("Sign On")) {
                String msg = lastNonBlank(after);
                throw new AuthFail(msg.isEmpty() ? "Sign-on rejected by the host." : msg);
            }
            if (!after.contains("===>")) {
                throw new Exception("Signed on, but no command line found. Last screen: " + trimBlock(after));
            }

            StringBuilder transcript = new StringBuilder();
            if ("sql".equals(kind)) {
                r.scr.sendKeys("QSH");
                r.scr.sendKeys(KeyMnemonic.ENTER);
                String qsh = r.waitStable(20000);
                if (!qsh.contains("$")) {
                    throw new Exception("QSH did not start. Screen: " + trimBlock(qsh));
                }
                String sql = command.replace("\"", "");
                r.scr.sendKeys("db2 \"" + sql + "\"");
                r.scr.sendKeys(KeyMnemonic.ENTER);
                String out = r.waitStable(45000);
                transcript.append(trimBlock(out));
                r.scr.sendKeys("exit");
                r.scr.sendKeys(KeyMnemonic.ENTER);
                r.waitStable(10000);
            } else {
                r.scr.sendKeys(command);
                r.scr.sendKeys(KeyMnemonic.ENTER);
                String prev = "";
                for (int i = 0; i < 10; i++) {
                    String t = r.waitStable(20000);
                    if (!t.equals(prev)) {
                        if (transcript.length() > 0) transcript.append("\n");
                        transcript.append(trimBlock(t));
                        prev = t;
                    }
                    if (t.contains("===>") && i > 0) break;
                    if (t.contains("===>") && i == 0) break;
                    if (t.contains("Press ENTER to continue")) {
                        r.scr.sendKeys(KeyMnemonic.ENTER);
                    } else if (t.contains("F3=Exit")) {
                        r.scr.sendKeys(KeyMnemonic.PF3);
                    } else {
                        r.scr.sendKeys(KeyMnemonic.ENTER);
                    }
                }
            }
            // Sign off (best effort)
            try {
                if (r.text().contains("===>")) {
                    r.scr.sendKeys("SIGNOFF");
                    r.scr.sendKeys(KeyMnemonic.ENTER);
                    Thread.sleep(1000);
                }
            } catch (Exception ignore) {}
            String out = transcript.toString().trim();
            return out.isEmpty() ? trimBlock(r.text()) : out;
        } finally {
            try { session.disconnect(); } catch (Exception ignore) {}
        }
    }
}
