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
<footer><div class="wrap"><span>© 2026 AxiomTV</span><nav aria-label="Legal"><a href="/guides">Guides</a><a href="/terms.html">Terms</a><a href="/privacy.html">Privacy</a><a href="/refunds.html">Refunds</a><a href="/discord">Contact</a></nav></div></footer>
</body></html>`;
  return new Response(page, { status, headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=600" } });
}
