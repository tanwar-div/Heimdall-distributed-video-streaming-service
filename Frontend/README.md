# Heimdall Dashboard

A plain HTML/CSS/JS dashboard for the Heimdall gateway — no framework, no
build step, no npm install. Upload and play videos, watch which primary/
replicas served a request, and see live traffic metrics and cluster health.

## Run it

1. **Start Heimdall** (the gateway + storage-node cluster + MinIO) first —
   either `docker compose up --build` from the `heimdall-simple/` root, or
   the local multi-JVM setup documented in the main `README.md`. Either way,
   the gateway needs to be reachable at the URL configured in
   `js/config.js` (`http://localhost:8080` by default).

2. **Serve this folder** with any static file server — opening the HTML
   files directly via `file://` will *not* work, because `fetch()` calls to
   a different origin are blocked under the `file://` scheme regardless of
   CORS headers. From this directory:

   ```bash
   python3 -m http.server 5500
   # or: npx serve .
   ```

   Then open `http://localhost:5500/login.html`.

3. **Log in** with the hardcoded credentials in `js/config.js`
   (`admin` / `heimdall123` by default). This is a presentation-layer gate
   only — see "About the auth" below.

## Pages

| Page | What it does |
|---|---|
| `login.html` | Hardcoded-credential gate; sets a `sessionStorage` flag other pages check. |
| `dashboard.html` | Stat cards + bar charts (uploads/reads per primary) + a live node-health panel, all polling the gateway's `/metrics/summary` and `/cluster/health` every 3s. |
| `upload.html` | Upload a video file; shows which primary it landed on and its chunk count/size. |
| `library.html` | Lists every object in the cluster; **Play** streams it through a real `<video>` element (so seeking is a genuine HTTP `Range` request handled by the browser, not a fake progress bar); **Info** shows the exact routing decision (`/objects/{id}/replicas`); **Delete** removes it everywhere. |

## Configuration

Everything environment-specific lives in `js/config.js`:

```js
const HEIMDALL_CONFIG = {
  GATEWAY_BASE_URL: "http://localhost:8080",
  API_KEY: "changeme",       // must match heimdall.api-key on the gateway
  LOGIN_USERNAME: "admin",
  LOGIN_PASSWORD: "heimdall123",
};
```

## About the auth

The login screen is a client-side-only check (`js/auth.js`) — it's meant to
gate casual access to the *dashboard UI* for a demo, not to be real
security. It doesn't touch the backend at all. Actual write protection comes
from Heimdall's own `X-API-Key` requirement on `POST`/`DELETE /objects/**`,
which this dashboard satisfies using the key configured above — the same
mechanism a `curl` request would use.

## About CORS

The gateway's `WebConfig` allows any origin (`*`) on `/objects/**`,
`/cluster/**`, and `/metrics/**` so this dashboard can call it from a
different port. That's fine for a local demo; a real deployment would
restrict it to the dashboard's actual origin.
