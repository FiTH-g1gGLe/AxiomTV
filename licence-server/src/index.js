/**
 * AxiomTV licence server (Cloudflare Worker + D1).
 *
 * The app sends its device ID to /v1/check. The first time, the server gives that device a short
 * code (e.g. 7K3P-9QXD) and a pay link (/pay/<code>) for the QR screen. When Lemon Squeezy reports a
 * paid order carrying that code, or you grant it from /admin, the device becomes active and /v1/check
 * returns a signed licence the app can verify offline with the public key from /v1/pubkey.
 *
 * Secrets (set by the deploy workflow):
 *   LICENCE_SIGNING_KEY         Ed25519 private key as JWK JSON (generated once, never printed)
 *   ADMIN_TOKEN                 password for /admin and the /v1/admin API
 *   LEMONSQUEEZY_WEBHOOK_SECRET signing secret of the Lemon Squeezy webhook (once the store is live)
 */

const CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // no 0/O, 1/I
const LICENCE_DAYS = 30; // the app refreshes on every launch; a revoke takes effect within this window offline
const DEVICE_SALT = "axiomtv:";

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (err) {
      console.error(err && err.stack ? err.stack : err);
      return json({ error: "server_error" }, 500);
    }
  },
};

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";
  const method = request.method;

  if (path === "/" && method === "GET") return json({ service: "axiomtv-licence", ok: true });
  if (path === "/v1/pubkey" && method === "GET") return json({ alg: "Ed25519", key: (await signingKey(env)).publicKey });
  if (path === "/v1/check" && method === "POST") return check(request, env, url);
  if (path.startsWith("/pay/") && method === "GET") return payPage(env, url, path.slice(5));
  if (path === "/v1/webhooks/lemonsqueezy" && method === "POST") return lemonSqueezyWebhook(request, env);
  if (path === "/admin" && method === "GET") return html(ADMIN_PAGE);
  if (path.startsWith("/v1/admin/")) {
    if (!(await isAdmin(request, env))) return json({ error: "unauthorised" }, 401);
    return adminApi(request, env, path.slice(10), method, url);
  }
  return json({ error: "not_found" }, 404);
}

// ---------------------------------------------------------------- app check-in

async function check(request, env, url) {
  const body = await readJson(request);
  const deviceId = typeof body.device_id === "string" ? body.device_id.trim() : "";
  if (deviceId.length < 4 || deviceId.length > 128) return json({ error: "bad_device_id" }, 400);

  const now = Date.now();
  const deviceHash = await sha256Hex(DEVICE_SALT + deviceId);
  const model = clip(body.model, 64);
  const appVersion = clip(body.app_version, 32);

  let device = await env.DB.prepare("SELECT * FROM devices WHERE device_hash = ?").bind(deviceHash).first();
  if (!device) {
    for (let attempt = 0; attempt < 6 && !device; attempt++) {
      const code = randomCode();
      await env.DB.prepare(
        "INSERT OR IGNORE INTO devices (device_hash, code, status, model, app_version, created_at, last_seen) VALUES (?, ?, 'unpaid', ?, ?, ?, ?)",
      ).bind(deviceHash, code, model, appVersion, now, now).run();
      device = await env.DB.prepare("SELECT * FROM devices WHERE device_hash = ?").bind(deviceHash).first();
    }
    if (!device) return json({ error: "server_busy" }, 503);
    await logEvent(env, device.code, "registered", model);
  } else {
    await env.DB.prepare("UPDATE devices SET last_seen = ?, model = COALESCE(?, model), app_version = COALESCE(?, app_version) WHERE device_hash = ?")
      .bind(now, model, appVersion, deviceHash).run();
  }

  const settings = await getSettings(env);
  const result = {
    status: device.status,
    code: device.code,
    pay_url: `${url.origin}/pay/${device.code}`,
    price: settings.price_text || null,
    payments_open: Boolean(settings.checkout_url),
    enforce: settings.paywall_on === "true", // the app only locks when you turn this on in /admin
  };
  if (device.status === "active") result.licence = await signLicence(env, device, now);
  return json(result);
}

async function signLicence(env, device, now) {
  const iat = Math.floor(now / 1000);
  const payload = { v: 1, app: "axiomtv", dh: device.device_hash, c: device.code, s: "active", iat, exp: iat + LICENCE_DAYS * 86400 };
  const body = b64url(new TextEncoder().encode(JSON.stringify(payload)));
  const key = await signingKey(env);
  const sig = await crypto.subtle.sign(key.algorithm, key.privateKey, new TextEncoder().encode(body));
  return `${body}.${b64url(new Uint8Array(sig))}`;
}

let cachedKey = null;
async function signingKey(env) {
  if (cachedKey && cachedKey.source === env.LICENCE_SIGNING_KEY) return cachedKey;
  if (!env.LICENCE_SIGNING_KEY) throw new Error("LICENCE_SIGNING_KEY secret is missing");
  const jwk = JSON.parse(env.LICENCE_SIGNING_KEY);
  let algorithm = { name: "Ed25519" };
  let privateKey;
  try {
    privateKey = await crypto.subtle.importKey("jwk", jwk, algorithm, false, ["sign"]);
  } catch {
    algorithm = { name: "NODE-ED25519", namedCurve: "NODE-ED25519" }; // older Workers runtimes
    privateKey = await crypto.subtle.importKey("jwk", jwk, algorithm, false, ["sign"]);
  }
  cachedKey = { source: env.LICENCE_SIGNING_KEY, privateKey, algorithm, publicKey: jwk.x };
  return cachedKey;
}

// ---------------------------------------------------------------- pay page (QR target)

async function payPage(env, url, rawCode) {
  const code = normaliseCode(rawCode);
  const device = code && (await env.DB.prepare("SELECT code, status FROM devices WHERE code = ?").bind(code).first());
  if (!device) return html(simplePage("Code not found", "Check the code on your AxiomTV screen and scan the QR code again."), 404);
  if (device.status === "active") return html(simplePage("Already unlocked", `AxiomTV on device <b>${device.code}</b> is already unlocked. Restart the app if it still shows the payment screen.`));

  const settings = await getSettings(env);
  if (!settings.checkout_url) {
    return html(simplePage("Payments open soon", `Your device code is<div class="code">${device.code}</div>Send this code to the seller to get AxiomTV unlocked.`));
  }
  const checkout = new URL(settings.checkout_url);
  checkout.searchParams.set("checkout[custom][device_code]", device.code);
  return Response.redirect(checkout.toString(), 302);
}

// ---------------------------------------------------------------- Lemon Squeezy

async function lemonSqueezyWebhook(request, env) {
  const raw = await request.text();
  if (!env.LEMONSQUEEZY_WEBHOOK_SECRET) return json({ error: "webhook_not_configured" }, 503);
  const expected = await hmacHex(env.LEMONSQUEEZY_WEBHOOK_SECRET, raw);
  const given = (request.headers.get("X-Signature") || "").toLowerCase();
  if (!safeEqual(expected, given)) return json({ error: "bad_signature" }, 401);

  let event;
  try {
    event = JSON.parse(raw);
  } catch {
    return json({ error: "bad_json" }, 400);
  }
  const name = event?.meta?.event_name;
  const attrs = event?.data?.attributes || {};
  const orderId = event?.data?.id ? String(event.data.id) : null;
  const code = normaliseCode(event?.meta?.custom_data?.device_code);
  const testMode = Boolean(attrs.test_mode);

  if (name === "order_created") {
    if (attrs.status !== "paid") {
      await logEvent(env, code, "order_not_paid", `${orderId} ${attrs.status}`);
      return json({ ok: true, ignored: "not_paid" });
    }
    const device = code && (await env.DB.prepare("SELECT code FROM devices WHERE code = ?").bind(code).first());
    if (!device) {
      await logEvent(env, code, "payment_unmatched", `order ${orderId} ${attrs.user_email || ""}`);
      return json({ ok: true, ignored: "unknown_code" });
    }
    await env.DB.prepare(
      "UPDATE devices SET status = 'active', source = ?, order_id = ?, email = ?, activated_at = ? WHERE code = ?",
    ).bind(testMode ? "lemonsqueezy-test" : "lemonsqueezy", orderId, clip(attrs.user_email, 200), Date.now(), code).run();
    await logEvent(env, code, testMode ? "paid_test" : "paid", `order ${orderId}`);
    return json({ ok: true, activated: code });
  }

  if (name === "order_refunded") {
    const res = await env.DB.prepare("UPDATE devices SET status = 'revoked' WHERE order_id = ?").bind(orderId).run();
    await logEvent(env, code, "refunded", `order ${orderId} (${res.meta?.changes ?? 0} device)`);
    return json({ ok: true });
  }

  return json({ ok: true, ignored: name || "unknown_event" });
}

// ---------------------------------------------------------------- admin

async function adminApi(request, env, action, method, url) {
  if (action === "devices" && method === "GET") {
    const q = (url.searchParams.get("q") || "").trim();
    const like = `%${q}%`;
    const rows = await env.DB.prepare(
      `SELECT code, status, source, order_id, email, note, model, app_version, created_at, activated_at, last_seen
       FROM devices WHERE ? = '' OR code LIKE ? OR email LIKE ? OR note LIKE ? OR model LIKE ?
       ORDER BY COALESCE(last_seen, created_at) DESC LIMIT 200`,
    ).bind(q, like, like, like, like).all();
    const counts = await env.DB.prepare("SELECT status, COUNT(*) AS n FROM devices GROUP BY status").all();
    return json({ devices: rows.results, counts: Object.fromEntries(counts.results.map((r) => [r.status, r.n])) });
  }
  if (action === "events" && method === "GET") {
    const rows = await env.DB.prepare("SELECT at, code, type, detail FROM events ORDER BY id DESC LIMIT 100").all();
    return json({ events: rows.results });
  }
  if ((action === "grant" || action === "revoke") && method === "POST") {
    const body = await readJson(request);
    const code = normaliseCode(body.code);
    if (!code) return json({ error: "bad_code" }, 400);
    const note = clip(body.note, 200);
    const res = action === "grant"
      ? await env.DB.prepare("UPDATE devices SET status = 'active', source = 'manual', note = COALESCE(?, note), activated_at = ? WHERE code = ?").bind(note, Date.now(), code).run()
      : await env.DB.prepare("UPDATE devices SET status = 'revoked', note = COALESCE(?, note) WHERE code = ?").bind(note, code).run();
    if (!res.meta?.changes) return json({ error: "code_not_found" }, 404);
    await logEvent(env, code, action === "grant" ? "granted" : "revoked", note);
    return json({ ok: true, code, status: action === "grant" ? "active" : "revoked" });
  }
  if (action === "settings" && method === "GET") return json(await getSettings(env));
  if (action === "settings" && method === "POST") {
    const body = await readJson(request);
    if (typeof body.paywall_on === "boolean") {
      await env.DB.prepare("INSERT INTO settings (key, value) VALUES ('paywall_on', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").bind(String(body.paywall_on)).run();
    }
    for (const key of ["checkout_url", "price_text"]) {
      if (typeof body[key] !== "string") continue;
      const value = body[key].trim();
      if (key === "checkout_url" && value && !/^https:\/\/[^\s]+$/.test(value)) return json({ error: "checkout_url must start with https://" }, 400);
      if (value) await env.DB.prepare("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").bind(key, clip(value, 500)).run();
      else await env.DB.prepare("DELETE FROM settings WHERE key = ?").bind(key).run();
    }
    await logEvent(env, null, "settings_changed", typeof body.paywall_on === "boolean" ? `paywall ${body.paywall_on ? "on" : "off"}` : null);
    return json(await getSettings(env));
  }
  return json({ error: "not_found" }, 404);
}

async function isAdmin(request, env) {
  if (!env.ADMIN_TOKEN) return false;
  const given = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
  return safeEqual(await sha256Hex(given), await sha256Hex(env.ADMIN_TOKEN));
}

// ---------------------------------------------------------------- helpers

async function getSettings(env) {
  const rows = await env.DB.prepare("SELECT key, value FROM settings").all();
  return Object.fromEntries(rows.results.map((r) => [r.key, r.value]));
}

async function logEvent(env, code, type, detail) {
  await env.DB.prepare("INSERT INTO events (at, code, type, detail) VALUES (?, ?, ?, ?)").bind(Date.now(), code || null, type, detail || null).run();
}

function randomCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  let s = "";
  for (const b of bytes) s += CODE_ALPHABET[b % CODE_ALPHABET.length];
  return `${s.slice(0, 4)}-${s.slice(4)}`;
}

function normaliseCode(value) {
  if (typeof value !== "string") return null;
  let s;
  try {
    s = decodeURIComponent(value);
  } catch {
    return null;
  }
  s = s.toUpperCase().replace(/[^0-9A-Z]/g, "");
  if (s.length !== 8 || [...s].some((ch) => !CODE_ALPHABET.includes(ch))) return null;
  return `${s.slice(0, 4)}-${s.slice(4)}`;
}

function clip(value, max) {
  return typeof value === "string" && value.trim() ? value.trim().slice(0, max) : null;
}

async function readJson(request) {
  try {
    const body = await request.json();
    return body && typeof body === "object" ? body : {};
  } catch {
    return {};
  }
}

async function sha256Hex(text) {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text))));
}

async function hmacHex(secret, text) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return hex(new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(text))));
}

function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function hex(bytes) {
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function b64url(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" } });
}

function html(body, status = 200) {
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'",
      "referrer-policy": "no-referrer",
    },
  });
}

const PAGE_STYLE = `
  :root { color-scheme: dark; }
  body { margin: 0; min-height: 100vh; font-family: system-ui, sans-serif; color: #e8ecf6;
    background: radial-gradient(circle at 20% 0%, #1d2b55, #0a1024 60%); }
  .card { max-width: 440px; margin: 12vh auto; padding: 32px; border-radius: 18px; text-align: center;
    background: rgba(20, 32, 70, .55); border: 1px solid rgba(255,255,255,.08); backdrop-filter: blur(14px); }
  h1 { margin: 0 0 12px; font-size: 22px; letter-spacing: .5px; }
  .brand { color: #d9b45a; font-weight: 700; letter-spacing: 3px; font-size: 13px; margin-bottom: 18px; }
  .code { font: 700 30px ui-monospace, monospace; letter-spacing: 4px; color: #d9b45a; margin: 16px 0; }
  p { line-height: 1.5; color: #b9c2da; }`;

function simplePage(title, message) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AxiomTV – ${title}</title><style>${PAGE_STYLE}</style></head>
<body><div class="card"><div class="brand">AXIOMTV</div><h1>${title}</h1><p>${message}</p></div></body></html>`;
}

const ADMIN_PAGE = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>AxiomTV licences</title><style>${PAGE_STYLE}
  .wrap { max-width: 1000px; margin: 0 auto; padding: 20px; }
  .panel { background: rgba(20,32,70,.55); border: 1px solid rgba(255,255,255,.08); border-radius: 14px; padding: 16px; margin-bottom: 16px; }
  input, button { font: inherit; border-radius: 8px; border: 1px solid #34436e; background: #101a38; color: inherit; padding: 8px 10px; }
  input { width: 100%; box-sizing: border-box; margin: 4px 0 10px; }
  button { cursor: pointer; background: #24356a; } button.gold { background: #d9b45a; color: #1a1406; border-color: #d9b45a; }
  button.red { background: #6a2430; }
  table { width: 100%; border-collapse: collapse; font-size: 14px; } td, th { padding: 8px 6px; border-bottom: 1px solid rgba(255,255,255,.07); text-align: left; vertical-align: top; }
  .mono { font-family: ui-monospace, monospace; } .muted { color: #8f9abb; font-size: 12px; }
  .active { color: #6fdc8c; } .unpaid { color: #d9b45a; } .revoked { color: #ff7b8a; }
  .row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; } .row input { flex: 1; min-width: 160px; margin: 0; }
  #msg { min-height: 20px; color: #d9b45a; }
</style></head><body><div class="wrap">
<div class="brand" style="margin-top:12px">AXIOMTV · LICENCES</div>
<div class="panel" id="login"><b>Admin password</b><input id="token" type="password" autocomplete="current-password"><button class="gold" onclick="saveToken()">Sign in</button></div>
<div id="app" hidden>
  <div class="panel"><div class="row"><div style="flex:1"><b>Lock the app for unpaid devices</b>
    <div class="muted" id="lockText">Off: everyone can use AxiomTV. Devices still check in, so you can unlock people before turning this on.</div></div>
    <button id="lockBtn" onclick="toggleLock()">Turn on</button></div></div>
  <div class="panel"><b>Grant or revoke a device</b>
    <div class="row" style="margin-top:8px"><input id="code" placeholder="Device code, e.g. 7K3P-9QXD"><input id="note" placeholder="Note (buyer, Discord name…)">
    <button class="gold" onclick="act('grant')">Unlock</button><button class="red" onclick="act('revoke')">Revoke</button></div><div id="msg"></div></div>
  <div class="panel"><b>Payment settings</b>
    <div class="muted">Lemon Squeezy checkout link (leave empty until the store is approved — the QR page then tells buyers to message you their code).</div>
    <input id="checkout_url" placeholder="https://yourstore.lemonsqueezy.com/buy/...">
    <div class="muted">Price shown in the app</div><input id="price_text" placeholder="£10 one-off">
    <button onclick="saveSettings()">Save settings</button></div>
  <div class="panel"><div class="row"><b style="flex:1">Devices <span id="counts" class="muted"></span></b><input id="q" placeholder="Search code, email, note, model" oninput="loadDevices()"></div>
    <table><thead><tr><th>Code</th><th>Status</th><th>Details</th><th>Last seen</th></tr></thead><tbody id="devices"></tbody></table></div>
  <div class="panel"><b>Recent activity</b><table><tbody id="events"></tbody></table></div>
</div>
<script>
let token = sessionStorage.getItem("axiom_admin") || "";
const $ = (id) => document.getElementById(id);
function saveToken() { token = $("token").value; sessionStorage.setItem("axiom_admin", token); start(); }
async function api(path, body) {
  const res = await fetch("/v1/admin/" + path, { method: body ? "POST" : "GET", headers: { "Authorization": "Bearer " + token, "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
  if (res.status === 401) { sessionStorage.removeItem("axiom_admin"); $("login").hidden = false; $("app").hidden = true; throw new Error("Wrong password"); }
  const data = await res.json(); if (!res.ok) throw new Error(data.error || res.status); return data;
}
const when = (t) => t ? new Date(t).toLocaleString() : "–";
function cell(tr, text, cls) { const td = document.createElement("td"); td.textContent = text; if (cls) td.className = cls; tr.appendChild(td); return td; }
async function loadDevices() {
  const data = await api("devices?q=" + encodeURIComponent($("q").value));
  $("counts").textContent = Object.entries(data.counts).map(([k, v]) => v + " " + k).join(" · ");
  const body = $("devices"); body.replaceChildren();
  for (const d of data.devices) {
    const tr = document.createElement("tr");
    const c = cell(tr, d.code, "mono"); c.style.cursor = "pointer"; c.title = "Use this code"; c.onclick = () => { $("code").value = d.code; };
    cell(tr, d.status, d.status);
    cell(tr, [d.model, d.app_version && "v" + d.app_version, d.source, d.email, d.note, d.order_id && "order " + d.order_id].filter(Boolean).join(" · "));
    cell(tr, when(d.last_seen), "muted");
    body.appendChild(tr);
  }
}
async function loadEvents() {
  const data = await api("events"); const body = $("events"); body.replaceChildren();
  for (const e of data.events) { const tr = document.createElement("tr"); cell(tr, when(e.at), "muted"); cell(tr, e.code || "", "mono"); cell(tr, e.type); cell(tr, e.detail || "", "muted"); body.appendChild(tr); }
}
async function act(kind) {
  try { const r = await api(kind, { code: $("code").value, note: $("note").value }); $("msg").textContent = r.code + " is now " + r.status; loadDevices(); loadEvents(); }
  catch (e) { $("msg").textContent = e.message === "code_not_found" ? "No device with that code yet — open AxiomTV on the box first." : e.message; }
}
let lockOn = false;
function showLock() {
  $("lockBtn").textContent = lockOn ? "Turn off" : "Turn on"; $("lockBtn").className = lockOn ? "red" : "gold";
  $("lockText").textContent = lockOn ? "On: devices that aren't unlocked see the payment screen." : "Off: everyone can use AxiomTV. Devices still check in, so you can unlock people before turning this on.";
}
async function toggleLock() {
  if (!lockOn && !confirmLock()) return;
  try { const s = await api("settings", { paywall_on: !lockOn }); lockOn = s.paywall_on === "true"; showLock(); loadEvents(); } catch (e) { $("msg").textContent = e.message; }
}
function confirmLock() { const b = $("lockBtn"); if (b.dataset.sure) { delete b.dataset.sure; return true; } b.dataset.sure = "1"; b.textContent = "Tap again to lock"; return false; }
async function loadSettings() { const s = await api("settings"); $("checkout_url").value = s.checkout_url || ""; $("price_text").value = s.price_text || ""; lockOn = s.paywall_on === "true"; showLock(); }
async function saveSettings() {
  try { await api("settings", { checkout_url: $("checkout_url").value, price_text: $("price_text").value }); $("msg").textContent = "Settings saved"; }
  catch (e) { $("msg").textContent = e.message; }
}
async function start() {
  if (!token) return;
  try { await loadSettings(); $("login").hidden = true; $("app").hidden = false; loadDevices(); loadEvents(); }
  catch (e) { $("token").placeholder = e.message; }
}
start();
</script></div></body></html>`;
