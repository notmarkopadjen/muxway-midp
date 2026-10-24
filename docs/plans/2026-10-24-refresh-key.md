# Feature: a key for Refresh

> Status: implemented 2026-10-24.

## 1. Goal

Refresh lives in the pane's menu, two presses away. The owner wants a
key for it. `1` refreshes the pane; the menu entry stays.

## 2. Design

`PaneView.keyPressed` maps `KEY_NUM1` to a new shortcut, `REFRESH`,
which the app runs as the menu's Refresh does (`start(REFRESH)`); while
a request is out the app says so, as for any key. The overlay's hint
becomes `1 2/8 5 0 # *` and the Settings help names the key.

## 3. Protocol and API changes

None.

## 4. Security analysis

The key sends nothing to the pane: it repeats the read the pane was
opened with, under the same token. Nothing new is logged.

## 5. Privacy check

No fixture changes.

## 6. Test plan

| # | Test | Kind | Expected |
|---|------|------|----------|
| 1 | F15 extended: `1` makes the app read the pane again | e2e-ui | one more pane read reaches the relay |

## 7. Documentation

`docs/features/pane-view.md` keypad table.

## 8. Implementation steps

One commit.

---

## Review (after implementation)

- [x] Diff matches the plan
- [x] Security rules hold: a read, nothing typed
- [x] Privacy check passed: `scripts/prepush-check.sh` clean and diff read
- [x] Docs updated

## Test run

To be recorded.
