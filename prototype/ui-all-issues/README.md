# PROTOTYPE: seedkeeper with every open UI issue implemented

Throwaway. Not part of the app, never served by it. It answers one question: what does seedkeeper look like once
#84 #87 #90 #102 #103 #108–#120 are in (plus the closed #79 #83 #85 #86 #88 #89)?

Run it: `open prototype/ui-all-issues/index.html` (one static file, no build, invented data, no real mutations).

- Pages: `#overview` (new landing page), `#groups`, `#overrides`, `#activity`, `#orphans`, `#settings`, `#system`, `#login?loggedOut=1`.
- Groups has three structurally different variants: `#groups?variant=A` (refined table), `B` (triage lanes), `C` (list + detail).
  Pick one with the A/B/C buttons in the floating bar, or cycle with ← →, from any page. The choice is remembered.
- Purple `#nnn` markers show which issue each change comes from. Toggle them with `i` or the `#` button.
- Try `#groups?verdict=leave` (#113), `Keep…` → Set (#88), Overrides Save/Delete (#87), and hovering or tabbing to a seeding % (#120).

Once a Groups variant wins, record the verdict on the issues and fold it into the real templates. This branch stays as the source.

## Verdict (2026-11-20)

**Groups: variant A (refined table) won.** Joran liked its look best. B (triage lanes) and C (list + detail) were rejected.
Recorded on #111 (structure) with pointers on #110, #112, #113, #118 and #120. The other views had one design each and
need no decision.
