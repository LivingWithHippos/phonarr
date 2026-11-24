# Phonarr

A partial android reimplementation of [Prowlarr](https://github.com/prowlarr/prowlarr).

## Using it

1. Open the app. On first launch download the definitions (Server tab → *Update definitions*).
2. Indexers tab → *Add indexer*, pick a site, *Save and test*.
3. Server tab → *Start*. A notification shows while the server runs.
4. In your client add a Torznab indexer:
   - URL: `http://127.0.0.1:9797`
   - API key: shown on the Server tab

Jackett-style (`/api/v2.0/indexers/{id}/results/torznab/api`) and Prowlarr-style
(`/api/v1/indexer/{id}/newznab`) URLs work too.

Apps that talk to Prowlarr's own API can use `http://127.0.0.1:9797` as a Prowlarr server, with the
same API key (`X-Api-Key` header or `apikey` parameter). Supported endpoints:

- `GET /api/v1/system/status`
- `GET /api/v1/search?query=...&type=search` (also `indexerIds`, `categories`, `limit`, `offset`;
  Prowlarr's `{Season:1}`-style tokens work in `query` for tvsearch, movie, music and book searches)

## How it is built

| Area | Code |
| --- | --- |
| Cardigann engine (port of Prowlarr's C#) | `core/cardigann/` |
| Torznab caps, categories, XML | `core/torznab/` |
| Definitions download, saved indexers, search | `core/data/` |
| HTTP server (Ktor CIO) | `core/server/TorznabServer.kt` |
| Foreground service | `service/ServerService.kt` |
| Compose UI | `ui/` |

Definitions come from `https://indexers.prowlarr.com/master/12/package.zip`, the same source
and schema version Prowlarr uses.

## Not supported yet

- Sites whose login page has a captcha: about 40 out of 590.

Form, cookie, post, get and one-url logins work.

## Cloudflare and DDoS-Guard

When a site answers with an anti-bot page, Phonarr (`browser/`):

1. loads the site in a hidden WebView using the same user agent as its HTTP client, and reuses the cookies it gets;
2. if the site still refuses the HTTP client (some sites check the TLS fingerprint), loads its pages through the WebView from then on;
3. if the hidden WebView can't pass the check (e.g. an interactive Turnstile), shows a notification and a
   "Pass site check" button that open the site in a visible browser; once you pass it, the cookies are reused.

Torrent files can't be read back from a WebView, so on sites that need step 2, `.torrent` downloads may still fail (magnets work).
