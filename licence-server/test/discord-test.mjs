// Tests the Discord bot against a pretend Discord API: node licence-server/test/discord-test.mjs (Node 22+)
import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import crypto from "node:crypto";

const calls = [];
let idSeq = 1000;
const realFetch = globalThis.fetch;
globalThis.fetch = async (url, opts = {}) => {
  const u = String(url);
  if (u.startsWith("https://discord.com/api/v10")) {
    const path = u.slice(27);
    const method = opts.method || "GET";
    calls.push({ method, path, body: opts.body ? JSON.parse(opts.body) : null });
    if (method === "GET" && path.endsWith("/roles")) return Response.json([{ id: "G1", name: "@everyone", permissions: "1071698660929" }]);
    if (method === "GET" && (path.endsWith("/channels") || path.endsWith("/auto-moderation/rules"))) return Response.json([]);
    if (method === "PUT" || method === "DELETE") return new Response(null, { status: 204 });
    const body = opts.body ? JSON.parse(opts.body) : {};
    return Response.json({ id: String(idSeq++), ...body });
  }
  if (u.includes("api.github.com")) return Response.json({ tag_name: "v0.1.90", body: "Fixes\n- faster guide" });
  return realFetch(url, opts);
};

const worker = (await import(new URL("../src/index.js", import.meta.url))).default;
const db = new DatabaseSync(":memory:");
const migrations = new URL("../migrations/", import.meta.url);
for (const f of fs.readdirSync(migrations).filter((f) => f.endsWith(".sql")).sort()) db.exec(fs.readFileSync(new URL(f, migrations), "utf8"));
const D1 = { async batch(list) { return Promise.all(list.map((st) => st.run())); }, prepare(sql) { const st = db.prepare(sql); let args = [];
  const o = { bind(...a) { args = a; return o; }, async first() { return st.get(...args) ?? null; },
    async all() { return { results: st.all(...args) }; }, async run() { const r = st.run(...args); return { meta: { changes: r.changes } }; } }; return o; } };

const sigKeys = crypto.generateKeyPairSync("ed25519");
const rawPub = sigKeys.publicKey.export({ format: "jwk" }).x;
const pubHex = Buffer.from(rawPub, "base64url").toString("hex");
const env = { DB: D1, LICENCE_SIGNING_KEY: JSON.stringify(crypto.generateKeyPairSync("ed25519").privateKey.export({ format: "jwk" })),
  ADMIN_TOKEN: "pw", DISCORD_PUBLIC_KEY: pubHex, DISCORD_BOT_TOKEN: "bot", DISCORD_APP_ID: "APP" };
const waits = [];
const ctx = { waitUntil: (p) => waits.push(p) };
const B = "https://lic.example";
const call = async (path, opts = {}) => { const r = await worker.fetch(new Request(B + path, opts), env, ctx); const t = await r.text(); let j; try { j = JSON.parse(t); } catch { j = t; } return [r.status, j]; };
const post = (p, body, h = {}) => call(p, { method: "POST", body: JSON.stringify(body), headers: { "content-type": "application/json", ...h } });
const A = { Authorization: "Bearer pw" };
const interact = (body, badSig = false) => {
  const raw = JSON.stringify(body); const ts = String(Math.floor(Date.now() / 1000));
  const sig = crypto.sign(null, Buffer.from(ts + raw), sigKeys.privateKey).toString("hex");
  return call("/discord/interactions", { method: "POST", body: raw, headers: { "x-signature-ed25519": badSig ? "00".repeat(64) : sig, "x-signature-timestamp": ts } });
};
const member = (id, admin = false) => ({ user: { id, username: "user" + id }, permissions: admin ? "8" : "0" });
const cmd = (name, options = [], id = "U1", admin = false) => interact({ type: 2, application_id: "APP", token: "tok", guild_id: "G1", member: member(id, admin), data: { name, options } });
let failed = 0; const assert = (c, m) => { if (!c) { console.error("FAIL", m); failed++; } else console.log("ok", m); };

let [s, j] = await interact({ type: 1 }); assert(j.type === 1, "PING answered");
[s] = await interact({ type: 1 }, true); assert(s === 401, "bad signature rejected");
[s, j] = await cmd("stats"); assert(j.data.content.includes("owner"), "admin command refused for members");

// set-up builds roles and channels
[s, j] = await cmd("setup", [], "OWNER", true); assert(j.type === 5, "setup deferred");
await Promise.all(waits.splice(0));
const settings = Object.fromEntries(db.prepare("SELECT key, value FROM settings").all().map((r) => [r.key, r.value]));
assert(settings.discord_role_lifetime && settings.discord_role_trial && settings.discord_ch_help && settings.discord_ch_buy && settings.discord_ch_alerts, "setup saved roles and channels");
assert(calls.some((c) => c.method === "POST" && c.path.endsWith("/auto-moderation/rules")), "AutoMod rule created");
const followUp = calls.find((c) => c.path.includes("/webhooks/APP/tok"));
assert(followUp && followUp.body.content.startsWith("Done"), "setup reported done: " + followUp?.body.content.slice(0, 60));

// a box checks in, gets a link code, verifies
await post("/v1/admin/settings", { paywall_on: true }, A);
[s, j] = await post("/v1/check", { device_id: "box-one-123" }); const boxCode = j.code;
[s, j] = await post("/v1/discord/link", { device_id: "box-one-123" }); assert(/^\d{6}$/.test(j.link_code), "link code made");
calls.length = 0;
[s, j] = await cmd("verify", [{ name: "code", value: "000000" }]); assert(j.data.content.includes("isn't right"), "wrong link code refused");
[s, j] = await cmd("verify", [{ name: "code", value: (await post("/v1/discord/link", { device_id: "box-one-123" }))[1].link_code }]);
assert(j.data.content.includes(boxCode) && j.data.content.includes("Trial"), "verify links box with Trial role: " + j.data.content);
assert(calls.some((c) => c.method === "PUT" && c.path.endsWith(settings.discord_role_trial)), "Trial role given");

// unlock -> Lifetime + Early Bird, Trial removed
calls.length = 0;
[s, j] = await cmd("unlock", [{ name: "code", value: boxCode }], "OWNER", true); assert(j.data.content.includes("unlocked"), "/unlock works");
assert(calls.some((c) => c.method === "PUT" && c.path.endsWith(settings.discord_role_lifetime)), "Lifetime role given");
assert(calls.some((c) => c.method === "PUT" && c.path.endsWith(settings.discord_role_early)), "Early Bird role given");
assert(calls.some((c) => c.method === "DELETE" && c.path.endsWith(settings.discord_role_trial)), "Trial role removed");
assert(calls.some((c) => c.method === "POST" && c.path === `/channels/${settings.discord_ch_alerts}/messages`), "alert posted");

// early bird ended: a newly unlocked box gets Lifetime but not Early Bird
await post("/v1/admin/settings", { early_bird_running: false }, A);
await post("/v1/check", { device_id: "box-two-456" }); const box2 = (await post("/v1/check", { device_id: "box-two-456" }))[1].code;
await cmd("verify", [{ name: "code", value: (await post("/v1/discord/link", { device_id: "box-two-456" }))[1].link_code }], "U2");
calls.length = 0;
await post("/v1/admin/grant", { code: box2 }, A);
assert(calls.some((c) => c.method === "PUT" && c.path.endsWith(settings.discord_role_lifetime)) && calls.some((c) => c.method === "DELETE" && c.path.includes("/members/U2/roles/" + settings.discord_role_early)), "late buyer: Lifetime, no Early Bird");

// tester toggle, mybox, revoke
calls.length = 0;
[s, j] = await cmd("tester", [{ name: "member", value: "U1" }], "OWNER", true); assert(j.data.content.includes("now a beta tester") && calls.some((c) => c.method === "PUT" && c.path.endsWith(settings.discord_role_beta)), "/tester gives Beta Tester");
[s, j] = await cmd("mydevice"); assert(j.data.content.includes("unlocked") && j.data.content.includes("beta tester"), "/mydevice shows box");
[s, j] = await cmd("error", [{ name: "code", value: 1001 }]); assert(j.data.content.includes("Channel offline"), "/error explains code");
calls.length = 0;
await post("/v1/admin/revoke", { code: boxCode }, A);
assert(calls.some((c) => c.method === "DELETE" && c.path.endsWith(settings.discord_role_lifetime)), "revoke removes Lifetime");

// tickets
calls.length = 0;
[s, j] = await interact({ type: 3, application_id: "APP", token: "t", guild_id: "G1", member: member("U3"), data: { custom_id: "ticket_open" } });
assert(j.data.content.includes("ticket is open") && calls.some((c) => c.method === "POST" && c.path === "/guilds/G1/channels" && c.body.name.startsWith("ticket-")), "ticket channel created");
[s, j] = await interact({ type: 3, application_id: "APP", token: "t", guild_id: "G1", channel_id: "C9", channel: { topic: "Ticket for <@U3>" }, member: member("U4"), data: { custom_id: "ticket_close" } });
assert(j.data.content.includes("Only the person"), "someone else can't close a ticket");

// hourly job announces a new release (second run onwards)
await worker.scheduled({}, env, ctx); await Promise.all(waits.splice(0));
db.prepare("UPDATE settings SET value = 'v0.1.89' WHERE key = 'discord_last_release'").run();
calls.length = 0;
await worker.scheduled({}, env, ctx); await Promise.all(waits.splice(0));
assert(calls.some((c) => c.method === "POST" && c.path === `/channels/${settings.discord_ch_announcements}/messages` && c.body.content.includes("0.1.90")), "new version announced");

// crash report goes to #reports
calls.length = 0;
await post("/v1/crash", { code: boxCode, model: "Box", app_version: "0.1.90", report: "java.lang.IllegalStateException: x" });
assert(calls.some((c) => c.path === `/channels/${settings.discord_ch_reports}/messages`), "crash report posted to #reports");

if (failed) process.exit(1);
