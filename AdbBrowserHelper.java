import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AdbBrowserHelper {
    private static final int PORT = Integer.getInteger("port", 8765);
    private static final String ORIGIN_IP = "http://127.0.0.1:" + PORT;
    private static final String ORIGIN_NAME = "http://localhost:" + PORT;

    // Random per-run secret. Only someone who sees the console URL has it.
    // Make it more secure later  
    private static final String TOKEN = newToken();

    private static final String PAGE = """
        <!doctype html>
        <html>
          <meta charset="utf-8">
          <title>ADB Browser</title>
          <h2>Connected Android devices</h2>
          <button onclick="loadDevices()">Refresh</button>
          <table border="1" cellpadding="6" style="margin-top:8px">
            <thead><tr><th>Serial</th><th>State</th><th>Model</th><th>Product</th></tr></thead>
            <tbody id="rows"></tbody>
          </table>
          <p id="msg"></p>
          <h3>Run adb command</h3>
          <form onsubmit="runCmd(event)">
            adb <input id="cmd" size="60" placeholder="shell getprop ro.product.model" autofocus>
            <button>Run</button>
          </form>
          <pre id="out"></pre>
          <script>
            const TOKEN = "__TOKEN__";
            async function loadDevices() {
              const msg = document.getElementById('msg');
              const rows = document.getElementById('rows');
              rows.textContent = '';
              msg.textContent = 'Loading...';
              try {
                const r = await fetch('/api/devices', {headers: {'X-Token': TOKEN}});
                if (!r.ok) { msg.textContent = 'Error ' + r.status + ': ' + await r.text(); return; }
                const devices = await r.json();
                msg.textContent = devices.length ? '' : 'No devices found.';
                for (const d of devices) {
                  const tr = rows.insertRow();
                  for (const v of [d.serial, d.state, d.model, d.product]) {
                    tr.insertCell().textContent = v ?? '';
                  }
                }
              } catch (e) { msg.textContent = 'Request failed: ' + e; }
            }
            async function runCmd(e) {
              e.preventDefault();
              const out = document.getElementById('out');
              out.textContent = 'Running...';
              try {
                const r = await fetch('/api/adb', {
                  method: 'POST',
                  headers: {'X-Token': TOKEN},
                  body: document.getElementById('cmd').value
                });
                out.textContent = await r.text();
              } catch (err) { out.textContent = 'Request failed: ' + err; }
            }
            loadDevices();
          </script>
        </html>
        """;

    public static void main(String[] args) throws Exception {
        HttpServer server =
            HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);

        // UI page: needs ?token=... and a valid Host header.
        server.createContext("/", exchange -> {
            if (!hostOk(exchange)) { send(exchange, 403, "text/plain", "Bad Host"); return; }
            if (!"/".equals(exchange.getRequestURI().getPath())) {
                send(exchange, 404, "text/plain", "Not found");
                return;
            }
            String q = exchange.getRequestURI().getRawQuery();
            if (q == null || !tokenEquals(q.startsWith("token=") ? q.substring(6) : "")) {
                send(exchange, 403, "text/plain",
                    "Open the full URL printed by the helper (includes ?token=...)");
                return;
            }
            send(exchange, 200, "text/html", PAGE.replace("__TOKEN__", TOKEN));
        });

        server.createContext("/api/devices", exchange -> {
            if (!authorized(exchange)) return;
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain", "GET only");
                return;
            }
            try {
                Process adb = new ProcessBuilder("adb", "devices", "-l")
                    .redirectErrorStream(true)
                    .start();

                ByteArrayOutputStream output = new ByteArrayOutputStream();
                Thread reader = new Thread(() -> {
                    try {
                        adb.getInputStream().transferTo(output);
                    } catch (IOException ignored) {
                    }
                });
                reader.start();

                if (!adb.waitFor(10, TimeUnit.SECONDS)) {
                    adb.destroyForcibly();
                    send(exchange, 504, "text/plain", "ADB timed out");
                    return;
                }
                reader.join();

                String text = output.toString(StandardCharsets.UTF_8);
                send(exchange, 200, "application/json", devicesToJson(text));

            } catch (IOException e) {
                send(exchange, 500, "text/plain",
                    "Could not start ADB. Check that adb is installed and on PATH.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                send(exchange, 500, "text/plain", "Request interrupted");
            }
        });

        server.createContext("/api/adb", exchange -> {
            if (!authorized(exchange)) return;
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain", "POST only");
                return;
            }
            try {
                String line = new String(
                    exchange.getRequestBody().readNBytes(4096), StandardCharsets.UTF_8);
                List<String> argv = tokenize(line);
                if (argv.isEmpty()) {
                    send(exchange, 400, "text/plain", "Empty command");
                    return;
                }
                List<String> cmd = new ArrayList<>();
                cmd.add("adb");
                cmd.addAll(argv); // argument list, never a shell string

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
                send(exchange, 200, "text/plain", text.isEmpty() ? "(no output)" : text);
            } catch (IOException e) {
                send(exchange, 500, "text/plain",
                    "Could not start ADB. Check that adb is installed and on PATH.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                send(exchange, 500, "text/plain", "Request interrupted");
            }
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Open " + ORIGIN_IP + "/?token=" + TOKEN);
    }

    // ---- security helpers -------------------------------------------------

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
        String host = ex.getRequestHeaders().getFirst("Host");
        return host != null
            && (host.equals("127.0.0.1:" + PORT) || host.equals("localhost:" + PORT));
    }

    /** Host + Origin (if sent) + token. Sends the error response itself. */
    private static boolean authorized(HttpExchange ex) throws IOException {
        if (!hostOk(ex)) { send(ex, 403, "text/plain", "Bad Host"); return false; }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.equals(ORIGIN_IP) && !origin.equals(ORIGIN_NAME)) {
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

    // ---- command parsing --------------------------------------------------

    private static final int MAX_OUTPUT = 1_000_000;

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

    // ---- adb output -> JSON ----------------------------------------------

    static String devicesToJson(String adbOutput) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String line : List.of(adbOutput.split("\\R"))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List of devices")
                || line.startsWith("*")) continue; // header / daemon messages
            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;

            if (!first) sb.append(',');
            first = false;
            sb.append("{\"serial\":").append(q(parts[0]))
              .append(",\"state\":").append(q(parts[1]));
            for (int i = 2; i < parts.length; i++) {
                int c = parts[i].indexOf(':');
                if (c > 0) {
                    sb.append(',').append(q(parts[i].substring(0, c)))
                      .append(':').append(q(parts[i].substring(c + 1)));
                }
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }

    // ---- response ---------------------------------------------------------

    private static void send(
        HttpExchange exchange, int status, String type, String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
            "Content-Type", type + "; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }
}
