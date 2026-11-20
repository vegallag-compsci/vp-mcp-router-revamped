---
name: vp-router
description: Read and edit UML/ERD diagrams in the locally running Visual Paradigm project through the visual-paradigm MCP server (vp_* tools). Use before reading, reviewing, creating, connecting, moving, styling, deleting or saving anything in a Visual Paradigm diagram.
---

# Visual Paradigm modelling workflow

The `mcp__visual-paradigm__*` tool descriptions are the contract for op
shapes and fields; this file is the workflow. It mirrors
`opencode/skills/vp-router/SKILL.md` (upstream) with Claude Code specifics.

## Start of every session

1. Call `vp_capabilities`. It names the open project and the schema guard.
2. On ECONNREFUSED, "Open API is unavailable" or "No project is currently
   open": tell the user to open Visual Paradigm with the project. Do not
   retry in a loop, and never launch or kill VP yourself.
3. `schema_guard.match: false` is a warning, not a blocker: the pack still
   validates; reinstalling with `scripts\install-windows.ps1` regenerates it.
4. If another skill or `CLAUDE.local.md` defines modelling conventions for
   this project (a course, a team style guide), load it too. Its rules
   override generic UML advice and `vp_lint_diagram` defaults; say so when
   they differ.

## Reading

- **Start from what the user sees.** `vp_get_selection` when they say
  "this / these / the selected ..."; `vp_get_active_diagram` when they mean
  "this diagram". Only ask for a link when nothing is active. If
  `selected_count` is 0 and the request needs a selection, ask the user to
  select — don't guess.
- `vp_lint_diagram` (active diagram by default) for a review: findings
  carry view/model ids, so turn them straight into a previewed batch.
  Run it again after applying to show what is fixed.
- `vp_list_diagrams` for ids; `get_diagram_by_url` for a whole diagram
  (accepts a bare id); `vp_get_neighborhood` for a slice around one element
  (cheaper on big diagrams; depth 0 omits edges, use depth 1).
- `vp_list_models` / `vp_get_model` for elements that sit on no diagram.
- `vp_export_image` to see the diagram: prefer `svg` for captioned diagrams
  (PNG clips captions); `pdf` writes a file.
- `vp_open_diagram` brings a diagram to the front for the user.
- `vp_export_text` for text the user can keep in git or paste into a
  report: `plantuml`, `mermaid`, or `json` (the only one where moves show).
  Write into the user's repo with `out_dir`; `all: true` exports every
  diagram. Re-exporting an unchanged diagram gives identical files, so
  `git diff` shows exactly what a batch changed.

## Writing: preview → approve → apply → read back

1. Build the ops. Use **view ids** for `from`/`to`/`element`/`parent`, and
   **model ids** for `update_element`/`delete_model`/`show_element`'s `model`.
   Chain ops in one batch with `{"ref": "<op id>"}`.
2. `vp_preview_batch`. It never mutates (`mutated: false`).
3. Show the user the plan in plain words (what gets created, connected,
   changed or deleted) and wait for their approval of **this** batch.
   Deletions are final; name every delete target explicitly.
4. `vp_apply_batch` with `confirm: true` and the same ops. On partial failure
   it compensates in reverse; report `applied`, `compensated` and `errors`.
5. Read back what landed. `name_warning` on an applied entry means VP kept a
   different name; use the kept one. `tagged_value_warnings` and
   `container_note` work the same way.
6. Look at it. Data confirms the change landed; only a picture shows whether
   it looks right. `vp_export_image` as `png` (crop to the changed area with
   `crop` or `crop_to_element` on big diagrams) and check: lines hidden
   behind shapes or crossing labels, overlapping shapes or captions, use
   cases outside the System boundary, crowded or uneven spacing. Say what
   you see; propose a follow-up batch (or `vp_layout`) for problems. PNG may
   clip long captions at shape edges: confirm suspected clipping with `svg`.
7. Mention the `backup` in the result the first time it is created
   (path), so the user knows their restore point.
8. Keep the `batch_id` from the result. To undo a batch the user regrets,
   run `vp_revert_batch` without `confirm` first, tell the user what will be
   undone and what cannot be (`irreversible` deletions, `conflicts`), and
   confirm only after they approve. `vp_list_changes` answers "what did you
   change?".

Field notes:
- New use cases belong inside the System boundary: pass
  `container: "<boundary view id>"` on `create_element`, with x/y inside
  the boundary (absolute diagram coordinates). Actors stay outside.
- `update_element.tagged_values` merges by name (`null` removes a tag).
  Use case specs go in the built-in tags (Preconditions, Post-conditions,
  Level, Complexity, Use Case Status, Author, Assumptions) when the user
  wants them in the model.

Relationship direction (as VP stores existing ones): «include» goes `from`
the including (base) use case `to` the included one; «extend» goes `from`
the extending use case `to` the extended (base) one. For actor–use case
associations, put the actor in `from`.

## Auto-layout

`vp_layout` runs one of VP's layouts on a whole diagram (VP has no
selection-only layout). Dry-run first, tell the user it moves every shape
on that diagram, and confirm only after approval. Then export a PNG and
judge it: a layout knows nothing about course conventions (actors on the
sides, use cases inside the boundary). If it looks worse, offer
`vp_revert_batch` with its `batch_id`, which restores every position.
`route_orthogonal` re-routes lines only and is the safe first try for a
tangled but well-placed diagram.

Do not run shape layouts (hierarchical, organic, ...) on a use case diagram
with a System boundary: VP ignores containment and moves every use case out
of the boundary (seen live). For those, fix spacing with `move_element`
batches, or try the layout on a `duplicate_diagram` copy first.

## Type tiers

Verified (behave exactly as documented): diagrams Class / UseCase / Activity
/ State / ER / Interaction / Deployment; elements Actor, UseCase, Class,
Activity, InitialNode, DecisionNode, ActivityFinalNode, State2, DBTable,
Component, Node, LifeLine; relationships Association, Include, Extend,
Generalization, Dependency, Message, Transition2; members Attribute,
Operation, DBColumn. Anything else from the schema pack is accepted but
flagged `unverified`: read it back after applying. `create_raw` is the
escape hatch for any no-arg factory method.

## Trying risky changes

Duplicate the diagram (`duplicate_diagram`), apply on the copy, export or
read to verify, then replicate onto the original or delete the copy
(delete trial elements before the diagram so no orphan models remain).

## Saving

Nothing is saved until `vp_save_project {confirm: true}`. Never save
unasked; after a clean batch, offer it and report the file path. Unsaved
edits can be discarded by closing the project without saving in VP.

Restore points: before the first write after each save, `vp_apply_batch`
copies the `.vpp` as last saved to `<plugin dir>\backups\<project>\`
(configurable in `mcp.properties`: `backup`, `backup_dir`, `backup_keep`).
To roll back, the user closes VP and opens the backup copy. VP may write
the project file on its own (Teamwork projects do, seconds after edits),
so a copy can include earlier unsaved edits: each copy is the state just
before the batch that made it.
