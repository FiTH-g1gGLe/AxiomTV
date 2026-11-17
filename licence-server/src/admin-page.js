// The admin page at /admin. One self-contained page: tabs for Devices, Activity, Reports and Settings.
// Written with String.raw so the script inside needs no extra escaping. It must not contain backticks or "${".
export const ADMIN_PAGE = String.raw`<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="robots" content="noindex"><meta name="theme-color" content="#0a1024">
<title>AxiomTV admin</title>
<style>
:root { color-scheme: dark; --bg: #0a1024; --panel: #121c3d; --panel-2: #18244c; --line: rgba(255,255,255,.08);
  --text: #e8ecf6; --muted: #8f9abb; --gold: #d9b45a; --green: #6fdc8c; --amber: #ffb454; --red: #ff7b8a; --blue: #6fb6ff; --purple: #c792ff; }
* { box-sizing: border-box; }
body { margin: 0; min-height: 100vh; font: 15px/1.45 system-ui, -apple-system, "Segoe UI", sans-serif; color: var(--text);
  background: radial-gradient(1200px 600px at 10% -10%, #1d2b55, transparent 70%), var(--bg); }
button, input { font: inherit; color: inherit; }
button { cursor: pointer; border: 0; border-radius: 10px; padding: 9px 14px; background: var(--panel-2); font-weight: 600; }
button:hover { filter: brightness(1.15); }
button.gold { background: var(--gold); color: #1a1406; } button.red { background: rgba(255,123,138,.16); color: var(--red); }
button.ghost { background: transparent; border: 1px solid var(--line); }
button.small { padding: 6px 11px; font-size: 13px; border-radius: 8px; }
input[type=text], input[type=password], input[type=number], input[type=search], input:not([type]) { width: 100%; padding: 10px 12px; border-radius: 10px;
  border: 1px solid var(--line); background: rgba(5,10,26,.6); outline: none; }
input:focus { border-color: var(--gold); }
.mono { font-family: ui-monospace, "SF Mono", Menlo, monospace; letter-spacing: .5px; }
.muted { color: var(--muted); } .small-text { font-size: 13px; }

/* Header and tabs */
.top { position: sticky; top: 0; z-index: 5; background: rgba(10,16,36,.86); backdrop-filter: blur(12px); border-bottom: 1px solid var(--line); }
.top-in { max-width: 1100px; margin: 0 auto; padding: 12px 16px 0; }
.bar { display: flex; align-items: center; gap: 10px; }
.logo { font-weight: 800; letter-spacing: 3px; color: var(--gold); font-size: 15px; }
.logo span { color: var(--muted); font-weight: 600; letter-spacing: 1px; margin-left: 6px; font-size: 13px; }
.bar .grow { flex: 1; }
.lock-chip { display: inline-flex; align-items: center; gap: 7px; font-size: 13px; padding: 6px 12px; border-radius: 99px; background: var(--panel-2); }
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--muted); display: inline-block; }
.tabs { display: flex; gap: 4px; margin-top: 10px; overflow-x: auto; scrollbar-width: none; }
.tab { background: none; border-radius: 0; padding: 10px 12px; color: var(--muted); border-bottom: 2px solid transparent; font-weight: 600; white-space: nowrap; }
.tab.on { color: var(--text); border-bottom-color: var(--gold); }
.tab .count { font-size: 11px; background: var(--panel-2); border-radius: 99px; padding: 1px 7px; margin-left: 4px; color: var(--muted); }

main { max-width: 1100px; margin: 0 auto; padding: 16px 16px 90px; }
.view { display: none; } .view.on { display: block; }
.card { background: var(--panel); border: 1px solid var(--line); border-radius: 16px; padding: 16px; margin-bottom: 14px; }
.card h2 { font-size: 16px; margin: 0 0 4px; } .card p { margin: 0 0 12px; color: var(--muted); font-size: 13px; }

/* Stats */
.stats { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 10px; margin-bottom: 14px; }
@media (min-width: 760px) { .stats { grid-template-columns: repeat(4, minmax(0,1fr)); } }
.stat { text-align: left; background: var(--panel); border: 1px solid var(--line); border-radius: 14px; padding: 12px 14px; }
.stat.on { border-color: var(--gold); }
.stat .n { font-size: 26px; font-weight: 800; line-height: 1.1; margin-top: 2px; }
.stat .l { font-size: 12px; color: var(--muted); font-weight: 600; }

.toolbar { display: flex; gap: 8px; margin-bottom: 12px; } .toolbar input { flex: 1; }
.form-row { display: flex; gap: 8px; flex-wrap: wrap; } .form-row input { flex: 1; min-width: 150px; }

/* Tables: real headers on wide screens, stacked rows on phones */
.table { background: var(--panel); border: 1px solid var(--line); border-radius: 16px; overflow: hidden; }
.thead, .tr { display: grid; gap: 10px; align-items: center; padding: 11px 16px; }
.thead { font-size: 11px; font-weight: 700; letter-spacing: .8px; color: var(--muted); background: rgba(255,255,255,.03); border-bottom: 1px solid var(--line); }
.tr { border-bottom: 1px solid var(--line); } .tr:last-child { border-bottom: 0; }
.tr.click { cursor: pointer; } .tr.click:hover { background: rgba(255,255,255,.025); }
.empty { padding: 28px 16px; text-align: center; color: var(--muted); }
.cols-dev { grid-template-columns: 120px 170px minmax(0,1.4fr) 80px 110px; }
.cols-act { grid-template-columns: 130px 110px 150px minmax(0,1fr); }
.cell-sub { font-size: 12px; color: var(--muted); }
.ellip { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.right { text-align: right; }
@media (max-width: 759px) {
  .thead { display: none; }
  .cols-dev { grid-template-columns: minmax(0,1fr) auto; grid-template-areas: "code status" "model seen" "note note"; row-gap: 3px; }
  .cols-dev .c-code { grid-area: code; } .cols-dev .c-status { grid-area: status; justify-self: end; }
  .cols-dev .c-model { grid-area: model; } .cols-dev .c-seen { grid-area: seen; text-align: right; }
  .cols-dev .c-ver { display: none; } .cols-dev .c-note { grid-area: note; }
  .cols-act { grid-template-columns: minmax(0,1fr) auto; grid-template-areas: "event when" "code code" "detail detail"; row-gap: 3px; }
  .cols-act .c-when { grid-area: when; text-align: right; } .cols-act .c-code { grid-area: code; }
  .cols-act .c-event { grid-area: event; } .cols-act .c-detail { grid-area: detail; }
}
@media (min-width: 760px) { .cols-dev .c-note { display: none; } .cols-dev .c-model .note-inline { display: block; } }
.code { font: 700 15px ui-monospace, "SF Mono", Menlo, monospace; letter-spacing: 1px; }
.note-inline { display: none; color: var(--gold); font-size: 12px; }
.c-note { color: var(--gold); font-size: 12px; }

/* Pills */
.pill { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 700; padding: 3px 10px; border-radius: 99px; white-space: nowrap; }
.p-green { background: rgba(111,220,140,.14); color: var(--green); } .p-amber { background: rgba(255,180,84,.14); color: var(--amber); }
.p-red { background: rgba(255,123,138,.14); color: var(--red); } .p-blue { background: rgba(111,182,255,.14); color: var(--blue); }
.p-purple { background: rgba(199,146,255,.14); color: var(--purple); } .p-grey { background: rgba(255,255,255,.07); color: var(--muted); }
.p-gold { background: rgba(217,180,90,.16); color: var(--gold); }

/* Device details */
.manage { grid-column: 1 / -1; margin-top: 10px; padding: 14px; border-radius: 12px; background: rgba(5,10,26,.55); cursor: default; }
.facts { display: grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 10px 16px; margin-bottom: 14px; }
.fact .k { font-size: 11px; color: var(--muted); font-weight: 700; letter-spacing: .6px; } .fact .v { font-size: 14px; word-break: break-word; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.sub-h { font-size: 12px; color: var(--muted); font-weight: 700; margin: 12px 0 6px; }

/* Reports */
details.rep { border-bottom: 1px solid var(--line); } details.rep:last-child { border-bottom: 0; }
details.rep summary { list-style: none; cursor: pointer; padding: 12px 16px; display: grid; grid-template-columns: auto minmax(0,1fr) auto; gap: 10px; align-items: center; }
details.rep summary::-webkit-details-marker { display: none; }
details.rep .body { padding: 0 16px 14px; }
pre { white-space: pre-wrap; word-break: break-word; font-size: 12px; max-height: 340px; overflow: auto; background: rgba(5,10,26,.6); padding: 12px; border-radius: 10px; margin: 10px 0 0; }

/* Settings */
.switch-row { display: flex; align-items: center; gap: 14px; }
.switch-row .grow { flex: 1; }
.switch { width: 52px; height: 30px; border-radius: 99px; background: var(--panel-2); position: relative; padding: 0; flex: none; }
.switch::after { content: ""; position: absolute; top: 4px; left: 4px; width: 22px; height: 22px; border-radius: 50%; background: var(--muted); transition: left .15s; }
.switch.on { background: var(--gold); } .switch.on::after { left: 26px; background: #1a1406; }
.field { margin-bottom: 12px; } .field label { display: block; font-size: 12px; color: var(--muted); font-weight: 700; margin-bottom: 5px; }
.check { display: flex; gap: 10px; align-items: flex-start; font-size: 14px; margin: 4px 0 14px; } .check input { margin-top: 3px; }

/* Sign in and toast */
.login { max-width: 380px; margin: 16vh auto; padding: 0 16px; }
.login .card { padding: 26px; text-align: center; } .login .logo { font-size: 18px; margin-bottom: 18px; display: block; }
.login input { margin-bottom: 12px; text-align: center; } .login button { width: 100%; }
#toast { position: fixed; left: 50%; bottom: 22px; transform: translate(-50%, 20px); opacity: 0; transition: .2s; z-index: 10;
  background: #f1e3bb; color: #1a1406; font-weight: 600; padding: 11px 18px; border-radius: 12px; max-width: calc(100% - 32px); box-shadow: 0 8px 30px rgba(0,0,0,.4); }
#toast.show { opacity: 1; transform: translate(-50%, 0); } #toast.bad { background: #ffd0d6; color: #4a0d16; }
</style></head><body>

<div class="login" id="login"><div class="card">
  <span class="logo">AXIOMTV<span>Admin</span></span>
  <input id="token" type="password" placeholder="Admin password" autocomplete="current-password">
  <button class="gold" id="signIn">Sign in</button>
  <div class="muted small-text" id="loginMsg" style="margin-top:10px"></div>
</div></div>

<div id="app" hidden>
<header class="top"><div class="top-in">
  <div class="bar">
    <div class="logo">AXIOMTV<span>Admin</span></div><div class="grow"></div>
    <button class="lock-chip" id="lockChip" title="Paywall"><span class="dot" id="lockDot"></span><span id="lockChipText">Paywall off</span></button>
  </div>
  <nav class="tabs">
    <button class="tab on" data-view="devices">Devices</button>
    <button class="tab" data-view="activity">Activity</button>
    <button class="tab" data-view="reports">Reports<span class="count" id="repCount">0</span></button>
    <button class="tab" data-view="settings">Settings</button>
  </nav>
</div></header>

<main>
  <section class="view on" id="v-devices">
    <div class="stats" id="stats"></div>
    <div class="toolbar">
      <input id="q" type="search" placeholder="Search code, email, note or model">
      <button class="gold" id="unlockToggle">Unlock</button>
    </div>
    <div class="card" id="unlockCard" hidden>
      <h2>Unlock a device</h2><p>For buyers who paid you directly. The device must have opened AxiomTV once.</p>
      <div class="form-row">
        <input id="code" class="codebox mono" placeholder="Device code" autocapitalize="characters" autocomplete="off" maxlength="9">
        <input id="note" placeholder="Note (buyer, Discord name)">
        <button class="gold" id="grantBtn">Unlock device</button>
      </div>
    </div>
    <div class="table">
      <div class="thead cols-dev"><div>DEVICE CODE</div><div>STATUS</div><div>DEVICE</div><div>VERSION</div><div class="right">LAST SEEN</div></div>
      <div id="devices"></div>
    </div>
  </section>

  <section class="view" id="v-activity">
    <div class="table">
      <div class="thead cols-act"><div>WHEN</div><div>DEVICE</div><div>EVENT</div><div>DETAILS</div></div>
      <div id="events"></div>
    </div>
  </section>

  <section class="view" id="v-reports">
    <div class="card">
      <h2>Diagnostics link for Claude</h2>
      <p>A private, read-only link to these reports, with no emails or orders. Paste it into your Claude chat. Make a new one any time.</p>
      <div class="form-row" id="diagBox"></div>
    </div>
    <div class="toolbar" style="justify-content:flex-end">
      <button class="small ghost" id="copyAll">Copy all</button><button class="small red" id="clearAll">Clear all</button>
    </div>
    <div class="table" id="crashes"></div>
  </section>

  <section class="view" id="v-settings">
    <div class="card"><div class="switch-row">
      <div class="grow"><h2>Paywall</h2><p id="lockText" style="margin:0"></p></div>
      <button class="switch" id="lockSwitch" aria-label="Paywall"></button>
    </div></div>
    <div class="card">
      <h2>Payments</h2><p>The checkout link the QR code opens. A Stripe payment link or a Lemon Squeezy checkout link.</p>
      <div class="field"><label for="checkout_url">Checkout link</label><input id="checkout_url" placeholder="https://buy.stripe.com/..."></div>
      <div class="field"><label for="price_text">Price shown in the app</label><input id="price_text" placeholder="£7.99 one-off"></div>
      <div class="field"><label for="trial_days">Free trial (days, 0 for none)</label><input id="trial_days" type="number" min="0" max="60" step="1"></div>
      <label class="check"><input id="early_bird" type="checkbox"><span>Early bird offer running<br><span class="muted small-text">Devices unlocked while this is ticked get the Early Bird role on Discord.</span></span></label>
      <button class="gold" id="saveSettings">Save settings</button>
    </div>
    <div class="card"><h2>Sign out</h2><p>Forget the password on this browser.</p><button class="ghost" id="signOut">Sign out</button></div>
  </section>
</main>
</div>
<div id="toast"></div>

<script>
var token = sessionStorage.getItem("axiom_admin") || "";
var $ = function (id) { return document.getElementById(id); };
var lockOn = false, openCode = null, filter = "all", allDevices = [], lastReports = [];

function el(tag, text, cls) { var n = document.createElement(tag); if (text != null) n.textContent = text; if (cls) n.className = cls; return n; }
function btn(text, cls, fn) { var b = el("button", text, (cls || "") + " small"); b.onclick = function (e) { e.stopPropagation(); fn(b); }; return b; }
function pill(text, colour) { return el("span", text, "pill p-" + colour); }
var toastTimer;
function toast(msg, bad) { var t = $("toast"); t.textContent = msg; t.className = "show" + (bad ? " bad" : ""); clearTimeout(toastTimer); toastTimer = setTimeout(function () { t.className = ""; }, 3200); }
function errText(e) {
  if (e.message === "code_not_found") return "No device with that code yet. Open AxiomTV on it first.";
  if (e.message === "too_many_tries") return "Too many wrong passwords. Try again in 15 minutes.";
  return e.message;
}

function when(t) { return t ? new Date(t).toLocaleString("en-GB", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" }) : "–"; }
function ago(t) {
  if (!t) return "never";
  var s = (Date.now() - t) / 1000;
  if (s < 60) return "just now"; if (s < 3600) return Math.floor(s / 60) + "m ago"; if (s < 86400) return Math.floor(s / 3600) + "h ago";
  if (s < 172800) return "yesterday"; if (s < 604800) return Math.floor(s / 86400) + " days ago";
  return new Date(t).toLocaleDateString("en-GB", { day: "numeric", month: "short" });
}

async function api(path, body) {
  var res = await fetch("/v1/admin/" + path, { method: body ? "POST" : "GET", headers: { "Authorization": "Bearer " + token, "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
  if (res.status === 401) { sessionStorage.removeItem("axiom_admin"); $("login").hidden = false; $("app").hidden = true; throw new Error("Wrong password"); }
  var data = await res.json(); if (!res.ok) throw new Error(data.error || res.status); return data;
}

// Device codes: capitals only, dash after 4 characters
document.addEventListener("input", function (e) {
  if (!e.target.classList || !e.target.classList.contains("codebox")) return;
  var raw = e.target.value.toUpperCase().replace(/[^0-9A-Z]/g, "").slice(0, 8);
  e.target.value = raw.length > 4 ? raw.slice(0, 4) + "-" + raw.slice(4) : raw;
});

// Tabs
document.querySelectorAll(".tab").forEach(function (t) {
  t.onclick = function () {
    document.querySelectorAll(".tab").forEach(function (x) { x.classList.toggle("on", x === t); });
    document.querySelectorAll(".view").forEach(function (v) { v.classList.toggle("on", v.id === "v-" + t.dataset.view); });
    if (t.dataset.view === "activity") loadEvents();
    if (t.dataset.view === "reports") loadCrashes();
  };
});

// ---------- Devices
function deviceState(d) {
  var trialLeft = d.status === "unpaid" && d.trial_ends && d.trial_ends > Date.now() ? Math.ceil((d.trial_ends - Date.now()) / 86400000) : 0;
  if (d.status === "active") return { key: "paid", label: "Unlocked", colour: "green" };
  if (d.status === "revoked") return { key: "locked", label: "Locked", colour: "red" };
  if (trialLeft) return { key: "trial", label: "Trial · " + trialLeft + "d", colour: "blue" };
  return { key: "locked", label: "Trial over", colour: "amber" };
}
function renderStats(c) {
  var items = [["all", "All devices", (c.active || 0) + (c.unpaid || 0) + (c.revoked || 0)], ["paid", "Unlocked", c.active || 0],
    ["trial", "On trial", c.trial || 0], ["locked", "Trial over or locked", (c.trial_over || 0) + (c.revoked || 0)]];
  var box = $("stats"); box.replaceChildren();
  items.forEach(function (it) {
    var b = el("button", null, "stat" + (filter === it[0] ? " on" : ""));
    b.appendChild(el("div", it[1], "l")); b.appendChild(el("div", String(it[2]), "n"));
    b.onclick = function () { filter = filter === it[0] && it[0] !== "all" ? "all" : it[0]; renderStats(c); renderDevices(); };
    box.appendChild(b);
  });
}
var lastCounts = {};
async function loadDevices() {
  try { var data = await api("devices?q=" + encodeURIComponent($("q").value)); allDevices = data.devices; lastCounts = data.counts; renderStats(data.counts); renderDevices(); }
  catch (e) { toast(errText(e), true); }
}
function renderDevices() {
  var list = $("devices"); list.replaceChildren();
  var shown = allDevices.filter(function (d) { return filter === "all" || deviceState(d).key === filter; });
  if (!shown.length) { list.appendChild(el("div", allDevices.length ? "No devices match." : "No devices yet.", "empty")); return; }
  shown.forEach(function (d) {
    var st = deviceState(d);
    var row = el("div", null, "tr click cols-dev");
    var c1 = el("div", null, "c-code"); c1.appendChild(el("div", d.code, "code"));
    var c2 = el("div", null, "c-status"); c2.appendChild(pill(st.label, st.colour));
    if (d.beta) { c2.appendChild(document.createTextNode(" ")); c2.appendChild(pill("Beta", "purple")); }
    var c3 = el("div", null, "c-model ellip"); c3.appendChild(el("div", d.model || "Unknown device", "ellip"));
    if (d.note) c3.appendChild(el("div", d.note, "note-inline ellip"));
    var c4 = el("div", d.app_version ? "v" + d.app_version : "–", "c-ver muted small-text");
    var c5 = el("div", ago(d.last_seen), "c-seen right muted small-text");
    row.append(c1, c2, c3, c4, c5);
    if (d.note) row.appendChild(el("div", d.note, "c-note ellip"));
    row.onclick = function () { openCode = openCode === d.code ? null : d.code; renderDevices(); };
    if (openCode === d.code) row.appendChild(manage(d));
    list.appendChild(row);
  });
}
function fact(box, k, v) { if (!v) return; var f = el("div", null, "fact"); f.appendChild(el("div", k, "k")); f.appendChild(el("div", v, "v")); box.appendChild(f); }
function manage(d) {
  var m = el("div", null, "manage"); m.onclick = function (e) { e.stopPropagation(); };
  var facts = el("div", null, "facts");
  fact(facts, "DEVICE", d.model); fact(facts, "APP VERSION", d.app_version && "v" + d.app_version);
  fact(facts, "EMAIL", d.email); fact(facts, "ORDER", d.order_number || d.order_id);
  fact(facts, "PAID WITH", d.source); fact(facts, "UNLOCKED", d.activated_at && when(d.activated_at));
  fact(facts, "FIRST SEEN", when(d.created_at)); fact(facts, "LAST SEEN", when(d.last_seen));
  fact(facts, "TRIAL ENDS", d.status === "unpaid" && d.trial_ends ? when(d.trial_ends) : null);
  fact(facts, "DISCORD", d.discord_user ? "Linked" : "Not linked"); fact(facts, "MOVED BY BUYER", d.last_self_move && when(d.last_self_move));
  m.appendChild(facts);
  var a = el("div", null, "actions");
  if (d.status === "active") a.appendChild(btn("Lock", "red", function () { run("revoke", { code: d.code }, d.code + " locked"); }));
  else a.appendChild(btn("Unlock", "gold", function () { run("grant", { code: d.code }, d.code + " unlocked"); }));
  a.appendChild(btn(d.beta ? "Remove beta tester" : "Make beta tester", "", function () { run("beta", { code: d.code, beta: !d.beta }, d.beta ? "No longer a beta tester" : "Now a beta tester"); }));
  a.appendChild(btn("Reset device", "ghost", function (b) {
    if (!b.dataset.sure) { b.dataset.sure = "1"; b.textContent = "Tap again: clears email, order and note"; return; }
    run("reset", { code: d.code }, d.code + " reset to a fresh device");
  }));
  m.appendChild(a);
  m.appendChild(el("div", "Note", "sub-h"));
  var nr = el("div", null, "form-row"); var note = el("input"); note.value = d.note || ""; note.placeholder = "Buyer, Discord name";
  nr.append(note, btn("Save note", "", function () { run("note", { code: d.code, note: note.value }, "Note saved"); })); m.appendChild(nr);
  if (d.status === "active") {
    m.appendChild(el("div", "Move this unlock to another device", "sub-h"));
    var mr = el("div", null, "form-row"); var to = el("input", null, "codebox mono"); to.placeholder = "New device's code"; to.maxLength = 9; to.autocomplete = "off";
    mr.append(to, btn("Move", "gold", function () { run("move", { from: d.code, to: to.value }, "Moved to " + to.value); })); m.appendChild(mr);
  }
  return m;
}
async function run(action, body, done) {
  try { await api(action, body); toast(done); openCode = null; loadDevices(); } catch (e) { toast(errText(e), true); }
}
var searchTimer; $("q").oninput = function () { clearTimeout(searchTimer); searchTimer = setTimeout(loadDevices, 250); };
$("unlockToggle").onclick = function () { var c = $("unlockCard"); c.hidden = !c.hidden; if (!c.hidden) $("code").focus(); };
$("grantBtn").onclick = async function () {
  try { var r = await api("grant", { code: $("code").value, note: $("note").value }); toast(r.code + " is now unlocked"); $("code").value = ""; $("note").value = ""; $("unlockCard").hidden = true; loadDevices(); }
  catch (e) { toast(errText(e), true); }
};

// ---------- Activity
var EVENTS = {
  registered: ["New device", "blue"], granted: ["Unlocked", "green"], paid: ["Paid", "green"], paid_test: ["Test payment", "green"],
  refunded: ["Refunded", "red"], revoked: ["Locked", "red"], reset: ["Reset", "amber"], moved: ["Moved", "blue"],
  transfer_code_made: ["Move code made", "blue"], claim_failed: ["Claim failed", "amber"], payment_unmatched: ["Payment not matched", "red"],
  order_not_paid: ["Order not paid", "amber"], discord_linked: ["Discord linked", "purple"], discord_verify_failed: ["Discord link failed", "amber"],
  settings_changed: ["Settings changed", "grey"], diag_link_made: ["Diagnostics link", "grey"], diag_link_off: ["Diagnostics off", "grey"],
  reports_cleared: ["Reports cleared", "grey"], admin_locked_out: ["Admin lock-out", "red"]
};
async function loadEvents() {
  try {
    var data = await api("events"); var body = $("events"); body.replaceChildren();
    if (!data.events.length) { body.appendChild(el("div", "Nothing yet.", "empty")); return; }
    data.events.forEach(function (e) {
      var info = EVENTS[e.type] || [e.type.replace(/_/g, " "), "grey"];
      var row = el("div", null, "tr cols-act");
      var w = el("div", null, "c-when"); w.appendChild(el("div", ago(e.at), "small-text")); w.appendChild(el("div", when(e.at), "cell-sub"));
      var c = el("div", e.code || "", "c-code code");
      var ev = el("div", null, "c-event"); ev.appendChild(pill(info[0], info[1]));
      var dt = el("div", e.detail || "", "c-detail muted small-text ellip"); dt.title = e.detail || "";
      row.append(w, c, ev, dt); body.appendChild(row);
    });
  } catch (err) { toast(errText(err), true); }
}

// ---------- Reports
function reportText(c) { return when(c.at) + " | " + (c.kind || "crash") + " | " + [c.code, c.model, c.app_version && "v" + c.app_version].filter(Boolean).join(" / ") + "\n" + c.report; }
async function loadCrashes() {
  try {
    var data = await api("crashes"); lastReports = data.crashes; $("repCount").textContent = data.crashes.length;
    var box = $("crashes"); box.replaceChildren();
    if (!data.crashes.length) { box.appendChild(el("div", "No reports. All quiet.", "empty")); return; }
    data.crashes.forEach(function (c) {
      var d = el("details", null, "rep"); var s = el("summary");
      s.appendChild(pill(c.kind === "error" ? "Error" : "Crash", c.kind === "error" ? "amber" : "red"));
      var mid = el("div", null, "ellip"); mid.appendChild(el("div", [c.model, c.app_version && "v" + c.app_version].filter(Boolean).join(" · ") || "Unknown device", "ellip"));
      mid.appendChild(el("div", c.code || "", "cell-sub mono")); s.appendChild(mid);
      s.appendChild(el("div", ago(c.at), "muted small-text")); d.appendChild(s);
      var b = el("div", null, "body"); var a = el("div", null, "actions");
      a.append(btn("Copy", "gold", function () { navigator.clipboard.writeText(reportText(c)).then(function () { toast("Report copied"); }); }),
        btn("Delete", "red", function () { clearReports(c.id); }));
      b.appendChild(a); b.appendChild(el("pre", c.report)); d.appendChild(b); box.appendChild(d);
    });
  } catch (e) { toast(errText(e), true); }
}
async function clearReports(id) {
  try { var r = await api("crashes/clear", id === undefined ? {} : { id: id }); toast(r.deleted + " report" + (r.deleted === 1 ? "" : "s") + " cleared"); loadCrashes(); }
  catch (e) { toast(errText(e), true); }
}
$("clearAll").onclick = function () { var b = $("clearAll"); if (!b.dataset.sure) { b.dataset.sure = "1"; b.textContent = "Tap again to clear"; return; } delete b.dataset.sure; b.textContent = "Clear all"; clearReports(); };
$("copyAll").onclick = function () {
  if (!lastReports.length) { toast("No reports to copy"); return; }
  navigator.clipboard.writeText(lastReports.map(reportText).join("\n\n==========\n\n")).then(function () { toast("All reports copied"); });
};
function showDiag(url) {
  var box = $("diagBox"); box.replaceChildren();
  if (url) {
    var i = el("input"); i.value = url; i.readOnly = true; box.appendChild(i);
    box.append(btn("Copy link", "gold", function () { navigator.clipboard.writeText(url).then(function () { toast("Link copied: paste it to Claude"); }); }),
      btn("New link", "", function () { makeDiag(false); }), btn("Turn off", "red", function () { makeDiag(true); }));
  } else box.appendChild(btn("Make a link", "gold", function () { makeDiag(false); }));
}
async function makeDiag(off) { try { var r = await api("diag-link", { off: off }); showDiag(r.url); toast(off ? "Diagnostics link turned off" : "New diagnostics link made"); } catch (e) { toast(errText(e), true); } }

// ---------- Settings and paywall
function showLock() {
  $("lockSwitch").classList.toggle("on", lockOn);
  $("lockDot").style.background = lockOn ? "var(--green)" : "var(--muted)";
  $("lockChipText").textContent = lockOn ? "Paywall on" : "Paywall off";
  $("lockText").textContent = lockOn ? "On: devices that aren't unlocked see the payment screen after their trial." : "Off: everyone can use AxiomTV for free. Devices still check in.";
}
var lockSure = false;
async function toggleLock() {
  if (!lockOn && !lockSure) { lockSure = true; toast("Tap again to turn the paywall on"); setTimeout(function () { lockSure = false; }, 4000); return; }
  lockSure = false;
  try { var s = await api("settings", { paywall_on: !lockOn }); lockOn = s.paywall_on === "true"; showLock(); toast(lockOn ? "Paywall on" : "Paywall off"); } catch (e) { toast(errText(e), true); }
}
$("lockSwitch").onclick = toggleLock;
$("lockChip").onclick = function () { document.querySelector('.tab[data-view="settings"]').click(); };
async function loadSettings() {
  var s = await api("settings");
  $("checkout_url").value = s.checkout_url || ""; $("price_text").value = s.price_text || ""; $("trial_days").value = s.trial_days == null ? "3" : s.trial_days;
  $("early_bird").checked = !s.early_bird_end; lockOn = s.paywall_on === "true"; showLock(); showDiag(s.diag_url);
}
$("saveSettings").onclick = async function () {
  try { await api("settings", { checkout_url: $("checkout_url").value, price_text: $("price_text").value, trial_days: Number($("trial_days").value || 0), early_bird_running: $("early_bird").checked }); toast("Settings saved"); loadDevices(); }
  catch (e) { toast(errText(e), true); }
};
$("signOut").onclick = function () { sessionStorage.removeItem("axiom_admin"); token = ""; $("app").hidden = true; $("login").hidden = false; $("token").value = ""; };

// ---------- Start
async function start() {
  if (!token) return;
  try { await loadSettings(); $("login").hidden = true; $("app").hidden = false; loadDevices(); loadCrashes(); }
  catch (e) { $("loginMsg").textContent = errText(e); }
}
function signIn() { token = $("token").value; sessionStorage.setItem("axiom_admin", token); start(); }
$("signIn").onclick = signIn;
$("token").onkeydown = function (e) { if (e.key === "Enter") signIn(); };
start();
</script></body></html>`;
