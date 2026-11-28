# PROTOTYPE: UI round 2 (throwaway branch, never merge)

Question: after #110–#120, what should the next round of UI issues be? Every view has variants on its own route,
switchable with `?variant=` and the floating bar (← → or the arrow keys); the header has its own variants (`?nav=` or
the Nav buttons, remembered in a cookie). Variant A is always today's view.

Run it: `.claude/skills/run-app/run-app.sh start` (Java 25), log in as `admin` / `ui-password`.

| View | Variant | Idea | Candidate issue |
|---|---|---|---|
| Header | `nav=B` sidebar | Views grouped (Day to day / Tools / Setup), a left-alone badge on Groups, live status (Transmission, next and last run) at the foot | Group the nav and show live status |
| Header | `nav=C` slim + More | 3 main tabs, Orphans/Settings/System under More, a "Next run in … · would remove N" pill | Same, lighter |
| Overview `/` | B next-run preview | One sentence ("the next run, in 17h, removes 4 groups holding 5.3 MiB"), named lists: will be removed, needs you (with hints), becoming removable | Overview leads with the next run and names the groups |
| Overview `/` | C timeline | Past runs → Now (needs you) → next run → forecast dates | Alternative to B |
| Groups | B compact rows | One line per group, no "Met ✓" noise, forecast folded into the seed cell, members on demand, keep form behind ⋯ | Groups: compact rows |
| Groups | C verdict lanes | Remove / Left alone / Keep lanes of cards | Alternative |
| Groups | D list + detail | Short list on the left; everything about one group (members, durations, keep form) in a panel; j/k | Groups: a detail panel |
| Overrides | B by name | Cards titled by the torrents kept; add by searching a name (working keep forms); hashes hidden | Overrides: add by name, hide hashes |
| Overrides | C by effect | In effect / ending within 14 days / no effect, with "Delete all" for expired + stale (stub) | Overrides: tidy expired and stale |
| Orphans | B flat, by size | Root shown once, relative paths, biggest first, kind chips, copy path / copy as `rm` | Orphans: relative paths, sort by size, copy |
| Activity | B ledger | One row per run, removals fold out, explanation in header tooltips, space-freed chart | Activity: a ledger |
| System | B health | System + Settings merged, a health checklist first | Merge System and Settings |

Findings with no variant:
- The overview's "Needs a look" box is red for routine left-alone groups; the "Due soon" card links "0 kept groups".
- System and Settings both show the next run and Transmission.
- Absolute paths (data dir, orphans, overrides file) dominate several views.

Code: `PrototypeVariant.java` (template globals `variant` and `nav`), `*-prototype-*.html` next to each view,
`tags/protoKeep.html`, `META-INF/resources/prototype/`. The variants read other views over `fetch` where the view's own
data doesn't have what they need. That's a prototype shortcut, not a design.

## Decision (2026-11-28)

Chosen: header `nav=B` (sidebar), Overview B (next-run preview), Groups C (lanes, ordered Keep, Left alone, Remove
from left to right), Overrides B, Orphans B, Activity B (the chart is the point), System B (merged with Settings).
Not chosen: header C, Overview C (timeline), Groups B and D, Overrides C.
