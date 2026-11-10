# Running a relay for old phones

A Java ME phone cannot talk to a muxway hub directly: MIDP 1.0 has no HTTPS,
and later phones trust no current certificate authority. A relay accepts
plain HTTP from the phone and forwards it to the hub over HTTPS.

## What a relay must do

- Forward `GET /v1/hosts`, `GET /v1/panes`, `GET /v1/panes/{address}`
  and `POST /v1/panes/{address}/input`, and for the MIDP 2.0 build `GET`
  and `POST /v1/panes/{address}/files`, `GET /v1/inbox`, `POST
  /v1/panes/{address}/answer`, `POST /v1/panes/{address}/later`, `GET
  /v1/talk`, `GET` and `POST /v1/panes/{address}/mail`, `POST
  /v1/panes/{address}/close`, `POST /v1/hosts/{host}/sessions`, `POST
  /v1/windows/{host}/{session}/{window}/panes` and `GET /v1/desk`
  (never `PUT`), to the hub, with
  their query string and the headers `X-Muxway-Token`, `Accept`,
  `Content-Type` and the body, and back the status, the body,
  `Content-Type`, `ETag` and `Muxway-Pane` (the id of the pane a read
  answered for; a relay that drops it costs the phone the check that
  its rows are its own pane's). An input asked with `?read=1` is
  answered `200` with the pane's screen as a text read gives it, `ETag`
  and `Muxway-Pane` with it: pass the body and both headers back as on
  a read, so the phone draws the key's echo at once instead of reading
  the pane again. These are the only paths the app
  requests (see [the protocol features
  used](../ARCHITECTURE.md#protocol-features-used)), and the example
  below names each of them and nothing wider. Nothing else: a relay
  that forwards any path is an open proxy.
- Answer only phones you know. The simplest way: a list of the tokens the
  relay will forward; anything else gets `401` from the relay itself.
- Cap request bodies (16 KiB is the hub's own limit) and answers. The
  `files` path carries an [image](images.md), up to 8 MiB, the hub's
  limit for it; cap that path alone there, and pass its `Content-Type`
  (`multipart/form-data` with its boundary) through untouched.
- Never log the token. Log its id instead, the first 8 hex digits of its
  SHA-256, which is what the hub logs too.

## Two rules for WAP 1.x phones

If the phone reaches the relay through a WAP gateway (the usual way for a
phone of the MIDP 1.0 era, which sends Java's HTTP through the gateway of
its active WAP settings):

1. **Answer in `text/plain`.** A WAP gateway turns HTTP answers into WSP and
   may not support `application/json`; it then drops the answer, and the
   phone waits until the app gives up. The hub answers errors in JSON, so
   the relay relabels them.
2. **Never answer without a body.** An answer with no body (`204` after a
   key, `304` for an unchanged pane) may never complete on the phone. Turn
   `204` into `200` with a short body such as `ok`, and do not forward
   `If-None-Match`, so the hub never answers `304`. The app accepts `200`
   for input. Give the stand-in no `ETag`: the hub's screen on an input
   always carries one, and that is how the phone tells the screen, which
   it draws, from an `ok`, which it never draws (it reads the pane
   instead, as it always did).

The app sends its token in `X-Muxway-Token` because WAP stacks rewrite
`Authorization: Bearer` into `Basic`; a relay passes that header on as it is.

## Security considerations

Between the phone and the relay everything travels unencrypted, the token
included: over the mobile network and, with a WAP gateway, through it (the
phone, relay, hub diagram in [the architecture page](../ARCHITECTURE.md#where-it-sits)
shows the two legs). Use a token per phone, granted only the panes it needs,
read-only unless typing is the point, and short-lived. Revoke it when the
phone is lost. The app says this once when such a server is saved in
Settings.

## Example: a reverse proxy

A relay can be a few lines of configuration in a proxy that terminates
nothing and forwards to the hub over HTTPS. For Caddy, with the two WAP
rules left to the phone side when no WAP gateway is involved, and the
paths above and no other, as one expression (the suite's S19 checks the
app against the same list; a relay for the MIDP 1.0 build alone needs
`hosts`, `panes`, a pane and `input`):

```caddyfile
:8080 {
	@client path_regexp ^/v1/(hosts|panes|inbox|talk|desk)$|^/v1/panes/[^/]+/[^/]+/[^/]+(/(input|answer|files|later|mail|close))?$|^/v1/hosts/[^/]+/sessions$|^/v1/windows/[^/]+/[^/]+/[^/]+/panes$
	request_body @client {
		max_size 8MB
	}
	handle @client {
		reverse_proxy https://hub.example.com {
			header_up Host hub.example.com
			header_up -Authorization
		}
	}
	respond 404
}
```

Behind a WAP gateway the two rules above need logic a static proxy
configuration cannot express; a small program does it.
