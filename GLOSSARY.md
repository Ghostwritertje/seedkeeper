# seedkeeper

seedkeeper decides when torrents in Transmission may be removed, and removes them, without ever deleting data that
another torrent or the media library still uses.

## Language

### Groups

**Cross-seed group**:
The torrents that share data on disk: a wanted file of one is the same file as a wanted file of another, directly or
through a third torrent.
_Avoid_: Group (on its own), cluster

**Verdict group**:
The torrents that get one verdict together. It is a cross-seed group, except that an unregistered torrent splits off
from its cross-seed group into a verdict group of its own.
_Avoid_: Group (on its own)

### Torrents

**Wanted file**:
A file of a torrent that is selected for download in Transmission. Only wanted files tie torrents together, and only
wanted files are ever deleted.
_Avoid_: Selected file

**Not finished**:
A torrent whose download isn't complete. Its files may not exist yet, so it is never missing data.
_Avoid_: Unfinished, incomplete

**Unregistered**:
A torrent its tracker has deleted for good, as the tracker's message says. A problem with the account, such as a bad
passkey, never makes a torrent unregistered.
_Avoid_: Dead, deleted torrent, trumped

**Seed time**:
The time a torrent has actually spent seeding; it stands still while the torrent is paused.
_Avoid_: Seeding time, age, ratio

**Minimum seed time**:
The seed time a tracker, or the public minimum, requires before a torrent may be removed.
_Avoid_: Hit-and-run rule (except in prose about the tracker's own rules), seed requirement

### Trackers

**Tracker**:
A tracker site named in the config, recognised by the hosts of its torrents' announce URLs. A torrent belongs to a
tracker when every one of its announce hosts is that tracker's.
_Avoid_: Managed tracker, site, indexer

**Public**:
What a torrent is when it isn't private and belongs to no tracker, once the config has a public minimum. It is not a
tracker: it is judged by the public minimum seed time instead.
_Avoid_: Public tracker

**Managed**:
A torrent that belongs to a tracker, or that is public. seedkeeper judges only managed torrents.

**Unmanaged**:
A torrent that is not managed. seedkeeper never removes it, and its whole verdict group is left alone.
_Avoid_: Unknown, untracked

### Runs

**Remove**:
Take a torrent out of Transmission, leaving its data on disk. Also the verdict that a verdict group is due for it.
_Avoid_: Leave, drop, clean up

**Delete**:
Take a file or a symlink off the disk. Only the paths of removed torrents are ever deleted.
_Avoid_: Remove (for files), purge, clean up

**Removal**:
One verdict group being removed by a run, together with the deletions that follow. It is still a removal when it fails
partway.
_Avoid_: Attempted removal, cleanup

### Verdicts

**Verdict**:
What a run decides about a verdict group: remove, keep or left alone. The same torrents and files always get the same
verdict.
_Avoid_: Decision, status, action

**Keep**:
The verdict that a verdict group may not be removed yet: nothing needs a person to look at it, but a reason holds it
back.
_Avoid_: Hold, pending

**Left alone**:
The verdict that seedkeeper doesn't judge a verdict group, because someone should look at it.
_Avoid_: Skipped, ignored, keep

**Reason**:
The first rule that holds for a verdict group, in the order of precedence; it decides the verdict.
_Avoid_: Cause, why

**Secondary reason**:
Any other rule that holds for the verdict group too. It is shown but decides nothing.

**Forecast**:
When a verdict group that is kept may be removed: for a minimum not reached, when every member is expected to reach its
minimum seed time if it keeps seeding; for a keep override, its end date. A paused member stops the clock, and then
there is no date.
_Avoid_: ETA, estimate

**Run**:
One pass that carries out the verdicts: it removes every verdict group due for removal and deletes what they leave
behind. It ends successful or failed.
_Avoid_: Job, sweep, cleanup

### Keep overrides

**Keep override**:
A manual instruction never to remove the torrents that hold some data, with a note and an optional end date. It can
only prevent a removal, and never keeps an unregistered torrent.
_Avoid_: Pin, hold, exception, whitelist

**Match**:
A keep override matches a verdict group when it names a torrent of that verdict group's cross-seed group, so splitting
off an unregistered torrent doesn't loosen it.

**Expired**:
A keep override past its end date. It has no effect but stays until someone deletes it.

**Stale**:
A keep override that names no current torrent. It has no effect but stays until someone deletes it.
_Avoid_: Orphaned override

### Disk

**Library link**:
A hardlink of a downloaded file under a library root: the sign that the media library still uses the file. A symlink
is never one.
_Avoid_: Import, library copy

**Orphan**:
A file or symlink under the downloads root, outside every library root, that no torrent references. It is either an
orphan file or a dangling symlink, and seedkeeper never deletes it.
_Avoid_: Leftover, stray, junk

**Orphan file**:
An orphan that is a regular file. One with a library link is still an orphan, but deleting it would free nothing.

**Dangling symlink**:
An orphan that is a symlink leading nowhere and that is no torrent's own path.
_Avoid_: Broken link

### Code

**Module**:
A top-level subpackage of `io.github.ghostwritertje.seedkeeper`, such as `decide`, `snapshot` or `run`, together with
its subpackages. The root package itself holds only app wiring (config, required secrets) and is no module. Modules
never depend on each other in a cycle.
_Avoid_: Package (for a module), component, layer

**Module API**:
The types of a module that other modules may use: the ones marked `@ModuleApi`, plus the records and enums in their
public signatures, which need no mark. A module has at most 3 module API types that aren't records or enums, so the
budget counts behaviour, not the data a module returns.
_Avoid_: Public API, exported types, facade

### Agents

**Implementer pull request**:
A pull request the implementer agent opened. Only these may be handed back or merged without the maintainer.
_Avoid_: Agent pull request, bot pull request

**Round**:
One summary review by the reviewer agent on a pull request. The number of rounds caps the review/fix loop.
_Avoid_: Iteration, pass

**Handback**:
The reviewer agent returning an implementer pull request to the implementer agent to fix its findings.
_Avoid_: Bounce, retry

**Human-only path**:
A path that an implementer pull request may change only with the maintainer's merge, whatever the reviewer agent
says.
_Avoid_: Protected path, restricted file

**Trusted author**:
The maintainer, a user with write access, or one of the agents. Only what trusted authors write reaches an agent as
instructions; the repo is public, so anyone else's comment is only data.
_Avoid_: Collaborator, maintainer (for anyone but the maintainer)
