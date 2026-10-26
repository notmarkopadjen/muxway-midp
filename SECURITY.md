# Security policy

muxway gives remote access to terminal sessions. A flaw in it can hand an
attacker a shell, so security reports come before everything else.

## Reporting a vulnerability

Do not open a public issue, and do not describe the flaw in a pull
request. Report it through GitHub's private vulnerability reporting:
the repository's **Security** tab, *Report a vulnerability*. Only the
maintainers see it, the thread stays private until the fix is out, and
a CVE can be issued from it. There is no e-mail address for reports.

Say what you found, how to reproduce it (a request, a pane name, a
screen), what it gives an attacker, and which commit or release you
tested. A proof of concept against your own hub and host is welcome;
never against someone else's.

## What to expect

| Step | Within |
|---|---|
| Acknowledgement | 3 days |
| First assessment and severity | 7 days |
| Fix or mitigation for a high or critical finding | 30 days |
| Public disclosure | after the fix is released, or 90 days after the report, whichever comes first, agreed with you |

This is a volunteer project; the days are targets, not a contract. Credit
goes in the release notes and the advisory if you want it; say so in the
report.

## Supported versions

| Version | Supported |
|---|---|
| `main` | yes |
| the latest release | yes |
| anything older | no: upgrade first |

## Scope

The MIDlet and the relay in this repository. Reports are most welcome on:

- the relay: what it exposes, how it validates what the phone sends, and
  what a hostile hub answer can do to it;
- the token: where the phone keeps it, the JAD install path, when it is
  shown;
- plain HTTP between the phone and the relay (by design on a phone that
  cannot do current TLS; see `docs/features/relay.md`) and anything that
  weakens it further;
- what a hostile pane's content can do to the app (crashes, spoofed names);
- the keypad input path into a pane.

The hub, the agent and the protocol are in
[muxway](https://github.com/notmarkopadjen/muxway); report flaws there to
that repository.

Out of scope: the phone's own Java ME implementation, denial of service
against your own relay or hub, and social engineering.

## Safe harbour

Research done in good faith against your own installation, within this
policy, is authorised as far as the maintainers can authorise it: they
will not report it to anyone or take legal action over it. Testing
against an installation you do not own is not covered; nor can the
maintainers speak for third parties such as a hosting provider.
