// Registers the bot's slash commands with Discord. Run by the deploy workflow:
//   DISCORD_APP_ID=... DISCORD_BOT_TOKEN=... node scripts/register-commands.mjs
import { COMMANDS } from "../src/discord.js";

const { DISCORD_APP_ID: app, DISCORD_BOT_TOKEN: token } = process.env;
if (!app || !token) {
  console.log("Discord secrets not set, skipping command registration");
  process.exit(0);
}
const res = await fetch(`https://discord.com/api/v10/applications/${app}/commands`, {
  method: "PUT",
  headers: { authorization: `Bot ${token}`, "content-type": "application/json" },
  body: JSON.stringify(COMMANDS),
});
const text = await res.text();
if (!res.ok) {
  console.error(`Registering commands failed: ${res.status} ${text.slice(0, 500)}`);
  process.exit(1);
}
console.log(`Registered ${JSON.parse(text).length} commands`);
