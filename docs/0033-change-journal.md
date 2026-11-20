# 0033: Change journal and batch revert

## Context

Compensation (ADR-0009) undoes a batch that fails midway, and backups
(ADR-0031) restore a whole file. Neither answers "what did the agent
change today?" or "undo just that batch" after it succeeded, possibly
with more batches on top. The undo steps exist at apply time as
closures over live VP objects, and were discarded on success.

## Decision

- `BatchApplier.apply(..., undoSink)` hands a fully successful batch's
  compensations (apply order) to the caller. Failed batches hand none:
  they were already compensated.
- `journal.ChangeJournal` records every batch with `mutated: true`:
  - in memory, with its undo steps and the live `IProject` it ran on;
  - on disk as one JSON line per event in `<journal_dir>/<project>.jsonl`
    (`batch` or `revert`; ops, applied entries, status). A failed write
    adds `journal_warning` to the result; it never fails the apply.
- `vp_apply_batch` returns `batch_id`.
- `vp_list_changes` merges the file with the session, newest first, and
  marks each entry `revertable`.
- `vp_revert_batch` dry-runs without `confirm` (`allowed`, `reason`,
  `conflicts`, `undo_steps`, `irreversible`). With `confirm: true` it
  backs up like apply, runs the undo steps newest first, reports each
  step, and journals a `revert` record. Refused (nothing runs) when:
  - a later standing batch shares a touched id (op reference fields
    `diagram/element/from/to/model/parent/container/member/from_member/
    to_member`, plus produced `vp_id`/`view_id`): revert newest first;
  - the project object differs (reopened: closures point at dead objects);
  - the batch is from an earlier VP session, failed, already reverted, or
    holds only deletions.
- Deletions stay final; a revert of a mixed batch lists them as
  `irreversible`.
- `mcp.properties`: `journal` (default true; false = memory only) and
  `journal_dir` (default `<plugin dir>/journal`).

## Consequences

- Undo is session-scoped by construction. Across restarts, history
  remains readable and backups are the restore path.
- Hand edits in VP are invisible to the journal: a revert may overwrite
  them (restored names, styles, positions) or fail a step, reported per
  step as `partially_reverted`.
- Conflict detection is id-overlap only; it is conservative for shared
  references (two batches connecting to the same actor conflict).
- Unit-tested with fake steps on a temp dir (`ChangeJournalTest`).
- Live run 2026-11-20 (VP 18.1, Teamwork project, scratch diagram):
  create use case (batch A) → move it (batch B) → dry-run revert A was
  refused with B as the conflict → revert B restored bounds exactly →
  revert A then allowed, removed view and model (`vp_get_model` no
  longer finds it) → `vp_list_changes` and the JSONL both held the four
  events. Each batch made its own backup (Teamwork rewrites the file);
  reverts reused the latest. Reopened-project refusal is unit-tested
  only.
- `batch_id` stamps are UTC; backup file names use local time.
