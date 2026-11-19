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
            const $ = id => document.getElementById(id);

            async function adb(cmd) {
              try {
                const r = await fetch('/api/adb', {
                  method: 'POST', headers: {'X-Token': TOKEN}, body: cmd
                });
                return await r.text();
              } catch (e) { return 'Request failed: ' + e; }
            }

            async function loadDevices() {
              $('rows').textContent = '';
              $('msg').textContent = 'Loading...';
              const text = await adb('devices -l');
              if (!text.includes('List of devices')) { $('msg').textContent = text; return; }
              const lines = text.split('\\n').map(l => l.trim())
                .filter(l => l && !l.startsWith('List of') && !l.startsWith('*'));
              $('msg').textContent = lines.length ? '' : 'No devices found.';
              for (const line of lines) {
                const [serial, state, ...props] = line.split(/\\s+/);
                const get = k => (props.find(p => p.startsWith(k + ':')) || '').slice(k.length + 1);
                const tr = $('rows').insertRow();
                for (const v of [serial, state, get('model'), get('product')]) {
                  tr.insertCell().textContent = v;
                }
              }
            }

            async function runCmd(e) {
              e.preventDefault();
              $('out').textContent = 'Running...';
              $('out').textContent = await adb($('cmd').value);
            }

            loadDevices();
          </script>
        </html>
        """;
}
