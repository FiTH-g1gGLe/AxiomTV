import { helpPage } from "./help.js";
// AxiomTV website: static pages from ./public, plus two short links.
//   /get      -> latest AxiomTV APK on GitHub (type this into Downloader)
//   /backup   -> the same APK passed through Cloudflare
//   /discord  -> the Discord invite set in wrangler.toml
async function backupDownload(env) {
  const upstream = await fetch(env.APK_URL, { redirect: "follow", headers: { "user-agent": "axiomtv-site" } });
  if (!upstream.ok || !upstream.body) {
    return new Response("The download isn't available right now. Please try again in a few minutes.", { status: 502 });
  }
  const headers = new Headers({
    "content-type": "application/vnd.android.package-archive",
    "content-disposition": 'attachment; filename="AxiomTV.apk"',
    "cache-control": "no-store",
  });
  const length = upstream.headers.get("content-length");
  if (length) headers.set("content-length", length);
  return new Response(upstream.body, { status: 200, headers });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const host = url.hostname.toLowerCase();
    // get.  -> straight to GitHub (counts on GitHub's download counter)
    // download. or /backup -> the same GitHub file, passed through Cloudflare, for boxes that can't reach GitHub directly
    if (host.startsWith("get.")) return Response.redirect(env.APK_URL, 302);
    if (host.startsWith("download.")) return backupDownload(env);
    if (host.startsWith("discord.")) return Response.redirect(env.DISCORD_URL || "https://axiomtv.co.uk/discord", 302);
    if (host.startsWith("www.")) return Response.redirect(`https://${host.slice(4)}${url.pathname}${url.search}`, 301);
    const path = url.pathname.replace(/\/+$/, "").toLowerCase();
    if (path === "/get") return Response.redirect(env.APK_URL, 302);
    if (path === "/backup" || path === "/download") return backupDownload(env);
    if (path === "/help" || path.startsWith("/help/")) return helpPage(path);
    if (path === "/discord" || path === "/contact") {
      if (env.DISCORD_URL) return Response.redirect(env.DISCORD_URL, 302);
      return new Response(
        `<!doctype html><html lang="en-GB"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Contact – AxiomTV</title><link rel="stylesheet" href="/style.css"></head><body><main class="legal"><h1>Contact</h1><p>Our Discord link is coming very soon. Please check back shortly.</p><p><a href="/">Back to AxiomTV</a></p></main></body></html>`,
        { headers: { "content-type": "text/html; charset=utf-8" } },
      );
    }
    return env.ASSETS.fetch(request);
  },
};
