# AxiomTV licence server

A Cloudflare Worker with a D1 database. It unlocks AxiomTV per device.

## How it works

1. On launch the app calls `POST /v1/check` with its device ID. The first time, the server gives the device a code like `7K3P-9QXD`.
2. While the device is unpaid, the app shows a QR code for `/pay/<code>`.
   - Before the Lemon Squeezy checkout link is saved in `/admin`, that page shows the code and asks the buyer to message you.
   - Once the link is saved, the page sends the buyer to the checkout with their code attached.
3. Lemon Squeezy calls `/v1/webhooks/lemonsqueezy` when an order is paid. The server marks that code active. A refund revokes it.
4. The next `/v1/check` returns a signed licence that lasts 30 days. The app checks it offline with the public key from `/v1/pubkey` and refreshes it on every launch.

You can also unlock or revoke any code by hand at `/admin`, for example for Discord sales or beta testers.

## API

| Call | Body / auth | Reply |
|---|---|---|
| `POST /v1/check` | `{"device_id", "model", "app_version"}` | `{status, code, pay_url, price, payments_open, licence?}` |
| `GET /v1/pubkey` | – | `{alg: "Ed25519", key}` (key is base64url, raw 32 bytes) |
| `GET /pay/<code>` | – | Checkout redirect or info page |
| `POST /v1/webhooks/lemonsqueezy` | `X-Signature` HMAC | – |
| `GET /admin` | – | Admin page |
| `GET /v1/admin/devices?q=` | Bearer admin password | Device list and counts |
| `GET /v1/admin/events` | Bearer | Last 100 changes |
| `POST /v1/admin/grant` / `revoke` | Bearer, `{"code", "note"}` | – |
| `GET`/`POST /v1/admin/settings` | Bearer, `{"checkout_url", "price_text"}` | – |

**Licence format:** `base64url(payload JSON) "." base64url(Ed25519 signature of the first part)`.

The payload is `{v, app: "axiomtv", dh, c, s: "active", iat, exp}`. Here `dh` is the SHA-256 hex of `"axiomtv:" + device_id`, so the app can check that the licence belongs to that device.

## Deploying

This is done by `.github/workflows/licence-server.yml`. It runs on any change in this folder, or you can start it by hand.

1. Add the repository secrets `CLOUDFLARE_API_TOKEN` and `LICENCE_ADMIN_PASSWORD`.
2. When the Lemon Squeezy store is approved, add `LEMONSQUEEZY_WEBHOOK_SECRET`.

The first run creates the D1 database and generates the signing key. It then writes the worker URL and public key to `deployed.json`.

### Lemon Squeezy (once approved)

- Create a product and copy its checkout link into `/admin` → Payment settings.
- Under Settings → Webhooks, add `https://<worker>/v1/webhooks/lemonsqueezy` with the events `order_created` and `order_refunded`, and pick a signing secret.
- Save that signing secret as the `LEMONSQUEEZY_WEBHOOK_SECRET` GitHub secret, then re-run the workflow.

## Testing locally

```
node licence-server/test/local-test.mjs   # Node 22+, uses SQLite in memory in place of D1
```

Last deploy requested: 2026-11-15
