# The pane view

## The list

Panes are listed by what runs in them, not by number: the pane's title,
which a program sets for itself (Claude Code calls its terminal `✳ <session
name>`), and the program in brackets, `* muxway android (claude)`. A
pane its owner named (the alias, `muxway expose --name review` on the
host or a rename on the web or Android; the core's
[pane names](https://github.com/notmarkopadjen/muxway/blob/main/docs/plans/2026-11-03-pane-names.md))
goes by that name instead, on both builds: `* review (claude)`, the
title stepping back. `*`
marks a pane the token may write to, taken from the hub's `mode` column
and never from the title; the character after it says what the pane
waits for, from the hub's `kind` column: `?` a question, `!` a
permission, `z` idle, `v` done, a space for nothing (`*?build (claude)`),
and `zz` with a space after it for a pane its owner parked for
[later](later.md) (`*zz build (claude)`);
a mark leading the title (`✳`, or `*` once folded) is dropped, it is
decoration. A title that says nothing, because it is empty,
the program's name, or the host's name (tmux's default until a program
sets one, and a host is usually named after its machine) gives way to the
pane's place: `work/0.1 (bash)`. When the list holds panes of several
hosts each entry starts with its host. Entries are cut at 64 characters;
the phone scrolls long ones. On the MIDP 1.0 build the list is asked for
in Latin-1 like the content (see [lenses](lenses.md)), so `✳` arrives as
`*`.

On the MIDP 2.0 build two things follow the name, from the listing's
last columns, where the row has the room the smallest screen has not:
the **folder** the pane works in, its last segment after a slash
(`cwd`, which the hub gives a writer of the pane under one of the host's
preview roots, and nobody else), so two Claude panes on the same subject
in two checkouts read apart, `* fixing the build /muxway (claude)` and
`* fixing the build /muxway-midp (claude)`; and, for a named pane, the
**status**, the title the alias replaced, after a middle dot (a dash on
the ASCII setting): `* review /muxway · fixing the build (claude)`. A
title that says nothing adds no status. The folder is shown as text
and never asked for: the phone has no directory listing.

The list also shows who talks with whom and who
has mail, from two more columns of the hub's text listing (`mail`, the
unread count, and `talk`, the partners' addresses). Panes that wrote or
mailed to each other in the last ten minutes, partners of partners
included, form a group, as on the web (the core's
[talk](https://github.com/notmarkopadjen/muxway/blob/main/docs/features/talk.md)):
the group's rows are kept together, pulled up behind the group's first
row, and framed by an edge at the row's left, `[` on the first row, `|`
between, `]` on the last, a space on every other row; the column is
there only while the list holds a group. A pane whose process has unread
[mail](https://github.com/notmarkopadjen/muxway/blob/main/docs/features/mail.md)
shows the count after its name, `✉ 2`, or `M 2` when the Text setting is
Latin-1 or ASCII; the name gives way so the count stays within the cut.
Nothing pops up for a message. A partner the list does not hold, a count
that is not one to three digits, and the column of a hub that does not
write them yet, change nothing.

```
[*?build ✉ 2 (claude)
]* review (claude)
 * work/0.1 (bash)
```

```mermaid
flowchart LR
    R["address TAB mode TAB command TAB title TAB kind TAB mail TAB talk TAB alias TAB cwd"] --> L{alias?}
    L -- yes --> N1["alias"]
    L -- no --> T{title empty,<br/>= command<br/>or = host?}
    T -- no --> N2["title"]
    T -- yes --> N3["session/window.pane"]
    N1 --> F["+ /folder (MIDP 2.0, cwd given)"]
    N2 --> F
    N3 --> F
    F --> S["+ · title (MIDP 2.0, a named pane whose title says something)"]
    S --> C["+ (command)"]
    C --> M["mark from mode: '*' or ' ', then from kind: '? ! z v', 'zz ' for later, or ' '"]
```

### The states

Every client paints a pane with the same three states of the lens (the
core's [guideline](https://github.com/notmarkopadjen/muxway/blob/main/docs/ui-guideline.md),
§1.2): **waiting** inverted and pulsing, **working** plain with a
turning ring and the word (the owner dropped the dim paint on
2026-11-02), **quiet** plain. The keypad has no paint of its own: the
list is the phone's own `List`, its labels in the phone's type and
colours, and the selection is the phone's highlight, so selection is
never a colour here by construction. What the keypad shows of each
state:

| State | List row | Pane view |
|---|---|---|
| waiting | the kind's character after the write mark, `?` `!` `z` `v`; the [inbox](inbox.md) says what for | drawn as it is; `9` opens the inbox |
| working | plain: no glyph, no word. The hub's `working` event clears the pane's attention and the listing's `kind` column is empty, and the screen moves | plain |
| quiet | plain | plain |
| [later](later.md) | `zz` where the kind's character goes, the hub's order kept | plain; the overlay reads `> zz name`, the name in italics (MIDP 2.0); *Later* and *Wake* on the menu (MIDP 2.0) |

A working row therefore looks like a quiet one; the pane's own screen
tells them apart. A mark for working would need the listing to carry
the state, which it does not, and a word would cost six of sixteen
columns: the owner left it out (plan of 2026-11-03).

```mermaid
stateDiagram-v2
    [*] --> quiet
    quiet --> waiting: kind in the listing
    waiting --> quiet: working event, an answer, or the pane read
    note right of quiet: row plain, working looks like quiet
    note right of waiting: the kind's character after the mark
```

## The screen

<img src="../images/photo-pane.jpg" alt="A Nokia 6310i showing a pane's text wrapped over the whole screen" width="226">
<img src="../images/photo-keys.jpg" alt="The same phone on the Keys menu: Enter, Ctrl-C, Escape" width="226">
<img src="../images/photo2-overlay.jpg" alt="A Nokia 7310 Supernova showing a Claude Code pane with the overlay: the pane's name on top, rows 151-162 of 162 and the key hints at the bottom" width="226">
<img src="../images/photo2-keys.jpg" alt="The same phone on the Keys menu, seven keys visible at once" width="226">

The pane fills the whole screen. There is no title bar and no scroll
marker: the pane's rows are drawn in the phone's small font from the left
edge, bottom-aligned, so the last row is always a full one and the spare
pixels sit at the top. On a 96x65 screen that is five rows of some twenty
characters.

## Keypad

| Key | Does |
|---|---|
| `1` | refresh the pane |
| `2` / `8`, or the joystick | scroll one row up / down; hold to repeat |
| `3` | Image (writable panes, MIDP 2.0 build): see [images](images.md) |
| `5` | Reply (writable panes) |
| `9` | Inbox (MIDP 2.0 build): see [the inbox](inbox.md) |
| `0` | Keys (writable panes) |
| `#` | show or hide the title and scroll position |
| `*` | show or hide what a [lens](lenses.md) hid (footer, status) |
| green call key | send Enter (writable panes) |

The same list is printed at the bottom of Settings. The left soft key menu
still offers Reply, Keys, Image (MIDP 2.0), Refresh, Auto, Pane, Later or
Wake (writable panes, MIDP 2.0, see [later](later.md)), Back and Settings.

## Pane

`Pane` in the menu opens the pane's own view, kept per pane address, as
a list: **Auto** (every [lens](lenses.md) may recognise the program),
**Plain** (the rows as they are) and one entry per lens, today **Claude
Code**, which uses that lens whether or not it is sure of the screen.
Selecting one saves it and redraws. The app remembers the last 32 panes
that were given a view; the others are Auto.

```mermaid
stateDiagram-v2
    [*] --> Pane
    Pane --> Pane: 2 / 8 scroll the body, pinned rows stay
    Pane --> Overlay: #
    Overlay --> Pane: #
    Pane --> Revealed: * (lens shows footer and status)
    Revealed --> Pane: *
    Pane --> Reply: 5 (writable)
    Pane --> Keys: 0 (writable)
    Pane --> Image: 3 (writable, MIDP 2.0)
```

## Overlay

`#` draws two inverted rows over the pane: the top one with `>` for a
writable pane, `zz` for a pane parked for [later](later.md), and the
pane's name as the list showed it (in the phone's italic face while the
pane is parked, on the MIDP 2.0 build), the bottom one with the rows on screen
out of the rows the app holds (`7-12/40`) and, when it fits next to that,
the key hints. `#` again removes them. While a request is out a small wait
bar runs in the top right corner, overlay or not.

## Wrapping

The app asks the hub for the pane's rows as they are on the terminal
(`width=400`, the protocol's maximum) and wraps them itself. Rows longer
than the screen are cut into pieces; nothing scrolls sideways. The `Width`
setting still means what it did: any value other than `0` wraps at that
many characters.

With `Width` at `0` the app wraps by measured pixels, character by
character. Nokia phones have no monospace font: asking for one gives the
proportional system font, whose letters are mostly narrower than `M`, so
counting columns by `M` left three or four characters of room at the end
of every row. The app measures each character once and cuts a row where
the next one would not fit. The lens sees the rows before wrapping either
way.

Wrapping on the phone is what lets a lens see a whole row: a rule of eighty
`─`, or a status line pushed to the right edge, is not recognisable once
the hub has cut it into pieces of sixteen.

```mermaid
flowchart TD
    H[Hub: last N rows, unwrapped] --> S[setText]
    S --> L{a lens recognises the rows?}
    L -- no --> B[body rows]
    L -- yes --> C[lens.shape]
    C --> B
    C --> P[pinned rows]
    B --> W[wrap: at Width characters, or by measured pixels]
    P --> W
    W --> D[paint: pinned rows at the bottom, body above, bottom-aligned]
```

## Security

The view changes how rows are drawn and adds keypad routes to screens that
exist already. `5`, `0` and `3` do nothing on a pane the token cannot
write; the hub refuses a write anyway if a relay lied about that. No key sends
anything but Enter. Wrapping allocates one string per wrapped row, at most
32 KiB / 8 columns of them (by pixels, one character per row at the
worst), within the smallest target's heap. The pane records in the
settings store hold only addresses the hub listed, at most 32, and a line
that does not parse is dropped on read. The pane's content is traced only
in test builds, as before.

A program in a pane chooses its own title, and a writer of the pane
its alias, so the list shows text an attacker on that host controls.
The hub refuses titles with control characters or over 256 bytes and
keeps an alias to `[a-z0-9-]{1,32}`, so neither can add a row or a
column to the list; the app splits on TAB and takes the fourth and
eighth fields, caps the entry, strips control characters from every
body, and takes the writable mark from the hub. A title or an alias made
to look like another pane (`work/0.0 (claude)` on a shell, `bob/work/0.0`
as a name) misleads the eye only: the program in brackets is the hub's,
the pane a row opens is its first column's, and the hub decides what
the token may write on every input. The owner exposed that pane; a
program there already holds a shell. The folder is a path on the host:
the hub gives it to a writer of the pane under a preview root the owner
named and to nobody else, the phone shows its last segment as text and
never asks for the directory. The end-to-end suite has these cases
(S09, S22, S23).
