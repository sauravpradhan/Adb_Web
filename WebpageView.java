/** Static web UI served by AdbBrowserHelper. */
final class  WebpageView {
    private WebpageView() {}

    static final String HTML = """
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
}
