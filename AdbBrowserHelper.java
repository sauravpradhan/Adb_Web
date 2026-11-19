import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AdbBrowserHelper {
    private static final int PORT = Integer.getInteger("port", 8765);
    private static final int MAX_OUTPUT = 1_000_000;
    private static final Set<String> ORIGINS =
        Set.of("http://127.0.0.1:" + PORT, "http://localhost:" + PORT);

    // Random per-run secret. Only someone who sees the console URL has it.
    // Make it more secure later
    private static final String TOKEN = newToken();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/", AdbBrowserHelper::handlePage);
        server.createContext("/api/adb", AdbBrowserHelper::handleAdb);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Open http://127.0.0.1:" + PORT + "/?token=" + TOKEN);
    }

    // ---- handlers ---------------------------------------------------------

    /** UI page: needs ?token=... and a valid Host header. */
    private static void handlePage(HttpExchange ex) throws IOException {
        if (!hostOk(ex)) { send(ex, 403, "text/plain", "Bad Host"); return; }
        if (!"/".equals(ex.getRequestURI().getPath())) {
            send(ex, 404, "text/plain", "Not found");
            return;
        }
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || !tokenEquals(q.startsWith("token=") ? q.substring(6) : "")) {
            send(ex, 403, "text/plain",
                "Open the full URL printed by the helper (includes ?token=...)");
            return;
        }
        send(ex, 200, "text/html", WebpageView.HTML.replace("__TOKEN__", TOKEN));
    }

    /** Runs one adb command (also used for "devices -l"). */
    private static void handleAdb(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "POST only");
            return;
        }
        List<String> argv = tokenize(
            new String(ex.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8));
        if (argv.isEmpty()) {
            send(ex, 400, "text/plain", "Empty command");
            return;
        }
        try {
            send(ex, 200, "text/plain", runAdb(argv));
        } catch (IOException e) {
            send(ex, 500, "text/plain",
                "Could not start ADB. Check that adb is installed and on PATH.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(ex, 500, "text/plain", "Request interrupted");
        }
    }

    /** Runs `adb <args>` (argument list, never a shell string); returns capped output. */
    private static String runAdb(List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("adb"));
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close(); // no stdin: interactive commands exit

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[8192];
            try (var in = p.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() < MAX_OUTPUT) out.write(buf, 0, n);
                }
            } catch (IOException ignored) {
            }
        });
        reader.start();

        boolean done = p.waitFor(30, TimeUnit.SECONDS);
        if (!done) p.destroyForcibly();
        reader.join(2000);

        String text = out.toString(StandardCharsets.UTF_8);
        if (out.size() >= MAX_OUTPUT) text += "\n[output truncated]";
        if (!done) text += "\n[timed out after 30s]";
        return text.isEmpty() ? "(no output)" : text;
    }

    // ---- security ---------------------------------------------------------

    private static String newToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static boolean tokenEquals(String given) {
        return MessageDigest.isEqual(
            given.getBytes(StandardCharsets.UTF_8),
            TOKEN.getBytes(StandardCharsets.UTF_8));
    }

    /** Blocks DNS rebinding: the Host header must be our own loopback name. */
    private static boolean hostOk(HttpExchange ex) {
        return ORIGINS.contains("http://" + ex.getRequestHeaders().getFirst("Host"));
    }

    /** Host + Origin (if sent) + token. Sends the error response itself. */
    private static boolean authorized(HttpExchange ex) throws IOException {
        if (!hostOk(ex)) { send(ex, 403, "text/plain", "Bad Host"); return false; }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !ORIGINS.contains(origin)) {
            send(ex, 403, "text/plain", "Bad Origin");
            return false;
        }
        String token = ex.getRequestHeaders().getFirst("X-Token");
        if (token == null || !tokenEquals(token)) {
            send(ex, 401, "text/plain", "Missing or invalid token");
            return false;
        }
        return true;
    }

    // ---- helpers ----------------------------------------------------------

    /** Splits on whitespace; "double" and 'single' quotes group words. */
    static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean has = false;
        for (char c : line.trim().toCharArray()) {
            if (quote != 0) {
                if (c == quote) quote = 0; else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c; has = true;
            } else if (Character.isWhitespace(c)) {
                if (has || cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); has = false; }
            } else {
                cur.append(c);
            }
        }
        if (has || cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static void send(HttpExchange ex, int status, String type, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
