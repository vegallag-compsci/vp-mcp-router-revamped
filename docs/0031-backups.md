# 0031: Backup before writes

## Context

VP has no transactions (ADR-0009) and deletions are final. Compensation
covers a failed batch, not a successful batch the user regrets.

## Decision

- `vp_apply_batch` copies the project's `.vpp` before a write whenever the
  file changed since the last copy (mtime moved): the first apply in a
  session, and again after every save. The copy is the file as VP last
  wrote it.
- Only valid batches trigger a copy. A needed copy that fails refuses the
  apply (structured error; nothing changed).
- `mcp.properties`: `backup` (default true), `backup_dir` (default
  `<plugin dir>/backups`), `backup_keep` (default 20, oldest pruned).
  Copies are `<dir>/<project>/<project>-yyyyMMdd-HHmmss.vpp`.
- Apply results carry `backup {enabled, created, file, note?}`.
- `vp.BackupManager` takes a clock and paths, unit-tested on temp dirs.

## Consequences

- Live run 2026-11-20 (VP 18.1, Teamwork project): VP writes the
  Teamwork working copy (`ws/teamwork_client/projects/...`) on its own,
  seconds after edits, with no save. So for such projects there is one
  copy per batch, each holding the state just before that batch
  (including earlier unsaved agent edits). `backup_keep` bounds the
  history. VP keeps the file locked; the in-process copy still succeeds.

- Restore = close VP, open (or copy back) the backup file.
- Never-saved projects get a note, not a refusal (nothing to copy).
