// Shared page shell for pages the worker builds (help and guides), with the tags search engines and link previews read.
export const SITE = "https://axiomtv.co.uk";

export const html = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

export function layout({ title, description, path, body, status = 200, type = "website", jsonld, noindex = false }) {
  const url = SITE + path;
  const page = `<!doctype html><html lang="en-GB"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${html(title)}</title>
<meta name="description" content="${html(description)}">
${noindex ? `<meta name="robots" content="noindex">` : `<link rel="canonical" href="${url}">`}
<meta property="og:type" content="${type}"><meta property="og:site_name" content="AxiomTV"><meta property="og:locale" content="en_GB">
<meta property="og:title" content="${html(title)}"><meta property="og:description" content="${html(description)}">
<meta property="og:url" content="${url}"><meta property="og:image" content="${SITE}/share.png"><meta property="og:image:width" content="1200"><meta property="og:image:height" content="630">
<meta name="twitter:card" content="summary_large_image">
<link rel="icon" href="/icon.png"><link rel="stylesheet" href="/style.css">
${jsonld ? `<script type="application/ld+json">${JSON.stringify(jsonld).replace(/</g, "\\u003c")}</script>` : ""}
</head><body>
<header class="top"><div class="wrap"><a class="brand" href="/"><img src="/logo.png" alt="AxiomTV home"><span class="wordmark">AXIOMTV</span></a>
<nav aria-label="Main"><a href="/guides">Guides</a><a href="/help">Error codes</a><a class="btn gold small" href="/#install">Get AxiomTV</a></nav></div></header>
<main class="legal">${body}</main>
<footer class="site-foot">  <div class="wrap foot-grid">    <div class="foot-brand">      <a class="foot-logo" href="/"><img src="/logo.png" alt="AxiomTV home" width="96" height="96"></a>      <div>        <div class="wordmark foot-word">AXIOMTV</div>        <p class="foot-tag">The premium IPTV player for Android TV, Fire TV and phones.</p>        <p class="foot-by">By g1gGLe · Coded with Claude</p>      </div>    </div>    <nav class="foot-col" aria-label="Product"><h3>Product</h3><a href="/#install">Get AxiomTV</a><a href="/guides">Guides</a><a href="/help">Error codes</a></nav>    <nav class="foot-col" aria-label="Support"><h3>Support</h3><a href="/discord">Discord</a><a href="mailto:support@axiomtv.co.uk" title="support@axiomtv.co.uk">Email us</a><a href="/link">Unlock a device</a></nav>    <nav class="foot-col" aria-label="Legal"><h3>Legal</h3><a href="/terms.html">Terms</a><a href="/privacy.html">Privacy</a><a href="/refunds.html">Refunds</a></nav>  </div>  <div class="wrap foot-base"><span>© 2026 AxiomTV · <a href="mailto:support@axiomtv.co.uk">support@axiomtv.co.uk</a></span><span>A media player. No channels or content included.</span></div></footer>
</body></html>`;
  return new Response(page, { status, headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=600" } });
}
