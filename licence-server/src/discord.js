/**
 * AxiomTV Discord bot (slash commands over HTTP, no always-on connection).
 *
 * Users link a box by typing /verify with the 6-digit code shown in Settings > Licence > Link Discord.
 * The bot gives roles from the licence: Lifetime (unlocked), Early Bird (unlocked before the offer ended),
 * Beta Tester (ticked on the admin page) and Trial (on the free trial), and keeps them in step.
 *
 * Secrets: DISCORD_PUBLIC_KEY, DISCORD_BOT_TOKEN, DISCORD_APP_ID.
 */
import { ERRORS } from "../../website/src/help.js";

const API = "https://discord.com/api/v10";
const EPHEMERAL = 64;
const LINK_CODE_MS = 10 * 60000;

// Permission bits
const P = {
  VIEW: 1n << 10n,
  SEND: 1n << 11n,
  EMBED: 1n << 14n,
  ATTACH: 1n << 15n,
  HISTORY: 1n << 16n,
  REACT: 1n << 6n,
  COMMANDS: 1n << 31n,
  THREAD_SEND: 1n << 38n,
  PUBLIC_THREADS: 1n << 35n,
};
const READ = P.VIEW | P.HISTORY;
const CHAT = READ | P.SEND | P.REACT | P.THREAD_SEND | P.EMBED | P.ATTACH;

const MANAGED_ROLES = ["lifetime", "early", "beta", "trial"];
const ROLE_DEFS = [
  // key, name, colour, shown separately in the member list
  ["support", "Support", 0x6fb6ff, true],
  ["beta", "Beta Tester", 0xc792ff, true],
  ["early", "Early Bird", 0xd9b45a, true],
  ["lifetime", "Lifetime", 0x6fdc8c, true],
  ["trial", "Trial", 0x8f9abb, false],
];

/** Slash commands; registered by scripts/register-commands.mjs during deploy. */
export const COMMANDS = [
  { name: "verify", description: "Link your AxiomTV box: use the code from Settings > Licence > Link Discord", dm_permission: false,
    options: [{ type: 3, name: "code", description: "The 6-digit code on your box", required: true }] },
  { name: "mybox", description: "Your linked boxes: unlocked, trial days left, roles", dm_permission: false },
  { name: "error", description: "Explain an AxiomTV error code", dm_permission: false,
    options: [{ type: 4, name: "code", description: "The error number, e.g. 1001", required: true }] },
  { name: "unlink", description: "Unlink your boxes from your Discord account", dm_permission: false },
  { name: "setup", description: "Admin: create AxiomTV channels, roles and settings", default_member_permissions: "8", dm_permission: false },
  { name: "lookup", description: "Admin: find a box by device code or a member", default_member_permissions: "8", dm_permission: false,
    options: [
      { type: 3, name: "code", description: "Device code, e.g. 7K3P-9QXD", required: false },
      { type: 6, name: "member", description: "A member", required: false },
    ] },
  { name: "unlock", description: "Admin: unlock a box", default_member_permissions: "8", dm_permission: false,
    options: [
      { type: 3, name: "code", description: "Device code", required: true },
      { type: 3, name: "note", description: "Note, e.g. buyer name", required: false },
    ] },
  { name: "revoke", description: "Admin: lock a box again", default_member_permissions: "8", dm_permission: false,
    options: [{ type: 3, name: "code", description: "Device code", required: true }] },
  { name: "move", description: "Admin: move an unlock to another box", default_member_permissions: "8", dm_permission: false,
    options: [
      { type: 3, name: "from", description: "Old box's device code", required: true },
      { type: 3, name: "to", description: "New box's device code", required: true },
    ] },
  { name: "tester", description: "Admin: make a member a beta tester (or undo)", default_member_permissions: "8", dm_permission: false,
    options: [{ type: 6, name: "member", description: "The member", required: true }] },
  { name: "paywall", description: "Admin: turn the lock for unpaid boxes on or off", default_member_permissions: "8", dm_permission: false,
    options: [{ type: 3, name: "state", description: "on or off", required: true, choices: [{ name: "on", value: "on" }, { name: "off", value: "off" }] }] },
  { name: "stats", description: "Admin: devices, unlocks and trials", default_member_permissions: "8", dm_permission: false },
];

/**
 * Plumbing the bot borrows from the licence server (passed in to avoid an import loop):
 * { normaliseCode, getSettings, setSetting, logEvent, grant, revoke, move, trialEnds }
 */
export async function handleInteraction(request, env, ctx, lib) {
  const body = await request.text();
  if (!(await verifySignature(request, body, env.DISCORD_PUBLIC_KEY))) return new Response("bad signature", { status: 401 });
  const i = JSON.parse(body);
  if (i.type === 1) return reply({ type: 1 });
  const userId = i.member?.user?.id || i.user?.id;
  const isAdmin = i.member ? (BigInt(i.member.permissions || "0") & 8n) === 8n : false;

  if (i.type === 3) return handleButton(i, env, ctx, lib, userId, isAdmin);
  if (i.type !== 2) return reply({ type: 4, data: { content: "Not supported.", flags: EPHEMERAL } });

  const name = i.data.name;
  const opt = Object.fromEntries((i.data.options || []).map((o) => [o.name, o.value]));
  const adminOnly = ["setup", "lookup", "unlock", "revoke", "move", "tester", "paywall", "stats"];
  if (adminOnly.includes(name) && !isAdmin) return say("That command is for the server owner.");

  switch (name) {
    case "verify":
      return verifyCommand(env, lib, userId, i.guild_id, String(opt.code || ""));
    case "mybox":
      return say(await myBoxText(env, lib, userId));
    case "error": {
      const e = ERRORS[opt.code];
      if (!e) return say(`There's no AxiomTV error ${opt.code}. All codes: https://axiomtv.co.uk/help`);
      return say(`**Error ${opt.code}: ${e.title}**\n${e.what}\n\n${e.steps.map((s, n) => `${n + 1}. ${s}`).join("\n")}\n\nMore: https://axiomtv.co.uk/help/${opt.code}`);
    }
    case "unlink": {
      const res = await env.DB.prepare("UPDATE devices SET discord_user = NULL WHERE discord_user = ?").bind(userId).run();
      await syncMember(env, lib, i.guild_id, userId);
      return say(res.meta?.changes ? "Your boxes are unlinked. Your AxiomTV roles have been removed." : "You had no linked boxes.");
    }
    case "setup":
      ctx.waitUntil(followUp(env, i, () => setupServer(env, lib, i.guild_id)));
      return reply({ type: 5, data: { flags: EPHEMERAL } });
    case "lookup":
      return say(await lookupText(env, lib, opt.code, opt.member));
    case "unlock": {
      const r = await lib.grant(env, opt.code, opt.note || `Discord ${i.member.user.username}`);
      return say(r.ok ? `${r.code} is now unlocked.` : r.error);
    }
    case "revoke": {
      const r = await lib.revoke(env, opt.code, null);
      return say(r.ok ? `${r.code} is now locked.` : r.error);
    }
    case "move": {
      const r = await lib.move(env, opt.from, opt.to);
      return say(r.ok ? `Moved the unlock from ${r.from} to ${r.to}.` : r.error);
    }
    case "tester": {
      const rows = await env.DB.prepare("SELECT code, beta FROM devices WHERE discord_user = ?").bind(opt.member).all();
      if (!rows.results.length) return say("That member hasn't linked a box yet (they need to /verify first).");
      const on = rows.results.some((r) => !r.beta) ? 1 : 0;
      await env.DB.prepare("UPDATE devices SET beta = ? WHERE discord_user = ?").bind(on, opt.member).run();
      await syncMember(env, lib, i.guild_id, opt.member);
      return say(on ? `<@${opt.member}> is now a beta tester.` : `<@${opt.member}> is no longer a beta tester.`);
    }
    case "paywall": {
      const on = opt.state === "on";
      await lib.setSetting(env, "paywall_on", String(on));
      if (on) await env.DB.prepare("INSERT OR IGNORE INTO settings (key, value) VALUES ('paywall_on_at', ?)").bind(String(Date.now())).run();
      await lib.logEvent(env, null, "settings_changed", `paywall ${on ? "on" : "off"} (Discord)`);
      return say(on ? "The lock is **on**: boxes that aren't unlocked get the payment screen after their trial." : "The lock is **off**: everyone can use AxiomTV.");
    }
    case "stats": {
      const c = await env.DB.prepare("SELECT status, COUNT(*) AS n FROM devices GROUP BY status").all();
      const linked = await env.DB.prepare("SELECT COUNT(DISTINCT discord_user) AS n FROM devices WHERE discord_user IS NOT NULL").first();
      const s = await lib.getSettings(env);
      return say(`Devices: ${c.results.map((r) => `${r.n} ${r.status}`).join(", ") || "none"}\nLinked Discord members: ${linked?.n ?? 0}\nLock: ${s.paywall_on === "true" ? "on" : "off"} | trial days: ${s.trial_days ?? 3} | early bird: ${s.early_bird_end ? "ended" : "running"}`);
    }
  }
  return say("Unknown command.");
}

// ---------------------------------------------------------------- linking

/** The app asks for a code to show in Settings > Licence > Link Discord. */
export async function makeLinkCode(env, device) {
  for (let attempt = 0; attempt < 8; attempt++) {
    const code = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1000000).padStart(6, "0");
    const clash = await env.DB.prepare("SELECT code FROM devices WHERE discord_link_code = ? AND discord_link_expires > ?").bind(code, Date.now()).first();
    if (clash) continue;
    const expires = Date.now() + LINK_CODE_MS;
    await env.DB.prepare("UPDATE devices SET discord_link_code = ?, discord_link_expires = ? WHERE code = ?").bind(code, expires, device.code).run();
    return { link_code: code, expires_at: expires };
  }
  return null;
}

async function verifyCommand(env, lib, userId, guildId, raw) {
  const code = raw.replace(/\D/g, "");
  if (code.length !== 6) return say("Type the 6-digit code from your box: Settings > Licence > Link Discord.");
  const device = await env.DB.prepare("SELECT * FROM devices WHERE discord_link_code = ? AND discord_link_expires > ?").bind(code, Date.now()).first();
  if (!device) {
    await lib.logEvent(env, null, "discord_verify_failed", userId);
    return say("That code isn't right or has run out. Open Settings > Licence > Link Discord on your box for a new one.");
  }
  await env.DB.prepare("UPDATE devices SET discord_user = ?, discord_link_code = NULL, discord_link_expires = NULL WHERE code = ?").bind(userId, device.code).run();
  await lib.logEvent(env, device.code, "discord_linked", userId);
  const roles = await syncMember(env, lib, guildId, userId);
  const names = roles.map((k) => ROLE_DEFS.find((d) => d[0] === k)?.[1]).filter(Boolean);
  const status = device.status === "active" ? "unlocked" : lib.trialEnds(device, await lib.getSettings(env)) > Date.now() ? "on its free trial" : "not unlocked yet";
  return say(`Linked box **${device.code}** (${status}).${names.length ? ` Your roles: ${names.join(", ")}.` : " Unlock it to get the Lifetime role: see #buy-unlock."}`);
}

async function myBoxText(env, lib, userId) {
  const rows = await env.DB.prepare("SELECT * FROM devices WHERE discord_user = ?").bind(userId).all();
  if (!rows.results.length) return "You haven't linked a box. On your box: Settings > Licence > Link Discord, then type /verify with the code.";
  const s = await lib.getSettings(env);
  return rows.results.map((d) => {
    const ends = lib.trialEnds(d, s);
    const state = d.status === "active" ? "unlocked" : ends && ends > Date.now() ? `free trial, ${Math.ceil((ends - Date.now()) / 86400000)} days left` : d.status === "revoked" ? "locked" : "not unlocked";
    return `**${d.code}**: ${state}${d.model ? ` (${d.model})` : ""}${d.beta ? ", beta tester" : ""}`;
  }).join("\n");
}

async function lookupText(env, lib, code, member) {
  let rows;
  if (code) rows = await env.DB.prepare("SELECT * FROM devices WHERE code = ?").bind(lib.normaliseCode(code)).all();
  else if (member) rows = await env.DB.prepare("SELECT * FROM devices WHERE discord_user = ?").bind(member).all();
  else return "Give a device code or a member.";
  if (!rows.results.length) return "Nothing found.";
  return rows.results.map((d) => [
    `**${d.code}**: ${d.status}${d.source ? ` (${d.source})` : ""}${d.beta ? ", beta tester" : ""}`,
    `${d.model || "?"} · v${d.app_version || "?"}${d.note ? ` · ${d.note}` : ""}`,
    d.discord_user ? `Discord: <@${d.discord_user}>` : "Not linked to Discord",
  ].join("\n")).join("\n\n");
}

// ---------------------------------------------------------------- roles

/** Which managed roles a member should have, from all their linked boxes. */
async function deservedRoles(env, lib, userId) {
  const rows = await env.DB.prepare("SELECT * FROM devices WHERE discord_user = ?").bind(userId).all();
  const s = await lib.getSettings(env);
  const earlyEnd = Number(s.early_bird_end) || 0;
  const want = new Set();
  for (const d of rows.results) {
    if (d.status === "active") {
      want.add("lifetime");
      if (!earlyEnd || (d.activated_at && d.activated_at < earlyEnd)) want.add("early");
    } else {
      const ends = lib.trialEnds(d, s);
      if (ends && ends > Date.now()) want.add("trial");
    }
    if (d.beta) want.add("beta");
  }
  if (want.has("lifetime")) want.delete("trial");
  return [...want];
}

/** Adds and removes the bot's roles on one member to match their boxes. Returns the role keys they now have. */
export async function syncMember(env, lib, guildId, userId) {
  const s = await lib.getSettings(env);
  guildId = guildId || s.discord_guild;
  if (!env.DISCORD_BOT_TOKEN || !guildId || !userId) return [];
  const want = await deservedRoles(env, lib, userId);
  for (const key of MANAGED_ROLES) {
    const roleId = s[`discord_role_${key}`];
    if (!roleId) continue;
    const method = want.includes(key) ? "PUT" : "DELETE";
    await discord(env, method, `/guilds/${guildId}/members/${userId}/roles/${roleId}`).catch(() => {});
  }
  return want;
}

/** After a licence changes (paid, refunded, moved…), update that box's Discord member. */
export async function syncDevice(env, lib, code) {
  try {
    const d = await env.DB.prepare("SELECT discord_user FROM devices WHERE code = ?").bind(code).first();
    if (d?.discord_user) await syncMember(env, lib, null, d.discord_user);
  } catch (err) {
    console.error("discord sync", err);
  }
}

// ---------------------------------------------------------------- server set-up

async function setupServer(env, lib, guildId) {
  const s = await lib.getSettings(env);
  const save = (k, v) => lib.setSetting(env, k, v);
  await save("discord_guild", guildId);
  const everyone = guildId;

  // Roles
  const existing = await discord(env, "GET", `/guilds/${guildId}/roles`);
  const role = {};
  for (const [key, name, color, hoist] of ROLE_DEFS) {
    let r = existing.find((x) => x.id === s[`discord_role_${key}`]) || existing.find((x) => x.name === name);
    if (!r) r = await discord(env, "POST", `/guilds/${guildId}/roles`, { name, color, hoist, mentionable: false, permissions: "0" });
    role[key] = r.id;
    await save(`discord_role_${key}`, r.id);
  }

  // Server safety settings: High verification, scan all media, mentions-only notifications
  await discord(env, "PATCH", `/guilds/${guildId}`, { verification_level: 3, explicit_content_filter: 2, default_message_notifications: 1 }).catch(() => {});
  // @everyone can't ping everyone or make invites
  const everyoneRole = existing.find((x) => x.id === everyone);
  if (everyoneRole) {
    const perms = (BigInt(everyoneRole.permissions) & ~(1n << 17n) & ~1n) | P.VIEW;
    await discord(env, "PATCH", `/guilds/${guildId}/roles/${everyone}`, { permissions: perms.toString() }).catch(() => {});
  }

  const paidish = [role.trial, role.lifetime, role.beta, role.support];
  const buyers = [role.lifetime, role.beta, role.support];
  const ow = (id, allow, deny = 0n) => ({ id, type: 0, allow: allow.toString(), deny: deny.toString() });
  const hidden = ow(everyone, 0n, P.VIEW);
  const readOnlyFor = (ids) => [hidden, ...ids.map((id) => ow(id, READ, P.SEND))];
  const chatFor = (ids) => [hidden, ...ids.map((id) => ow(id, CHAT))];

  const channels = await discord(env, "GET", `/guilds/${guildId}/channels`);
  const ensure = async (key, def) => {
    let ch = channels.find((c) => c.id === s[`discord_ch_${key}`]) || channels.find((c) => c.name === def.name && c.type === def.type);
    if (ch) ch = await discord(env, "PATCH", `/channels/${ch.id}`, def);
    else ch = await discord(env, "POST", `/guilds/${guildId}/channels`, def);
    channels.push(ch);
    await save(`discord_ch_${key}`, ch.id);
    return ch;
  };

  const startCat = await ensure("cat_start", { name: "📌 START HERE", type: 4, position: 0, permission_overwrites: [ow(everyone, READ, P.SEND)] });
  const welcome = await ensure("welcome", { name: "welcome", type: 0, parent_id: startCat.id, permission_overwrites: [ow(everyone, READ, P.SEND)] });
  const rules = await ensure("rules", { name: "rules", type: 0, parent_id: startCat.id, permission_overwrites: [ow(everyone, READ, P.SEND)] });
  const verify = await ensure("verify", { name: "verify", type: 0, parent_id: startCat.id, topic: "Type /verify with the 6-digit code from Settings > Licence > Link Discord",
    permission_overwrites: [ow(everyone, READ | P.COMMANDS, P.SEND)] });
  const getApp = await ensure("get", { name: "get-axiomtv", type: 0, parent_id: startCat.id, permission_overwrites: [ow(everyone, READ, P.SEND)] });

  const newsCat = await ensure("cat_news", { name: "📣 NEWS", type: 4, position: 1, permission_overwrites: readOnlyFor(paidish) });
  await ensure("announcements", { name: "announcements", type: 0, parent_id: newsCat.id, permission_overwrites: readOnlyFor(paidish) });
  await ensure("status", { name: "status", type: 0, parent_id: newsCat.id, permission_overwrites: readOnlyFor(paidish) });

  const supportCat = await ensure("cat_support", { name: "🛠️ SUPPORT", type: 4, position: 2, permission_overwrites: chatFor(paidish) });
  await ensure("help", { name: "help", type: 15, parent_id: supportCat.id, rate_limit_per_user: 30,
    topic: "One post per problem. Include the error code (if any), your box model and what you were doing. Never post logins or playlists.",
    available_tags: ["Live TV", "Films & Series", "TV guide", "Install", "Licence", "Error code", "Solved"].map((name) => ({ name })),
    permission_overwrites: chatFor(paidish) });
  const errorCodes = await ensure("error_codes", { name: "error-codes", type: 0, parent_id: supportCat.id, permission_overwrites: readOnlyFor(paidish) });

  const communityCat = await ensure("cat_community", { name: "💬 COMMUNITY", type: 4, position: 3, permission_overwrites: chatFor(buyers) });
  await ensure("general", { name: "general", type: 0, parent_id: communityCat.id, rate_limit_per_user: 30, permission_overwrites: chatFor(buyers) });
  await ensure("requests", { name: "feature-requests", type: 15, parent_id: communityCat.id,
    topic: "One idea per post. Vote with 👍.", available_tags: ["Idea", "Planned", "Done", "Not planned"].map((name) => ({ name })),
    permission_overwrites: chatFor(buyers) });

  const betaCat = await ensure("cat_beta", { name: "🧪 BETA", type: 4, position: 4, permission_overwrites: chatFor([role.beta]) });
  await ensure("beta_builds", { name: "beta-builds", type: 0, parent_id: betaCat.id, permission_overwrites: readOnlyFor([role.beta]) });
  await ensure("beta_feedback", { name: "beta-feedback", type: 0, parent_id: betaCat.id, permission_overwrites: chatFor([role.beta]) });

  const buyCat = await ensure("cat_buy", { name: "🎟️ BUY / UNLOCK", type: 4, position: 5, permission_overwrites: [ow(everyone, READ, P.SEND)] });
  const buy = await ensure("buy", { name: "buy-unlock", type: 0, parent_id: buyCat.id, permission_overwrites: [ow(everyone, READ, P.SEND)] });

  const adminCat = await ensure("cat_admin", { name: "🔒 ADMIN", type: 4, position: 6, permission_overwrites: [hidden] });
  for (const key of ["alerts", "reports", "mod_log", "bot_admin"]) {
    await ensure(key, { name: key.replace("_", "-"), type: 0, parent_id: adminCat.id, permission_overwrites: [hidden] });
  }

  // Messages (only posted once)
  const once = async (key, channelId, payload) => {
    if (s[`discord_msg_${key}`]) return;
    const m = await discord(env, "POST", `/channels/${channelId}/messages`, payload);
    await save(`discord_msg_${key}`, m.id);
  };
  await once("welcome", welcome.id, { content: [
    "# Welcome to AxiomTV",
    "AxiomTV is a fast, good-looking IPTV **player** for Android TV boxes, Fire TV and phones. It doesn't include any channels: you sign in with your own provider.",
    "",
    "**Already have AxiomTV?** Go to <#" + verify.id + "> to link your box and open up the server.",
    "**New here?** See <#" + getApp.id + "> for the free trial.",
    "Website: https://axiomtv.co.uk",
  ].join("\n") });
  await once("rules", rules.id, { content: [
    "# Rules",
    "1. **No providers, playlists, logins or reseller offers.** Don't ask for them, share them or sell them. This server is about the AxiomTV player only.",
    "2. Be decent. No abuse, spam or adverts.",
    "3. Keep help questions in the help forum, one post per problem, with your error code and box model.",
    "4. **Staff will never DM you first** or ask for your login or payment details. Report anyone who does.",
    "5. Breaking rule 1 means a ban, no warning.",
  ].join("\n") });
  await once("verify", verify.id, { content: [
    "# Link your box",
    "1. On your box open **Settings > Licence > Link Discord**.",
    "2. A 6-digit code appears (it works for 10 minutes).",
    "3. Type **/verify** here followed by the code, e.g. `/verify 482913`.",
    "You'll get your roles straight away: **Lifetime** if your box is unlocked, **Trial** if it's on its free trial.",
  ].join("\n") });
  await once("get", getApp.id, { content: [
    "# Get AxiomTV",
    "**Install:** in the Downloader app on your box, type **get.axiomtv.co.uk**",
    "**Free trial:** every box gets 3 days with every feature.",
    "**Unlock for life:** scan the QR code on the payment screen, or see <#" + buy.id + ">.",
    "Install help and FAQ: https://axiomtv.co.uk",
  ].join("\n") });
  await once("error_codes", errorCodes.id, { content: "# Error codes\n" +
    Object.entries(ERRORS).map(([c, e]) => `**${c}**: ${e.title}`).join("\n") +
    "\n\nType `/error 1001` for help with any code, or see https://axiomtv.co.uk/help" });
  await once("buy", buy.id, {
    content: "# Unlock AxiomTV\nPress **Open ticket** to unlock your box with us. Have your **device code** ready: it's on the payment screen and in Settings > Licence.",
    components: [{ type: 1, components: [{ type: 2, style: 1, label: "Open ticket", custom_id: "ticket_open", emoji: { name: "🎟️" } }] }],
  });

  // AutoMod: block provider and playlist talk
  const rulesNow = await discord(env, "GET", `/guilds/${guildId}/auto-moderation/rules`).catch(() => []);
  if (!rulesNow.some((r) => r.name === "AxiomTV: no providers or playlists")) {
    await discord(env, "POST", `/guilds/${guildId}/auto-moderation/rules`, {
      name: "AxiomTV: no providers or playlists",
      event_type: 1,
      trigger_type: 1,
      trigger_metadata: {
        keyword_filter: ["*m3u*", "*xtream*", "*get.php*", "*player_api*", "*playlist*", "*reseller*", "dm me", "cheap iptv", "*iptv line*", "*username=*", "*password=*"],
      },
      actions: [
        { type: 1, metadata: { custom_message: "Providers, playlists and logins aren't allowed here." } },
        { type: 2, metadata: { channel_id: s.discord_ch_mod_log || (await lib.getSettings(env)).discord_ch_mod_log } },
      ],
      enabled: true,
    }).catch((err) => console.error("automod", err));
  }
  return "Done: roles, channels, messages and AutoMod are set up. Delete Discord's default #general and voice channels if you don't want them, then give yourself a look round.";
}

// ---------------------------------------------------------------- tickets

async function handleButton(i, env, ctx, lib, userId, isAdmin) {
  const id = i.data.custom_id;
  const s = await lib.getSettings(env);
  if (id === "ticket_open") {
    const name = `ticket-${(i.member.user.username || "user").toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 20) || userId.slice(-4)}`;
    const guildId = i.guild_id;
    const ch = await discord(env, "POST", `/guilds/${guildId}/channels`, {
      name,
      type: 0,
      parent_id: s.discord_ch_cat_buy,
      topic: `Ticket for <@${userId}>`,
      permission_overwrites: [
        { id: guildId, type: 0, allow: "0", deny: P.VIEW.toString() },
        { id: userId, type: 1, allow: CHAT.toString(), deny: "0" },
        ...(s.discord_role_support ? [{ id: s.discord_role_support, type: 0, allow: CHAT.toString(), deny: "0" }] : []),
      ],
    });
    await discord(env, "POST", `/channels/${ch.id}/messages`, {
      content: `Hi <@${userId}>, thanks for opening a ticket. Please send your **device code** (on the payment screen, or Settings > Licence) and we'll sort your unlock.`,
      components: [{ type: 1, components: [{ type: 2, style: 4, label: "Close ticket", custom_id: "ticket_close" }] }],
    });
    await notify(env, lib, "alerts", `🎟️ New ticket from <@${userId}>: <#${ch.id}>`);
    return say(`Your ticket is open: <#${ch.id}>`);
  }
  if (id === "ticket_close") {
    const topic = i.channel?.topic || "";
    if (!isAdmin && !topic.includes(userId)) return say("Only the person who opened this ticket, or the owner, can close it.");
    ctx.waitUntil(discord(env, "DELETE", `/channels/${i.channel_id}`).catch(() => {}));
    return reply({ type: 4, data: { content: "Closing this ticket…" } });
  }
  return say("That button doesn't do anything any more.");
}

// ---------------------------------------------------------------- notifications and the hourly job

/** Posts to one of the admin channels (alerts, reports) if the server is set up. */
export async function notify(env, lib, key, content) {
  try {
    if (!env.DISCORD_BOT_TOKEN) return;
    const s = await lib.getSettings(env);
    const channel = s[`discord_ch_${key}`];
    if (!channel) return;
    await discord(env, "POST", `/channels/${channel}/messages`, { content: content.slice(0, 1900), allowed_mentions: { parse: [] } });
  } catch (err) {
    console.error("discord notify", err);
  }
}

/** Hourly: announce new app versions, and refresh roles of members whose trial has ended. */
export async function hourly(env, lib) {
  if (!env.DISCORD_BOT_TOKEN) return;
  const s = await lib.getSettings(env);
  if (!s.discord_guild) return;
  try {
    const res = await fetch("https://api.github.com/repos/FiTH-g1gGLe/AxiomTV/releases/latest", { headers: { "user-agent": "axiomtv-licence", accept: "application/vnd.github+json" } });
    if (res.ok) {
      const rel = await res.json();
      if (rel.tag_name && rel.tag_name !== s.discord_last_release) {
        if (s.discord_last_release && s.discord_ch_announcements) {
          const notes = String(rel.body || "").replace(/\r/g, "").split("\n").filter((l) => l.trim()).slice(0, 15).join("\n");
          await discord(env, "POST", `/channels/${s.discord_ch_announcements}/messages`, {
            content: `# AxiomTV ${rel.tag_name.replace(/^v/, "")} is out\n${notes}\n\nYour box will offer the update next time you open AxiomTV.`.slice(0, 1900),
          });
        }
        await lib.setSetting(env, "discord_last_release", rel.tag_name);
      }
    }
  } catch (err) {
    console.error("release check", err);
  }
  // Trials that ended in the last ~2 hours: drop the Trial role
  const rows = await env.DB.prepare("SELECT * FROM devices WHERE discord_user IS NOT NULL AND status = 'unpaid'").all();
  for (const d of rows.results) {
    const ends = lib.trialEnds(d, s);
    if (ends && ends < Date.now() && ends > Date.now() - 2 * 3600000) await syncMember(env, lib, s.discord_guild, d.discord_user);
  }
}

// ---------------------------------------------------------------- helpers

async function followUp(env, i, work) {
  let text;
  try {
    text = await work();
  } catch (err) {
    console.error(err);
    text = `Something went wrong: ${err.message}. Check the bot has Administrator permission and its role is near the top of the role list.`;
  }
  await fetch(`${API}/webhooks/${i.application_id}/${i.token}/messages/@original`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ content: text.slice(0, 1900) }),
  });
}

async function discord(env, method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: { authorization: `Bot ${env.DISCORD_BOT_TOKEN}`, "content-type": "application/json", "user-agent": "AxiomTV bot (https://axiomtv.co.uk, 1)" },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 204) return null;
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new Error(`Discord ${method} ${path}: ${res.status} ${JSON.stringify(data)?.slice(0, 200)}`);
  return data;
}

async function verifySignature(request, body, publicKey) {
  if (!publicKey) return false;
  const sig = request.headers.get("x-signature-ed25519") || "";
  const ts = request.headers.get("x-signature-timestamp") || "";
  try {
    const key = await crypto.subtle.importKey("raw", hexBytes(publicKey), { name: "Ed25519" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "Ed25519" }, key, hexBytes(sig), new TextEncoder().encode(ts + body));
  } catch {
    return false;
  }
}

function hexBytes(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

function reply(data) {
  return new Response(JSON.stringify(data), { headers: { "content-type": "application/json" } });
}

function say(content) {
  return reply({ type: 4, data: { content: String(content).slice(0, 1900), flags: EPHEMERAL, allowed_mentions: { parse: [] } } });
}
