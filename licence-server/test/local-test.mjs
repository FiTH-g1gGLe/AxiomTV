// Runs the worker against an in-memory SQLite stand-in for D1: node licence-server/test/local-test.mjs (Node 22+)
import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import crypto from "node:crypto";
const worker = (await import(new URL("../src/index.js", import.meta.url))).default;
const db = new DatabaseSync(":memory:");
const migrations = new URL("../migrations/", import.meta.url);
for (const f of fs.readdirSync(migrations).filter((f) => f.endsWith(".sql")).sort()) db.exec(fs.readFileSync(new URL(f, migrations), "utf8"));
const D1 = { async batch(list) { return Promise.all(list.map((st) => st.run())); }, prepare(sql) { const st = db.prepare(sql); let args = [];
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
await post("/v1/check", { device_id: "secondbox1" });
const code2 = (await post("/v1/check", { device_id: "secondbox1" }))[1].code;
[s, j] = await post("/v1/admin/move", { from: code, to: code2.replace("-", "").toLowerCase() }, A); assert(j.to === code2, "move to another box");
[s, j] = await post("/v1/check", { device_id: "secondbox1" }); assert(j.status === "active" && j.licence, "new box unlocked");
[s, j] = await post("/v1/check", { device_id: "abc123def" }); assert(j.status === "revoked", "old box revoked after move");
[s, j] = await post("/v1/admin/move", { from: code, to: code2 }, A); assert(s === 400, "can't move a revoked box");
[s, j] = await post("/v1/admin/note", { code: code2, note: "Lee new box" }, A); assert(j.ok, "note saved");
[s, j] = await post("/v1/admin/move", { from: code2, to: code }, A); assert(j.ok, "move back");
[s, j] = await call("/pay/%E0%A4%A"); assert(s === 404, "malformed code safe");
[s, j] = await post("/v1/check", { device_id: "abc123def" }); assert(j.enforce === false, "paywall off by default");
[s, j] = await post("/v1/admin/settings", { paywall_on: true }, A); assert(j.paywall_on === "true", "paywall switched on");
[s, j] = await post("/v1/check", { device_id: "newbox999" }); assert(j.enforce === true && j.status === "unpaid", "enforce reported");
const left = (j.trial_ends - Date.now()) / 86400000; assert(left > 2.99 && left <= 3, "3-day trial by default: " + left.toFixed(3));
[s, j] = await post("/v1/check", { device_id: "abc123def" }); assert(j.status === "active" && j.trial_ends === null, "no trial for unlocked box");
[s, j] = await post("/v1/admin/settings", { trial_days: 0 }, A); [s, j] = await post("/v1/check", { device_id: "newbox999" }); assert(j.trial_ends === null, "trial can be turned off");
[s, j] = await post("/v1/admin/settings", { trial_days: 2.5 }, A); assert(s === 400, "bad trial days rejected");
[s, j] = await post("/v1/admin/settings", { trial_days: 3 }, A); [s, j] = await call("/v1/admin/devices", { headers: A }); assert(j.devices.some(d => d.trial_ends), "trial shown in admin list");

// ---- buyer moves to another box
await post("/v1/admin/settings", { paywall_on: true }, A);
const oldId = "movebox-old", newId = "movebox-new";
const oldCode = (await post("/v1/check", { device_id: oldId }))[1].code;
const newCode = (await post("/v1/check", { device_id: newId }))[1].code;
[s, j] = await post("/v1/transfer/start", { device_id: oldId }); assert(s === 400, "locked box can't make a transfer code");
await post("/v1/admin/grant", { code: oldCode }, A);
[s, j] = await post("/v1/transfer/start", { device_id: oldId }); assert(/^\d{6}$/.test(j.transfer_code), "transfer code made " + j.transfer_code);
const tc = j.transfer_code;
[s, j] = await post("/v1/transfer/claim", { device_id: newId, transfer_code: "000000" === tc ? "111111" : "000000" }); assert(s === 400 && j.error === "invalid_code", "wrong transfer code rejected");
[s, j] = await post("/v1/transfer/claim", { device_id: oldId, transfer_code: tc }); assert(j.error === "same_box", "same box rejected");
[s, j] = await post("/v1/transfer/claim", { device_id: newId, transfer_code: tc.slice(0,3) + " " + tc.slice(3) }); assert(j.ok && j.to === newCode, "transfer claimed");
[s, j] = await post("/v1/check", { device_id: newId }); assert(j.status === "active" && j.licence, "new box unlocked by transfer");
[s, j] = await post("/v1/check", { device_id: oldId }); assert(j.status === "revoked", "old box locked after transfer");
[s, j] = await post("/v1/transfer/start", { device_id: newId }); assert(s === 429 && j.next_allowed > Date.now() + 29 * 86400000, "30-day limit follows the licence");
[s, j] = await post("/v1/admin/move", { from: newCode, to: oldCode }, A); assert(j.ok, "admin move ignores the limit");
// receipt move
const rId = "receipt-old", r2Id = "receipt-new";
const rCode = (await post("/v1/check", { device_id: rId }))[1].code;
await post("/v1/check", { device_id: r2Id });
const ev2 = JSON.stringify({ meta: { event_name: "order_created", custom_data: { device_code: rCode } }, data: { id: "9001", attributes: { status: "paid", order_number: 4242, user_email: "Buyer@Example.com", test_mode: false } } });
await post("/v1/webhooks/lemonsqueezy", ev2, { "X-Signature": crypto.createHmac("sha256", "whsec").update(ev2).digest("hex") });
[s, j] = await post("/v1/transfer/receipt", { device_id: r2Id, email: "buyer@example.com", order: "9999" }); assert(s === 404, "wrong order rejected");
[s, j] = await post("/v1/transfer/receipt", { device_id: r2Id, email: " buyer@example.com ", order: "#4242" }); assert(j.ok, "receipt move works");
[s, j] = await post("/v1/check", { device_id: r2Id }); assert(j.status === "active", "receipt box unlocked");
const ref2 = JSON.stringify({ meta: { event_name: "order_refunded" }, data: { id: "9001", attributes: { status: "refunded" } } });
await post("/v1/webhooks/lemonsqueezy", ref2, { "X-Signature": crypto.createHmac("sha256", "whsec").update(ref2).digest("hex") });
[s, j] = await post("/v1/check", { device_id: r2Id }); assert(j.status === "revoked", "refund still revokes after a move");
for (let i = 0; i < 30; i++) await post("/v1/transfer/claim", { device_id: r2Id, transfer_code: "123456" });
[s, j] = await post("/v1/transfer/claim", { device_id: r2Id, transfer_code: "123456" }); assert(j.error === "too_many_attempts", "guessing is slowed down");

[s, j] = await post("/v1/crash", { code: "abcd-efgh", model: "Box", app_version: "0.1.85", report: "java.lang.RuntimeException: boom" }); assert(j.ok, "crash stored");
[s, j] = await call("/v1/admin/crashes", { headers: A }); assert(j.crashes.length === 1 && j.crashes[0].report.includes("boom"), "crash listed");

[s, j] = await post("/v1/crash", { kind: "error", code: "abcd-efgh", model: "Box", app_version: "0.1.89", report: "Error 1005 format" }); assert(j.ok, "error report stored");
[s, j] = await call("/v1/diag?key=nope"); assert(s === 403, "diag locked without link");
[s, j] = await post("/v1/admin/diag-link", {}, A); const diagUrl = j.url; assert(/\/v1\/diag\?key=[0-9a-f]{32}$/.test(diagUrl), "diag link made");
[s, j] = await call(diagUrl.replace("https://lic.example", "")); assert(s === 200 && j.includes("Error 1005") && j.includes("boom") && !j.includes("a@b.c"), "diag shows reports, no emails");
[s, j] = await call("/v1/admin/settings", { headers: A }); assert(j.diag_url === diagUrl && !j.diag_key, "settings show link not raw key");
[s, j] = await post("/v1/admin/diag-link", { off: true }, A); [s, j] = await call(diagUrl.replace("https://lic.example", "")); assert(s === 403, "diag link turned off");

{ const html = (await call("/admin"))[1]; const js = html.match(/<script>([\s\S]*?)<\/script>/)[1]; let okJs = true; try { new Function(js); } catch { okJs = false; } assert(okJs, "admin page script is valid"); }
