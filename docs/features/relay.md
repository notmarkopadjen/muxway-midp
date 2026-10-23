# Running a relay for old phones

A Java ME phone cannot talk to a muxway hub directly: MIDP 1.0 has no HTTPS,
and later phones trust no current certificate authority. A relay accepts
plain HTTP from the phone and forwards it to the hub over HTTPS.

## What a relay must do

- Forward `GET /v1/hosts`, `GET /v1/panes`, `GET /v1/hosts/{host}/tree`,
  `GET /v1/panes/{address}` and `POST /v1/panes/{address}/input` to the
  hub, with the headers `X-Muxway-Token`, `Accept`, `Content-Type` and the
  body. Nothing else: a relay that forwards any path is an open proxy.
- Answer only phones you know. The simplest way: a list of the tokens the
  relay will forward; anything else gets `401` from the relay itself.
- Cap request bodies (16 KiB is the hub's own limit) and answers.
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
   for input.

The app sends its token in `X-Muxway-Token` because WAP stacks rewrite
`Authorization: Bearer` into `Basic`; a relay passes that header on as it is.

## Risks to accept

Between the phone and the relay everything travels unencrypted, the token
included: over the mobile network and, with a WAP gateway, through it. Use
a token per phone, granted only the panes it needs, read-only unless typing
is the point, and short-lived. Revoke it when the phone is lost.

## Example: a reverse proxy

A relay can be a few lines of configuration in a proxy that terminates
nothing and forwards to the hub over HTTPS. For Caddy, with the two WAP
rules left to the phone side when no WAP gateway is involved:

```caddyfile
:8080 {
	@client path /v1/hosts /v1/panes /v1/hosts/*/tree /v1/panes/*
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
