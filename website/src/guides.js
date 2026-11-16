// Plain-English guide pages: /guides and /guides/<slug>.
// These are here to help people (and so Google can find AxiomTV). Keep them about the player, never about content.
import { layout, html } from "./layout.js";

export const GUIDES = {
  "install-on-fire-stick-and-android-tv": {
    title: "How to install an IPTV player on a Fire Stick or Android TV device",
    short: "Install AxiomTV on a Fire TV Stick, Android TV or Google TV device in about five minutes.",
    description: "Step-by-step: install the AxiomTV IPTV player on an Amazon Fire TV Stick, Android TV device or Google TV using the free Downloader app. Takes about five minutes.",
    updated: "16 November 2026",
    body: `
<p>AxiomTV isn't in the Amazon or Google app stores yet, so you install it with a free app called <b>Downloader</b>. It's safe, it takes about five minutes, and you only do it once: AxiomTV updates itself after that.</p>

<h2>On an Amazon Fire TV Stick or Fire TV Cube</h2>
<ol>
<li>On the Fire TV home screen, open <b>Find</b>, search for <b>Downloader</b> and install it (the orange icon by AFTVnews).</li>
<li>Go to <b>Settings › My Fire TV</b>. If you can't see <b>Developer options</b>, open <b>About</b>, highlight your device name and press the select button seven times until it says you're a developer.</li>
<li>Open <b>Developer options › Install unknown apps</b> and turn it on for <b>Downloader</b>.</li>
<li>Open Downloader, select the address bar and type <b>axiomtv.co.uk/get</b>, then press Go.</li>
<li>When the download finishes, choose <b>Install</b>, then <b>Open</b>. You can delete the downloaded file afterwards to save space.</li>
</ol>
<div class="callout">Some of the newest Fire TV models run Amazon's new Vega OS instead of Android, and they can't install Android apps at all. If Downloader isn't in the store on your device, that's probably why. An Android TV or Google TV device will work instead.</div>

<h2>On an Android TV or Google TV device</h2>
<ol>
<li>Open the <b>Google Play Store</b> on the device and install <b>Downloader</b> by AFTVnews.</li>
<li>On Google TV, go to <b>Settings › System › About</b> and press the select button seven times on <b>Android TV OS build</b> to turn on developer mode. Older Android TV devices don't need this step.</li>
<li>Go to <b>Settings › Apps › Security &amp; restrictions › Unknown sources</b> (on some devices it's under <b>Device preferences › Security</b>) and allow <b>Downloader</b>.</li>
<li>Open Downloader, type <b>axiomtv.co.uk/get</b> and press Go, then <b>Install</b>.</li>
</ol>

<h2>On an Android phone or tablet</h2>
<p>Open <b>axiomtv.co.uk/get</b> in Chrome. When the download finishes, tap it and allow Chrome to install apps when asked.</p>

<h2>If the download won't start</h2>
<ul>
<li>Try <b>axiomtv.co.uk/backup</b> instead. It's the same file, sent a different way, for devices that can't reach GitHub.</li>
<li>Check the device is online: open any other app that uses the internet.</li>
<li>Low on space? Uninstall apps you don't use, then try again.</li>
</ul>

<h2>Next step</h2>
<p>Open AxiomTV and sign in with the details from your TV provider. Our <a href="/guides/set-up-xtream-login">Xtream login guide</a> shows exactly what goes where. You get 3 days free to try everything before you pay.</p>`,
  },

  "set-up-xtream-login": {
    title: "How to set up an Xtream Codes login on Android TV",
    short: "What the server address, username and password mean, and how to get them right first time.",
    description: "How to enter an Xtream Codes login (server URL, username and password) in an IPTV player on Android TV or Fire Stick, with fixes for the most common sign-in mistakes.",
    updated: "16 November 2026",
    body: `
<p>Most TV providers give you an <b>Xtream Codes</b> login (sometimes called an Xtream API login). It's three things: a server address, a username and a password. AxiomTV uses them to load your provider's channels, guide, films and series. AxiomTV doesn't come with any channels of its own.</p>

<h2>What you need from your provider</h2>
<ul>
<li><b>Server address</b>, also called the URL, host or portal. It usually looks like <code>http://example.com:8080</code>: the start, a web address, and often a number after a colon (the port).</li>
<li><b>Username</b></li>
<li><b>Password</b></li>
</ul>
<p>If your provider only gave you a long M3U link, the three details are usually hidden inside it: look for <code>username=</code> and <code>password=</code>. The server address is everything before <code>/get.php</code>.</p>

<h2>Signing in to AxiomTV</h2>
<ol>
<li>Open AxiomTV. The first time, it goes straight to the sign-in screen.</li>
<li>Enter the server address exactly as your provider sent it, including <code>http://</code> or <code>https://</code> and the port number.</li>
<li>Enter your username and password. Both are case-sensitive, so <b>Abc</b> isn't the same as <b>abc</b>.</li>
<li>Sign in. AxiomTV loads your channels and guide. The first load can take up to a minute on big lists.</li>
</ol>

<h2>If it won't sign in</h2>
<ul>
<li><b>"Login not accepted"</b> (<a href="/help/2001">error 2001</a>): check the username and password letter by letter. Watch for a space at the end if you copied and pasted.</li>
<li><b>"Provider's server isn't answering"</b> (<a href="/help/2004">error 2004</a>): the server address is wrong or the server is down. Check you've included the port, and that it starts with the right <code>http</code> or <code>https</code>.</li>
<li><b>Don't add extra bits</b> to the address, like <code>/player_api.php</code> or <code>/c/</code>. AxiomTV adds what it needs.</li>
<li><b>"Your provider account has expired"</b> (<a href="/help/2002">error 2002</a>): your subscription with the provider has ended. Renew with them.</li>
</ul>

<h2>Changing to a new login later</h2>
<p>Go to <b>Settings</b>, sign out, and sign in with the new details. Your AxiomTV licence stays on the device, so you don't pay again.</p>`,
  },

  "why-iptv-buffers": {
    title: "Why IPTV channels buffer, and what to try",
    short: "Work out whether it's your Wi-Fi, your device or your provider, and fix it.",
    description: "IPTV buffering or freezing? Find out whether the cause is your Wi-Fi, your Android TV device or your provider's server, with simple fixes to try in order.",
    updated: "16 November 2026",
    body: `
<p>Buffering means the video is arriving slower than it plays. The cause is almost always one of three things: <b>your home connection</b>, <b>your device</b>, or <b>your provider's server</b>. These checks, in order, tell you which.</p>

<h2>Step one: is it one channel or all of them?</h2>
<ul>
<li><b>Only one channel</b> buffers and others are fine: it's that stream at your provider. Try the SD or HD version if there is one, and tell your provider which channel it is.</li>
<li><b>Everything</b> buffers, including films: keep going below.</li>
<li><b>Only at busy times</b>, like Saturday afternoons or big events, and fine otherwise: your provider's server is struggling with demand. Only they can fix that.</li>
</ul>

<h2>Step two: check your connection</h2>
<ul>
<li>Run a speed test on the device itself (the free Speedtest or Downloader apps both have one). HD needs about <b>10&nbsp;Mbps</b> steady, 4K about <b>25&nbsp;Mbps</b>.</li>
<li><b>Use a network cable</b> if you can. It's the single biggest fix for buffering. If you can't, use powerline adapters or a mesh Wi-Fi point near the TV.</li>
<li>On Wi-Fi, connect to your router's <b>5&nbsp;GHz</b> network if it has one, and keep the device out from behind the TV.</li>
<li>Restart the router and the device: unplug both for 30 seconds.</li>
<li>Someone downloading big files or gaming on the same connection? Try again when they've finished.</li>
</ul>

<h2>Step three: check the device</h2>
<ul>
<li>Restart it properly. On a Fire Stick, hold Select and Play together for five seconds.</li>
<li>Free up space: storage that's nearly full slows everything down.</li>
<li>Turn off any VPN and try again. Some VPN servers are too slow for live TV, and some providers block them (<a href="/help/2005">error 2005</a>).</li>
<li>Very old or very cheap devices can struggle with 4K and some HD formats. Try the SD or HD version of the channel.</li>
</ul>

<h2>What AxiomTV does for you</h2>
<p>If the connection drops for a moment, AxiomTV reconnects by itself, up to three times (<a href="/help/3002">error 3002</a>). If a stream takes too long to start, you'll see <a href="/help/1003">error 1003</a> with a Try again button. Every error tells you plainly whether it's your provider, your internet or the app, so you know who to contact. See <a href="/help">all error codes</a>.</p>`,
  },
};

export function guidesPage(path) {
  const slug = path.replace(/^\/guides\/?/, "");
  if (!slug) {
    const list = Object.entries(GUIDES).map(([s, g]) =>
      `<li><a href="/guides/${s}"><b>${html(g.title)}</b></a><br>${html(g.short)}</li>`).join("");
    return layout({
      title: "Guides – AxiomTV",
      description: "Plain-English guides for AxiomTV: installing an IPTV player on Fire Stick and Android TV, setting up an Xtream login, and fixing buffering.",
      path: "/guides",
      body: `<h1>Guides</h1><p class="updated">Short, plain-English help for getting set up and fixing common problems.</p><ul class="guide-list">${list}</ul>
<p style="margin-top:28px">Got an error code on screen? See <a href="/help">what each error means</a>.</p>`,
    });
  }
  const g = GUIDES[slug];
  if (!g) return null;
  const jsonld = {
    "@context": "https://schema.org", "@type": "Article", headline: g.title, description: g.description,
    dateModified: new Date(g.updated).toISOString().slice(0, 10), inLanguage: "en-GB",
    author: { "@type": "Organization", name: "AxiomTV", url: "https://axiomtv.co.uk/" },
    publisher: { "@type": "Organization", name: "AxiomTV", logo: { "@type": "ImageObject", url: "https://axiomtv.co.uk/icon.png" } },
    mainEntityOfPage: `https://axiomtv.co.uk/guides/${slug}`,
  };
  return layout({
    title: `${g.title} – AxiomTV`,
    description: g.description,
    path: `/guides/${slug}`,
    type: "article",
    jsonld,
    body: `<p class="updated"><a href="/guides">Guides</a> · Updated ${g.updated}</p><h1>${html(g.title)}</h1>${g.body}
<div class="callout" style="margin-top:32px"><b>New to AxiomTV?</b> It's a fast, good-looking player for Android TV, Fire TV and phones. Try it free for 3 days, then £7.99 once unlocks your device for life. <a href="/#install">Get AxiomTV</a></div>`,
  });
}
