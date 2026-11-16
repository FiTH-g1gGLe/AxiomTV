// Help pages for AxiomTV error codes (the QR code on the TV's Help screen opens /help/<code>).
// Keep in step with app/src/main/java/com/axiomtv/player/AxiomErrors.kt.
const P = "provider", I = "internet", A = "axiomtv";

export const ERRORS = {
  1001: { title: "Channel offline", source: P,
    what: "Your provider isn't sending this channel right now. It's not a problem with your box or AxiomTV, and other channels usually still work.",
    steps: ["Pick another channel and come back to this one in a few minutes.", "If several channels are offline at once, your provider may be having problems. Check their status page or group.", "If one channel stays offline for hours, tell your provider which channel it is."] },
  1002: { title: "Not available any more", source: P,
    what: "Your provider has removed or moved this channel, film or episode, so the link AxiomTV had for it no longer works.",
    steps: ["Go back and pick it again from the list.", "In Settings, refresh your channels and library so AxiomTV gets your provider's latest list.", "If it's still missing, ask your provider."] },
  1003: { title: "Took too long to start", source: P,
    what: "Your provider's server didn't start sending the video in time. This is usually a busy or slow server.",
    steps: ["Press Try again.", "Try a different channel or film. If those work, it's just this one stream.", "If everything is slow, wait a while or check with your provider. Test your internet speed too."] },
  1004: { title: "Too many screens", source: P,
    what: "Your provider account is already being used on its maximum number of screens at the same time.",
    steps: ["Stop watching on another TV, phone or app that uses the same login, then press Try again.", "Closing an app sometimes takes a minute or two to free the screen up.", "If you need more screens at once, ask your provider about upgrading."] },
  1005: { title: "Video format not supported", source: A,
    what: "This box can't play the way this video has been encoded. Some very new or unusual formats aren't supported by every box.",
    steps: ["Press Try again: AxiomTV may switch to its other player.", "In the player, press Right and pick the other engine.", "Try another version (SD or HD) if your provider has one."] },
  2001: { title: "Login not accepted", source: P,
    what: "Your provider didn't accept the username or password AxiomTV is using.",
    steps: ["Check your login details with your provider: they may have changed.", "In AxiomTV, sign out in Settings and sign in again with the new details."] },
  2002: { title: "Your provider account has expired", source: P,
    what: "Your subscription with your TV provider has ended, so there's nothing to play. AxiomTV itself is working fine and stays unlocked.",
    steps: ["Renew with your provider, then press Try again.", "Got a new login? Sign out in Settings and enter it."] },
  2003: { title: "Account suspended", source: P,
    what: "Your provider has suspended or disabled your account.",
    steps: ["Contact your provider to find out why and get it turned back on."] },
  2004: { title: "Provider's server isn't answering", source: P,
    what: "Your provider's server is down, very slow, or the address is wrong. It's not your box.",
    steps: ["Wait a few minutes and try again.", "If you've just signed in, check the server address with your provider.", "If it lasts, check with your provider."] },
  2005: { title: "Provider refused the connection", source: P,
    what: "Your provider's server refused to send this. That often happens when you use a VPN, or when an account is restricted.",
    steps: ["Turn off any VPN or proxy and press Try again.", "If it keeps happening, contact your provider."] },
  3001: { title: "No internet connection", source: I,
    what: "This box isn't connected to the internet.",
    steps: ["Check the Wi-Fi or network cable on the box.", "See if other devices in your home can get online.", "If none can, restart your router, or contact your internet company."] },
  3002: { title: "Connection dropped, reconnecting", source: I,
    what: "The connection to your provider dropped for a moment. AxiomTV reconnects by itself, up to three times.",
    steps: ["Wait a few seconds: it usually comes back on its own.", "If it happens a lot, your Wi-Fi may be weak. A network cable or moving the router closer helps."] },
};

const WHO = {
  provider: `<b>Who can fix it:</b> this comes from your TV provider, so only they can fix it. Contact your provider and tell them what you see. AxiomTV can't change what your provider sends.`,
  internet: `<b>Who can fix it:</b> this is your internet connection. If none of your devices can get online, contact your internet company.`,
  axiomtv: `<b>Still stuck?</b> Post in the help forum on our <a href="/discord">Discord</a> (discord.axiomtv.co.uk) with the error code and your device code (Settings &rarr; Licence).`,
};

const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

function page(title, body) {
  return `<!doctype html><html lang="en-GB"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)} – AxiomTV help</title><link rel="icon" href="/icon.png"><link rel="stylesheet" href="/style.css"></head><body>
<header class="top"><div class="wrap"><a class="brand" href="/"><img src="/logo.png" alt=""><span class="wordmark">AXIOMTV</span></a>
<nav aria-label="Main"><a href="/help">All errors</a></nav></div></header>
<main class="legal">${body}</main>
<footer><div class="wrap"><span>© 2026 AxiomTV</span><nav aria-label="Legal"><a href="/terms.html">Terms</a><a href="/privacy.html">Privacy</a><a href="/refunds.html">Refunds</a><a href="/discord">Contact</a></nav></div></footer>
</body></html>`;
}

export function helpPage(path) {
  const code = path.replace(/^\/help\/?/, "");
  if (!code) {
    const groups = [["1", "Streams"], ["2", "Your provider account"], ["3", "Your internet"]];
    const list = groups.map(([d, name]) => `<h2>${name}</h2><ul>` +
      Object.entries(ERRORS).filter(([c]) => c.startsWith(d)).map(([c, e]) => `<li><a href="/help/${c}">Error ${c}: ${esc(e.title)}</a></li>`).join("") +
      `</ul>`).join("");
    return new Response(page("Error codes", `<h1>Error codes</h1><p class="updated">Every AxiomTV error has a code. Pick yours for what it means and what to do.</p>${list}`),
      { headers: { "content-type": "text/html; charset=utf-8" } });
  }
  const e = ERRORS[code];
  if (!e) {
    return new Response(page("Unknown error", `<h1>We don't know that code</h1><p>Check the number on your screen, or see <a href="/help">all error codes</a>.</p>`),
      { status: 404, headers: { "content-type": "text/html; charset=utf-8" } });
  }
  const body = `<p class="updated">Error ${code}</p><h1>${esc(e.title)}</h1>
<h2>What it means</h2><p>${esc(e.what)}</p>
<h2>What to try</h2><ol>${e.steps.map((s) => `<li>${esc(s)}</li>`).join("")}</ol>
<div class="callout" style="margin-top:28px">${WHO[e.source]}</div>
<p style="margin-top:28px"><a href="/help">See all error codes</a></p>`;
  return new Response(page(`Error ${code}: ${e.title}`, body), { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=600" } });
}
