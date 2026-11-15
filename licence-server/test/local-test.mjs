// Runs the worker against an in-memory SQLite stand-in for D1: node licence-server/test/local-test.mjs (Node 22+)
import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import crypto from "node:crypto";
const worker = (await import(new URL("../src/index.js", import.meta.url))).default;
const db = new DatabaseSync(":memory:");
db.exec(fs.readFileSync(new URL("../migrations/0001_init.sql", import.meta.url), "utf8"));
const D1 = { prepare(sql) { const st = db.prepare(sql); let args = [];
  const o = { bind(...a) { args = a; return o; }, async first() { return st.get(...args) ?? null; },
    async all() { return { results: st.all(...args) }; }, async run() { const r = st.run(...args); return { meta: { changes: r.changes } }; } }; return o; } };
const jwk = crypto.generateKeyPairSync("ed25519").privateKey.export({ format: "jwk" });
const env = { DB: D1, LICENCE_SIGNING_KEY: JSON.stringify(jwk), ADMIN_TOKEN: "pw", LEMONSQUEEZY_WEBHOOK_SECRET: "whsec" };
const B = "https://lic.example";
const call = async (path, opts = {}) => { const r = await worker.fetch(new Request(B + path, opts), env); const t = await r.text(); let j; try { j = JSON.parse(t) } catch { j = t } return [r.status, j, r.headers.get("location")]; };
const post = (p, body, h = {}) => call(p, { method: "POST", body: typeof body === "string" ? body : JSON.stringify(body), headers: { "content-type": "application/json", ...h } });
const A = { Authorization: "Bearer pw" };
let assert = (c, m) => { if (!c) { console.error("FAIL", m); process.exitCode = 1 } else console.log("ok", m) };

let [s, j] = await post("/v1/check", { device_id: "abc123def", model: "X96", app_version: "0.1.80" });
assert(s === 200 && j.status === "unpaid" && /^[2-9A-Z]{4}-[2-9A-Z]{4}$/.test(j.code), "register " + JSON.stringify(j));
const code = j.code;
[s, j] = await post("/v1/check", { device_id: "abc123def" }); assert(j.code === code && !j.licence, "same code on recheck");
[s, j] = await post("/v1/check", { device_id: "x" }); assert(s === 400, "bad id rejected");
[s, j] = await call("/pay/" + code); assert(s === 200 && j.includes("Payments open soon"), "holding page");
[s, j] = await call("/pay/ZZZZ-ZZZ0"); assert(s === 404, "unknown code");
[s, j] = await call("/v1/admin/devices"); assert(s === 401, "admin needs auth");
[s, j] = await post("/v1/admin/settings", { checkout_url: "https://store.lemonsqueezy.com/buy/abc", price_text: "£10" }, A); assert(j.checkout_url, "settings saved");
let loc; [s, j, loc] = await call("/pay/" + code.toLowerCase()); assert(s === 302 && loc.includes("device_code%5D=" + code), "redirect " + loc);
// webhook bad sig
const ev = JSON.stringify({ meta: { event_name: "order_created", custom_data: { device_code: code } }, data: { id: "555", attributes: { status: "paid", user_email: "a@b.c", test_mode: false } } });
[s] = await post("/v1/webhooks/lemonsqueezy", ev, { "X-Signature": "00" }); assert(s === 401, "bad signature rejected");
const sig = crypto.createHmac("sha256", "whsec").update(ev).digest("hex");
[s, j] = await post("/v1/webhooks/lemonsqueezy", ev, { "X-Signature": sig }); assert(j.activated === code, "paid webhook activates");
[s, j] = await post("/v1/check", { device_id: "abc123def" }); assert(j.status === "active" && j.licence, "licence issued");
// verify licence like the app would
const [p64, s64] = j.licence.split(".");
const pub = (await call("/v1/pubkey"))[1].key;
const pk = crypto.createPublicKey({ key: { kty: "OKP", crv: "Ed25519", x: pub }, format: "jwk" });
assert(crypto.verify(null, Buffer.from(p64), pk, Buffer.from(s64, "base64url")), "signature verifies with /v1/pubkey");
const payload = JSON.parse(Buffer.from(p64, "base64url"));
assert(payload.dh === crypto.createHash("sha256").update("axiomtv:abc123def").digest("hex"), "device hash matches");
const ref = JSON.stringify({ meta: { event_name: "order_refunded" }, data: { id: "555", attributes: { status: "refunded" } } });
await post("/v1/webhooks/lemonsqueezy", ref, { "X-Signature": crypto.createHmac("sha256", "whsec").update(ref).digest("hex") });
[s, j] = await post("/v1/check", { device_id: "abc123def" }); assert(j.status === "revoked" && !j.licence, "refund revokes");
[s, j] = await post("/v1/admin/grant", { code, note: "Discord: tester" }, A); assert(j.status === "active", "manual grant");
[s, j] = await post("/v1/admin/grant", { code: "2222-2222" }, A); assert(s === 404, "grant unknown code 404");
[s, j] = await call("/v1/admin/devices?q=tester", { headers: A }); assert(j.devices.length === 1 && j.counts.active === 1, "search devices");
[s, j] = await call("/v1/admin/events", { headers: A }); assert(j.events.length >= 5, "events logged: " + j.events.map(e => e.type).join(","));
[s, j] = await call("/admin"); assert(s === 200, "admin page served");
[s, j] = await call("/pay/%E0%A4%A"); assert(s === 404, "malformed code safe");
[s, j] = await post("/v1/check", { device_id: "abc123def" }); assert(j.enforce === false, "paywall off by default");
[s, j] = await post("/v1/admin/settings", { paywall_on: true }, A); assert(j.paywall_on === "true", "paywall switched on");
[s, j] = await post("/v1/check", { device_id: "newbox999" }); assert(j.enforce === true && j.status === "unpaid", "enforce reported");
