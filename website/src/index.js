// AxiomTV website: static pages from ./public, plus two short links.
//   /get      -> latest AxiomTV APK (type this into Downloader)
//   /discord  -> the Discord invite set in wrangler.toml
export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname.replace(/\/+$/, "").toLowerCase();
    if (path === "/get" || path === "/download") return Response.redirect(env.APK_URL, 302);
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
