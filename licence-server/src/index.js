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
 *   STRIPE_WEBHOOK_SECRET       signing secret (whsec_...) of the Stripe webhook
 */

const CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // no 0/O, 1/I
const LICENCE_DAYS = 30;
const DEFAULT_TRIAL_DAYS = 3;
const SELF_MOVE_EVERY_MS = 30 * 86400000; // buyers can move their unlock themselves once per 30 days
const TRANSFER_CODE_MS = 30 * 60000; // a transfer code lasts 30 minutes
const MAX_FAILED_CLAIMS = 30; // wrong codes/receipts allowed across everyone per 10 minutes // the app refreshes on every launch; a revoke takes effect within this window offline
const DEVICE_SALT = "axiomtv:";

import { ADMIN_PAGE } from "./admin-page.js";
import { handleInteraction, makeLinkCode, syncDevice, notify, hourly } from "./discord.js";

/** What the Discord bot borrows from here. */
const LIB = {
  normaliseCode: (c) => normaliseCode(c),
  getSettings: (env) => getSettings(env),
  setSetting: (env, k, v) => setSetting(env, k, v),
  logEvent: (env, c, t, d) => logEvent(env, c, t, d),
  grant: (env, c, n) => grantCode(env, c, n),
  revoke: (env, c, n) => revokeCode(env, c, n),
  move: (env, f, t) => moveCodes(env, f, t),
  trialEnds: (d, s) => trialEnds(d, s),
};

export default {
  async scheduled(event, env, ctx) {
    ctx.waitUntil(hourly(env, LIB));
  },
  async fetch(request, env, ctx) {
    try {
      return await route(request, env, ctx);
    } catch (err) {
      console.error(err && err.stack ? err.stack : err);
      return json({ error: "server_error" }, 500);
    }
  },
};

async function route(request, env, ctx) {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";
  const method = request.method;

  if (path === "/" && method === "GET") return json({ service: "axiomtv-licence", ok: true });
  if (path === "/v1/pubkey" && method === "GET") return json({ alg: "Ed25519", key: (await signingKey(env)).publicKey });
  if (path === "/v1/check" && method === "POST") return check(request, env, url);
  if (path === "/v1/crash" && method === "POST") return crashReport(request, env);
  if (path === "/discord/interactions" && method === "POST") return handleInteraction(request, env, ctx, LIB);
  if (path === "/v1/discord/link" && method === "POST") return discordLink(request, env);
  if (path === "/v1/diag" && method === "GET") return diagnostics(env, url);
  if (path === "/v1/transfer/start" && method === "POST") return transferStart(request, env);
  if (path === "/v1/transfer/claim" && method === "POST") return transferClaim(request, env);
  if (path === "/v1/transfer/receipt" && method === "POST") return transferReceipt(request, env);
  if (path.startsWith("/pay/") && method === "GET") return payPage(env, url, path.slice(5));
  if (path === "/v1/webhooks/lemonsqueezy" && method === "POST") return lemonSqueezyWebhook(request, env);
  if (path === "/v1/webhooks/stripe" && method === "POST") return stripeWebhook(request, env);
  if (path === "/admin" && method === "GET") return html(ADMIN_PAGE);
  if (path.startsWith("/v1/admin/")) {
    const gate = await adminGate(request, env);
    if (gate) return gate;
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
    // The QR on the payment screen: axiomtv.co.uk/link/<code> (the website passes it through to /pay here)
    pay_url: `${env.PUBLIC_PAY_BASE || `${url.origin}/pay`}/${device.code}`,
    price: settings.price_text || null,
    payments_open: Boolean(settings.checkout_url),
    enforce: settings.paywall_on === "true", // the app only locks when you turn this on in /admin
    trial_ends: trialEnds(device, settings), // epoch ms; the app stays open until then
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

/**
 * Free trial: starts when the device first checked in, or when the lock was first switched on if that was
 * later (so boxes already using the app get the full trial). Only while the lock is on and the box isn't unlocked.
 */
function trialEnds(device, settings) {
  if (settings.paywall_on !== "true" || device.status !== "unpaid") return null;
  const days = trialDays(settings);
  if (!days) return null;
  const start = Math.max(device.created_at, Number(settings.paywall_on_at) || 0);
  return start + days * 86400000;
}

function trialDays(settings) {
  const days = settings.trial_days === undefined ? DEFAULT_TRIAL_DAYS : Number(settings.trial_days);
  return Number.isFinite(days) && days > 0 ? Math.min(days, 60) : 0;
}

// ---------------------------------------------------------------- crash reports

async function crashReport(request, env) {
  const text = (await request.text()).slice(0, 40000);
  let body = {};
  try {
    body = JSON.parse(text);
  } catch {
    return json({ error: "bad_json" }, 400);
  }
  const report = typeof body.report === "string" ? body.report.slice(0, 30000) : "";
  if (!report) return json({ error: "empty" }, 400);
  const recent = await env.DB.prepare("SELECT COUNT(*) AS n FROM crashes WHERE at > ?").bind(Date.now() - 3600000).first();
  if ((recent?.n ?? 0) > 200) return json({ ok: true, dropped: true });
  const kind = body.kind === "error" ? "error" : "crash";
  await env.DB.prepare("INSERT INTO crashes (at, code, model, app_version, report, kind) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(Date.now(), normaliseCode(body.code), clip(body.model, 64), clip(body.app_version, 32), report, kind).run();
  await env.DB.prepare("DELETE FROM crashes WHERE id NOT IN (SELECT id FROM crashes ORDER BY id DESC LIMIT 300)").run();
  await notify(env, LIB, "reports", `🐞 ${kind === "error" ? "Error" : "Crash"} report from ${normaliseCode(body.code) || "unknown device"} (${clip(body.model, 64) || "?"}, v${clip(body.app_version, 32) || "?"}):\n${report.split("\n").slice(0, 3).join("\n").slice(0, 300)}\nFull report on the admin page.`);
  return json({ ok: true });
}

/**
 * Read-only diagnostics for whoever has the link (made on the admin page for Claude): crash and error reports,
 * app versions in use. No emails or order numbers. The link stops working when it's turned off or remade.
 */
async function diagnostics(env, url) {
  const settings = await getSettings(env);
  const key = url.searchParams.get("key") || "";
  if (!settings.diag_key || !safeEqual(await sha256Hex(key), await sha256Hex(settings.diag_key))) {
    return new Response("This diagnostics link is off or out of date. Make a new one on the admin page.", { status: 403 });
  }
  const reports = await env.DB.prepare("SELECT at, kind, code, model, app_version, report FROM crashes ORDER BY id DESC LIMIT 25").all();
  const versions = await env.DB.prepare(
    "SELECT app_version, COUNT(*) AS n FROM devices WHERE last_seen > ? GROUP BY app_version ORDER BY n DESC",
  ).bind(Date.now() - 7 * 86400000).all();
  const counts = await env.DB.prepare("SELECT status, COUNT(*) AS n FROM devices GROUP BY status").all();
  const lines = [
    `AxiomTV diagnostics, ${new Date().toISOString()}`,
    `Paywall: ${settings.paywall_on === "true" ? "on" : "off"} | trial days: ${settings.trial_days ?? 3} | payments open: ${settings.checkout_url ? "yes" : "no"}`,
    `Devices: ${counts.results.map((r) => `${r.n} ${r.status}`).join(", ") || "none"}`,
    `App versions seen this week: ${versions.results.map((r) => `${r.app_version || "?"} x${r.n}`).join(", ") || "none"}`,
    "",
    `Last ${reports.results.length} crash/error reports (newest first):`,
  ];
  for (const r of reports.results) {
    lines.push("", "=".repeat(70), `${new Date(r.at).toISOString()} | ${r.kind} | ${r.code || "-"} | ${r.model || "-"} | v${r.app_version || "?"}`, "-".repeat(70), r.report);
  }
  return new Response(lines.join("\n"), { headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store", "x-robots-tag": "noindex" } });
}

// ---------------------------------------------------------------- moving to another box (by the buyer)

/** Old box: makes a 6-digit code to type on the new box. */
async function transferStart(request, env) {
  const device = await deviceFromBody(env, await readJson(request));
  if (!device) return json({ error: "unknown_device" }, 404);
  if (device.status !== "active") return json({ error: "not_unlocked" }, 400);
  const wait = moveWait(device);
  if (wait) return json({ error: "move_limit", next_allowed: wait }, 429);

  const now = Date.now();
  for (let attempt = 0; attempt < 8; attempt++) {
    const n = crypto.getRandomValues(new Uint32Array(1))[0] % 1000000;
    const transferCode = String(n).padStart(6, "0");
    const clash = await env.DB.prepare("SELECT code FROM devices WHERE transfer_code = ? AND transfer_expires > ?").bind(transferCode, now).first();
    if (clash) continue;
    const expires = now + TRANSFER_CODE_MS;
    await env.DB.prepare("UPDATE devices SET transfer_code = ?, transfer_expires = ? WHERE code = ?").bind(transferCode, expires, device.code).run();
    await logEvent(env, device.code, "transfer_code_made", null);
    return json({ transfer_code: transferCode, expires_at: expires });
  }
  return json({ error: "server_busy" }, 503);
}

/** New box: types the 6-digit code from the old box. */
async function transferClaim(request, env) {
  const body = await readJson(request);
  if (await tooManyFailures(env)) return json({ error: "too_many_attempts" }, 429);
  const target = await deviceFromBody(env, body);
  if (!target) return json({ error: "unknown_device" }, 404);
  const transferCode = String(body.transfer_code || "").replace(/\D/g, "");
  const old = transferCode.length === 6
    && (await env.DB.prepare("SELECT * FROM devices WHERE transfer_code = ? AND transfer_expires > ? AND status = 'active'").bind(transferCode, Date.now()).first());
  if (!old) {
    await logEvent(env, target.code, "claim_failed", "transfer code");
    return json({ error: "invalid_code" }, 400);
  }
  return selfMove(env, old, target, "self_moved");
}

/** New box: the email and order number from the Lemon Squeezy receipt (old box lost or broken). */
async function transferReceipt(request, env) {
  const body = await readJson(request);
  if (await tooManyFailures(env)) return json({ error: "too_many_attempts" }, 429);
  const target = await deviceFromBody(env, body);
  if (!target) return json({ error: "unknown_device" }, 404);
  const email = String(body.email || "").trim().toLowerCase();
  const order = String(body.order || "").trim().replace(/^#/, "");
  const old = email && order
    && (await env.DB.prepare("SELECT * FROM devices WHERE status = 'active' AND lower(email) = ? AND (order_number = ? OR order_id = ?)").bind(email, order, order).first());
  if (!old) {
    await logEvent(env, target.code, "claim_failed", "receipt");
    return json({ error: "order_not_found" }, 404);
  }
  return selfMove(env, old, target, "self_moved_receipt");
}

async function selfMove(env, old, target, eventType) {
  if (old.code === target.code) return json({ error: "same_box" }, 400);
  const wait = moveWait(old);
  if (wait) return json({ error: "move_limit", next_allowed: wait }, 429);
  const now = Date.now();
  await env.DB.batch([
    ...moveStatements(env, old, target.code, now),
    env.DB.prepare("UPDATE devices SET note = ? WHERE code = ?").bind(clip(`${old.note ? old.note + " · " : ""}moved to ${target.code}`, 200), old.code),
  ]);
  await logEvent(env, target.code, eventType, `from ${old.code}`);
  await syncDevice(env, LIB, target.code);
  await syncDevice(env, LIB, old.code);
  await notify(env, LIB, "alerts", `📦 Moved by the buyer: ${old.code} → ${target.code}`);
  return json({ ok: true, from: old.code, to: target.code });
}

/** Gives `to` the licence of `old` and locks `old`. */
function moveStatements(env, old, to, lastSelfMove) {
  return [
    env.DB.prepare(
      "UPDATE devices SET status = 'active', source = ?, order_id = ?, order_number = ?, email = ?, note = ?, activated_at = ?, last_self_move = ?, discord_user = COALESCE(discord_user, ?), beta = MAX(beta, ?) WHERE code = ?",
    ).bind(old.source, old.order_id, old.order_number, old.email, old.note, old.activated_at ?? Date.now(), lastSelfMove ?? null, old.discord_user ?? null, old.beta ?? 0, to),
    env.DB.prepare(
      "UPDATE devices SET status = 'revoked', order_id = NULL, order_number = NULL, transfer_code = NULL, transfer_expires = NULL WHERE code = ?",
    ).bind(old.code),
  ];
}

function moveWait(device) {
  if (!device.last_self_move) return null;
  const next = device.last_self_move + SELF_MOVE_EVERY_MS;
  return next > Date.now() ? next : null;
}

async function deviceFromBody(env, body) {
  const deviceId = typeof body.device_id === "string" ? body.device_id.trim() : "";
  if (deviceId.length < 4 || deviceId.length > 128) return null;
  return env.DB.prepare("SELECT * FROM devices WHERE device_hash = ?").bind(await sha256Hex(DEVICE_SALT + deviceId)).first();
}

async function tooManyFailures(env) {
  const row = await env.DB.prepare("SELECT COUNT(*) AS n FROM events WHERE type = 'claim_failed' AND at > ?").bind(Date.now() - 600000).first();
  return (row?.n ?? 0) >= MAX_FAILED_CLAIMS;
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
  // The checkout link in the admin page decides who takes the payment: a Stripe payment link or a Lemon Squeezy one
  const checkout = new URL(settings.checkout_url);
  if (isStripeLink(checkout)) checkout.searchParams.set("client_reference_id", device.code);
  else checkout.searchParams.set("checkout[custom][device_code]", device.code);
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
      "UPDATE devices SET status = 'active', source = ?, order_id = ?, order_number = ?, email = ?, activated_at = ? WHERE code = ?",
    ).bind(
      testMode ? "lemonsqueezy-test" : "lemonsqueezy",
      orderId,
      attrs.order_number != null ? String(attrs.order_number) : null,
      clip(attrs.user_email, 200),
      Date.now(),
      code,
    ).run();
    await logEvent(env, code, testMode ? "paid_test" : "paid", `order ${orderId}`);
    await syncDevice(env, LIB, code);
    await notify(env, LIB, "alerts", `💳 Paid${testMode ? " (test)" : ""}: ${code} unlocked, order ${attrs.order_number ?? orderId}`);
    return json({ ok: true, activated: code });
  }

  if (name === "order_refunded") {
    const res = await env.DB.prepare("UPDATE devices SET status = 'revoked' WHERE order_id = ?").bind(orderId).run();
    await logEvent(env, code, "refunded", `order ${orderId} (${res.meta?.changes ?? 0} device)`);
    await notify(env, LIB, "alerts", `↩️ Refund: order ${attrs.order_number ?? orderId}, device locked`);
    for (const r of (await env.DB.prepare("SELECT code FROM devices WHERE order_id = ?").bind(orderId).all()).results) await syncDevice(env, LIB, r.code);
    return json({ ok: true });
  }

  return json({ ok: true, ignored: name || "unknown_event" });
}

// ---------------------------------------------------------------- Stripe (Managed Payments payment link)

function isStripeLink(url) {
  return /(^|\.)stripe\.com$/.test(url.hostname);
}

// Stripe-Signature: t=<time>,v1=<hmac of "time.body">. Reject anything older than 5 minutes.
async function stripeSignatureOk(secret, header, raw) {
  const parts = Object.fromEntries((header || "").split(",").map((p) => p.trim().split("=")).filter((p) => p.length === 2 && p[0] !== "v1"));
  const sigs = (header || "").split(",").map((p) => p.trim()).filter((p) => p.startsWith("v1=")).map((p) => p.slice(3));
  const t = Number(parts.t);
  if (!t || !sigs.length || Math.abs(Date.now() / 1000 - t) > 300) return false;
  const expected = await hmacHex(secret, `${t}.${raw}`);
  return sigs.some((sig) => safeEqual(expected, sig.toLowerCase()));
}

async function stripeWebhook(request, env) {
  const raw = await request.text();
  if (!env.STRIPE_WEBHOOK_SECRET) return json({ error: "webhook_not_configured" }, 503);
  if (!(await stripeSignatureOk(env.STRIPE_WEBHOOK_SECRET, request.headers.get("Stripe-Signature"), raw))) return json({ error: "bad_signature" }, 401);

  let event;
  try {
    event = JSON.parse(raw);
  } catch {
    return json({ error: "bad_json" }, 400);
  }
  const obj = event?.data?.object || {};
  const testMode = event?.livemode === false;

  if (event.type === "checkout.session.completed" || event.type === "checkout.session.async_payment_succeeded") {
    const code = normaliseCode(obj.client_reference_id || obj.metadata?.device_code);
    const payment = obj.payment_intent ? String(obj.payment_intent) : String(obj.id || "");
    if (obj.payment_status !== "paid") {
      await logEvent(env, code, "order_not_paid", `${payment} ${obj.payment_status}`);
      return json({ ok: true, ignored: "not_paid" });
    }
    const device = code && (await env.DB.prepare("SELECT code FROM devices WHERE code = ?").bind(code).first());
    if (!device) {
      await logEvent(env, code, "payment_unmatched", `stripe ${payment} ${obj.customer_details?.email || ""}`);
      await notify(env, LIB, "alerts", `⚠️ Stripe payment ${payment} didn't match a device. Find it in the Stripe dashboard and grant the device by hand.`);
      return json({ ok: true, ignored: "unknown_code" });
    }
    // The receipt number may have arrived first (charge.succeeded); otherwise use the end of the payment id for now
    const early = await env.DB.prepare("SELECT detail FROM events WHERE type = 'stripe_receipt' AND detail LIKE ? ORDER BY id DESC LIMIT 1").bind(`${payment} %`).first();
    const orderNumber = early ? early.detail.split(" ")[1] : payment.slice(-8).toUpperCase();
    await env.DB.prepare(
      "UPDATE devices SET status = 'active', source = ?, order_id = ?, order_number = ?, email = ?, activated_at = ? WHERE code = ?",
    ).bind(testMode ? "stripe-test" : "stripe", payment, orderNumber, clip(obj.customer_details?.email, 200), Date.now(), code).run();
    await logEvent(env, code, testMode ? "paid_test" : "paid", `stripe ${payment}`);
    await syncDevice(env, LIB, code);
    await notify(env, LIB, "alerts", `💳 Paid${testMode ? " (test)" : ""}: ${code} unlocked, Stripe ${payment}`);
    return json({ ok: true, activated: code });
  }

  // Receipt number (the one on the buyer's email receipt), so "Already paid?" works with it
  if (event.type === "charge.succeeded" && obj.payment_intent && obj.receipt_number) {
    const res = await env.DB.prepare("UPDATE devices SET order_number = ? WHERE order_id = ?").bind(String(obj.receipt_number), String(obj.payment_intent)).run();
    if (!res.meta?.changes) await logEvent(env, null, "stripe_receipt", `${obj.payment_intent} ${obj.receipt_number}`);
    return json({ ok: true });
  }

  // Full refund: lock the box again (a part refund leaves it unlocked)
  if (event.type === "charge.refunded" && obj.refunded && obj.payment_intent) {
    const payment = String(obj.payment_intent);
    const rows = (await env.DB.prepare("SELECT code FROM devices WHERE order_id = ?").bind(payment).all()).results;
    await env.DB.prepare("UPDATE devices SET status = 'revoked' WHERE order_id = ?").bind(payment).run();
    await logEvent(env, rows[0]?.code ?? null, "refunded", `stripe ${payment} (${rows.length} device)`);
    await notify(env, LIB, "alerts", `↩️ Refund: Stripe ${payment}, device locked`);
    for (const r of rows) await syncDevice(env, LIB, r.code);
    return json({ ok: true });
  }

  return json({ ok: true, ignored: event.type || "unknown_event" });
}

// ---------------------------------------------------------------- admin

async function adminApi(request, env, action, method, url) {
  if (action === "devices" && method === "GET") {
    const q = (url.searchParams.get("q") || "").trim();
    const like = `%${q}%`;
    const rows = await env.DB.prepare(
      `SELECT code, status, source, order_id, order_number, email, note, model, app_version, created_at, activated_at, last_seen, last_self_move, discord_user, beta
       FROM devices WHERE ? = '' OR code LIKE ? OR email LIKE ? OR note LIKE ? OR model LIKE ?
       ORDER BY COALESCE(last_seen, created_at) DESC LIMIT 200`,
    ).bind(q, like, like, like, like).all();
    const counts = await env.DB.prepare("SELECT status, COUNT(*) AS n FROM devices GROUP BY status").all();
    const settings = await getSettings(env);
    for (const d of rows.results) d.trial_ends = trialEnds(d, settings);
    // Unpaid devices split into "on trial" and "trial over" for the stats row
    const unpaid = await env.DB.prepare("SELECT * FROM devices WHERE status = 'unpaid'").all();
    const onTrial = unpaid.results.filter((d) => (trialEnds(d, settings) || 0) > Date.now()).length;
    const countMap = Object.fromEntries(counts.results.map((r) => [r.status, r.n]));
    countMap.trial = onTrial;
    countMap.trial_over = (countMap.unpaid || 0) - onTrial;
    return json({ devices: rows.results, counts: countMap });
  }
  if (action === "crashes" && method === "GET") {
    const rows = await env.DB.prepare("SELECT id, at, code, model, app_version, report, kind FROM crashes ORDER BY id DESC LIMIT 30").all();
    return json({ crashes: rows.results });
  }
  if (action === "crashes/clear" && method === "POST") {
    const body = await readJson(request);
    const res = Number.isInteger(body.id)
      ? await env.DB.prepare("DELETE FROM crashes WHERE id = ?").bind(body.id).run()
      : await env.DB.prepare("DELETE FROM crashes").run();
    await logEvent(env, null, "reports_cleared", Number.isInteger(body.id) ? `report ${body.id}` : "all");
    return json({ ok: true, deleted: res.meta?.changes ?? 0 });
  }
  if (action === "events" && method === "GET") {
    const rows = await env.DB.prepare("SELECT at, code, type, detail FROM events WHERE type != 'stripe_receipt' ORDER BY id DESC LIMIT 100").all();
    return json({ events: rows.results });
  }
  if ((action === "grant" || action === "revoke") && method === "POST") {
    const body = await readJson(request);
    const r = action === "grant" ? await grantCode(env, body.code, clip(body.note, 200)) : await revokeCode(env, body.code, clip(body.note, 200));
    if (!r.ok) return json({ error: r.error }, r.error === "code_not_found" ? 404 : 400);
    return json({ ok: true, code: r.code, status: action === "grant" ? "active" : "revoked" });
  }
  if (action === "reset" && method === "POST") {
    // Back to a fresh box: clears payment details (email, order), source, note and the self-move limit.
    // For test purchases and mistakes. Keeps the device code and its Discord link.
    const body = await readJson(request);
    const code = normaliseCode(body.code);
    if (!code) return json({ error: "bad_code" }, 400);
    const res = await env.DB.prepare(
      "UPDATE devices SET status = 'unpaid', source = NULL, order_id = NULL, order_number = NULL, email = NULL, note = NULL, activated_at = NULL, last_self_move = NULL, transfer_code = NULL, transfer_expires = NULL WHERE code = ?",
    ).bind(code).run();
    if (!res.meta?.changes) return json({ error: "code_not_found" }, 404);
    await logEvent(env, code, "reset", null);
    await syncDevice(env, LIB, code);
    return json({ ok: true, code });
  }
  if (action === "beta" && method === "POST") {
    const body = await readJson(request);
    const code = normaliseCode(body.code);
    if (!code) return json({ error: "bad_code" }, 400);
    const res = await env.DB.prepare("UPDATE devices SET beta = ? WHERE code = ?").bind(body.beta ? 1 : 0, code).run();
    if (!res.meta?.changes) return json({ error: "code_not_found" }, 404);
    await logEvent(env, code, body.beta ? "beta_on" : "beta_off", null);
    await syncDevice(env, LIB, code);
    return json({ ok: true, code });
  }
  if (action === "note" && method === "POST") {
    const body = await readJson(request);
    const code = normaliseCode(body.code);
    if (!code) return json({ error: "bad_code" }, 400);
    const res = await env.DB.prepare("UPDATE devices SET note = ? WHERE code = ?").bind(clip(body.note, 200), code).run();
    if (!res.meta?.changes) return json({ error: "code_not_found" }, 404);
    return json({ ok: true, code });
  }
  if (action === "move" && method === "POST") {
    const body = await readJson(request);
    const r = await moveCodes(env, body.from, body.to);
    if (!r.ok) return json({ error: r.error }, r.error === "code_not_found" ? 404 : 400);
    return json({ ok: true, from: r.from, to: r.to });
  }
  if (action === "diag-link" && method === "POST") {
    const body = await readJson(request);
    if (body.off) {
      await env.DB.prepare("DELETE FROM settings WHERE key = 'diag_key'").run();
      await logEvent(env, null, "diag_link_off", null);
      return json({ ok: true, url: null });
    }
    const key = hex(crypto.getRandomValues(new Uint8Array(16)));
    await env.DB.prepare("INSERT INTO settings (key, value) VALUES ('diag_key', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").bind(key).run();
    await logEvent(env, null, "diag_link_made", null);
    return json({ ok: true, url: `${url.origin}/v1/diag?key=${key}` });
  }
  if (action === "settings" && method === "GET") {
    const settings = await getSettings(env);
    if (settings.diag_key) settings.diag_url = `${url.origin}/v1/diag?key=${settings.diag_key}`;
    delete settings.diag_key;
    return json(settings);
  }
  if (action === "settings" && method === "POST") {
    const body = await readJson(request);
    if (typeof body.paywall_on === "boolean") {
      await env.DB.prepare("INSERT INTO settings (key, value) VALUES ('paywall_on', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").bind(String(body.paywall_on)).run();
      // The trial clock for boxes that were already using the app starts the first time the lock goes on
      if (body.paywall_on) await env.DB.prepare("INSERT OR IGNORE INTO settings (key, value) VALUES ('paywall_on_at', ?)").bind(String(Date.now())).run();
    }
    if (typeof body.early_bird_running === "boolean") {
      const current = (await getSettings(env)).early_bird_end;
      if (body.early_bird_running) await setSetting(env, "early_bird_end", null);
      else if (!current) await setSetting(env, "early_bird_end", Date.now());
    }
    if (body.trial_days !== undefined) {
      const days = Number(body.trial_days);
      if (!Number.isInteger(days) || days < 0 || days > 60) return json({ error: "Trial days must be a whole number from 0 to 60" }, 400);
      await env.DB.prepare("INSERT INTO settings (key, value) VALUES ('trial_days', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").bind(String(days)).run();
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

// 5 wrong passwords from one address within 15 minutes locks that address out for 15 minutes
const ADMIN_MAX_TRIES = 5;
const ADMIN_WINDOW = 15 * 60_000;
async function adminGate(request, env) {
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const now = Date.now();
  const row = await env.DB.prepare("SELECT * FROM admin_fails WHERE ip = ?").bind(ip).first();
  if (row?.locked_until && row.locked_until > now) {
    return json({ error: "too_many_tries", retry_minutes: Math.ceil((row.locked_until - now) / 60_000) }, 429);
  }
  if (await isAdmin(request, env)) {
    if (row) await env.DB.prepare("DELETE FROM admin_fails WHERE ip = ?").bind(ip).run();
    return null;
  }
  const fresh = !row || now - row.first_at > ADMIN_WINDOW || (row.locked_until && row.locked_until <= now);
  const count = fresh ? 1 : row.count + 1;
  const lockedUntil = count >= ADMIN_MAX_TRIES ? now + ADMIN_WINDOW : null;
  await env.DB.prepare(
    "INSERT INTO admin_fails (ip, count, first_at, locked_until) VALUES (?, ?, ?, ?) ON CONFLICT(ip) DO UPDATE SET count = excluded.count, first_at = excluded.first_at, locked_until = excluded.locked_until",
  ).bind(ip, count, fresh ? now : row.first_at, lockedUntil).run();
  if (lockedUntil) {
    await logEvent(env, null, "admin_locked_out", `${ip} after ${count} wrong passwords`);
    await notify(env, LIB, "alerts", `🔒 Admin page: ${count} wrong passwords from one address, locked out for 15 minutes.`);
  }
  return json({ error: "unauthorised" }, 401);
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

// ---------------------------------------------------------------- licence changes shared by the admin page and the Discord bot

async function grantCode(env, rawCode, note) {
  const code = normaliseCode(rawCode);
  if (!code) return { ok: false, error: "bad_code" };
  const res = await env.DB.prepare("UPDATE devices SET status = 'active', source = 'manual', note = COALESCE(?, note), activated_at = ? WHERE code = ?").bind(note || null, Date.now(), code).run();
  if (!res.meta?.changes) return { ok: false, error: "code_not_found" };
  await logEvent(env, code, "granted", note);
  await syncDevice(env, LIB, code);
  await notify(env, LIB, "alerts", `🔓 Unlocked by hand: ${code}${note ? ` (${note})` : ""}`);
  return { ok: true, code };
}

async function revokeCode(env, rawCode, note) {
  const code = normaliseCode(rawCode);
  if (!code) return { ok: false, error: "bad_code" };
  const res = await env.DB.prepare("UPDATE devices SET status = 'revoked', note = COALESCE(?, note) WHERE code = ?").bind(note || null, code).run();
  if (!res.meta?.changes) return { ok: false, error: "code_not_found" };
  await logEvent(env, code, "revoked", note);
  await syncDevice(env, LIB, code);
  await notify(env, LIB, "alerts", `🔒 Locked: ${code}`);
  return { ok: true, code };
}

async function moveCodes(env, rawFrom, rawTo) {
  // Moves an unlock to another box (new box, factory reset): the new code gets the licence, the old one is revoked
  const from = normaliseCode(rawFrom);
  const to = normaliseCode(rawTo);
  if (!from || !to) return { ok: false, error: "bad_code" };
  if (from === to) return { ok: false, error: "Pick a different device" };
  const old = await env.DB.prepare("SELECT * FROM devices WHERE code = ?").bind(from).first();
  const target = await env.DB.prepare("SELECT code, status FROM devices WHERE code = ?").bind(to).first();
  if (!old || !target) return { ok: false, error: "code_not_found" };
  if (old.status !== "active") return { ok: false, error: "Only an unlocked device can be moved" };
  await env.DB.batch([
    ...moveStatements(env, old, to, old.last_self_move),
    env.DB.prepare("UPDATE devices SET note = ? WHERE code = ?").bind(`moved to ${to}`, from),
  ]);
  await logEvent(env, to, "moved", `from ${from}`);
  await syncDevice(env, LIB, to);
  await syncDevice(env, LIB, from);
  await notify(env, LIB, "alerts", `📦 Moved by you: ${from} → ${to}`);
  return { ok: true, from, to };
}

/** The app's Settings > Licence > Link Discord: a 6-digit code to type after /verify. */
async function discordLink(request, env) {
  const body = await readJson(request);
  const deviceId = typeof body.device_id === "string" ? body.device_id.trim() : "";
  if (deviceId.length < 4 || deviceId.length > 128) return json({ error: "bad_device_id" }, 400);
  const device = await env.DB.prepare("SELECT * FROM devices WHERE device_hash = ?").bind(await sha256Hex(DEVICE_SALT + deviceId)).first();
  if (!device) return json({ error: "unknown_device" }, 404);
  const link = await makeLinkCode(env, device);
  return link ? json(link) : json({ error: "server_busy" }, 503);
}

async function setSetting(env, key, value) {
  if (value == null || value === "") await env.DB.prepare("DELETE FROM settings WHERE key = ?").bind(key).run();
  else await env.DB.prepare("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").bind(key, String(value)).run();
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

