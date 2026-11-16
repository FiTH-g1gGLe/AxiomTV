import { helpPage, ERRORS } from "./help.js";
import { guidesPage, GUIDES } from "./guides.js";
import { SITE } from "./layout.js";

// Pages listed for search engines. Help and guide pages are added from their lists, so new ones appear by themselves.
function sitemap() {
  const pages = ["/", "/guides", "/help", "/terms.html", "/privacy.html", "/refunds.html",
    ...Object.keys(GUIDES).map((s) => `/guides/${s}`), ...Object.keys(ERRORS).map((c) => `/help/${c}`)];
  const body = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n` +
    pages.map((p) => `  <url><loc>${SITE}${p}</loc></url>`).join("\n") + `\n</urlset>\n`;
  return new Response(body, { headers: { "content-type": "application/xml; charset=utf-8", "cache-control": "public, max-age=3600" } });
}
const ROBOTS = `User-agent: *\nDisallow: /link/\nDisallow: /pay/\nDisallow: /admin\nDisallow: /v1/\nDisallow: /backup\nDisallow: /download\n\nSitemap: ${SITE}/sitemap.xml\n`;
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
    // Payment QR code: axiomtv.co.uk/link/<device code> shows the licence server's pay page under our own address
    const pay = url.pathname.match(/^\/(?:link|pay)\/([A-Za-z0-9-]{4,12})\/?$/);
    if (pay) {
      const target = new Request(`${env.LICENCE_URL}/pay/${pay[1]}`, { redirect: "manual", headers: { "user-agent": "axiomtv-site" } });
      const upstream = env.LICENCE ? await env.LICENCE.fetch(target) : await fetch(target);
      const headers = new Headers(upstream.headers);
      headers.set("x-robots-tag", "noindex");
      return new Response(upstream.body, { status: upstream.status, headers });
    }
    // Admin page and the server's API under our own address: axiomtv.co.uk/admin (same as the licence server's /admin)
    if (path === "/admin" || url.pathname.startsWith("/v1/")) {
      const target = new Request(`${env.LICENCE_URL}${url.pathname}${url.search}`, request);
      const upstream = env.LICENCE ? await env.LICENCE.fetch(target) : await fetch(target);
      const headers = new Headers(upstream.headers);
      headers.set("x-robots-tag", "noindex");
      headers.set("cache-control", "no-store");
      return new Response(upstream.body, { status: upstream.status, headers });
    }
    if (path === "/get") return Response.redirect(env.APK_URL, 302);
    if (path === "/backup" || path === "/download") return backupDownload(env);
    if (path === "/help" || path.startsWith("/help/")) return helpPage(path);
    if (path === "/guides" || path.startsWith("/guides/")) {
      const page = guidesPage(path);
      if (page) return page;
    }
    if (path === "/robots.txt") return new Response(ROBOTS, { headers: { "content-type": "text/plain; charset=utf-8" } });
    if (path === "/sitemap.xml") return sitemap();
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
