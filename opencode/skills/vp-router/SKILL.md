---
name: vp-router
description: Read and model UML/ERD diagrams in the locally running Visual Paradigm project over MCP
---

## What I do

Read the open Visual Paradigm project as JSON graphs and modify it
through validated write batches: create diagrams and elements,
connect them, edit properties, members and styling, duplicate
diagrams for trials, delete, save. Tool descriptions are the
contract; this file is the workflow.

## Prerequisites

VP must be running with a project open. On "Open API is unavailable"
or "No project is currently open", tell the operator to open VP
instead of retrying. Start every session with `vp_capabilities`.

## Reads

Start from what the operator sees: `vp_get_selection` for "this/these",
`vp_get_active_diagram` for "this diagram"; ask only when nothing is
active or selected. `vp_lint_diagram` reviews a diagram (active by
default) and returns findings with ids ready for a batch.

`vp_list_diagrams`, `vp_list_models` (every model incl. members and
orphans), `vp_get_model` (one model by id, with the diagrams it is
shown on), `get_diagram_by_url` (full graph + visuals),
`vp_get_neighborhood` (N-hop slice; the center accepts a view id or a
model id), `vp_export_image` (png/svg/pdf, crop or
`crop_to_element`). Prefer SVG for captioned diagrams: the PNG
rasterizer clips captions at shape bounds, vector markup keeps them.
`vp_export_text` (plantuml/mermaid/json, `out_dir`, `all`) writes
deterministic text for git diffs and reports.

## Writes (tiers, not walls)

Dry-run with `vp_preview_batch`, show the plan with undos, then
`vp_apply_batch` with `confirm: true`. Partial failure compensates
in reverse; report applied/compensated/errors. After every batch,
read back and then look: export a PNG of the changed area and check
for hidden lines, overlapping labels or shapes, and elements outside
their boundary; propose fixes for what you see.

`vp_layout` lays out a whole diagram (dry run, then `confirm: true`);
judge the result from an image and offer `vp_revert_batch` if it is
worse. `route_orthogonal` only re-routes lines. Never run shape
layouts on use case diagrams with a System boundary: VP moves every
use case out of it.

- Types are tiered. Verified families (Class/UseCase/Activity/State/
  ER/Interaction/Deployment diagrams; Actor/UseCase/Class/Activity/
  InitialNode/DecisionNode/ActivityFinalNode/State2/DBTable/Component/
  Node/LifeLine elements; Association/Include/Extend/Generalization/
  Dependency/Message/Transition2 relationships; Attribute/Operation/
  DBColumn members) behave exactly as documented.
- Anything else from the schema pack validates but is flagged
  `unverified` in the plan — VP may veto silently or misplace, so
  read the result back after applying. Check `vp://schemas` resources
  for the type universes (diagram-types, factory-creates).
- `create_raw` invokes any no-arg factory method directly; placement
  refusals and VP's transient kinds (never saved, never listed) are
  reported as notes.
- Edits: `update_element` (name, documentation, stereotypes — replaces
  all), `update_member` (attribute/operation/column fields; parameters
  replace all), `style_element` (colours, fill, line, font, hex like
  reads report).
- `move_element` repositions a view. `show_element` places another
  view of an existing model — the fix for duplicate-name renames.
  `delete_model` removes a model plus all its views (final); use it
  after `delete_diagram` so no orphan models linger.
- `name_warning` on an applied entry means VP overrode the name
  (reserved words, table naming); use the kept name. `vp_id` in an
  applied entry is the model id; `view_id` is the view.

## Trial loop

Duplicate the target, apply candidates on the copy, read/export to
verify, then keep (replicate to source) or `delete_diagram` the
copy. Delete trial elements before the diagram so no orphan models
linger. Deletion is final — name targets explicitly at confirm.

## Saving

Never auto-saved. After clean batches offer `vp_save_project`
(`confirm: true`) and report the file path. Apply copies the `.vpp` as
last saved before the first write after each save (`backup` in the
result; `backup`/`backup_dir`/`backup_keep` in `mcp.properties`).
Each applied batch gets a `batch_id`. To undo one, dry-run
`vp_revert_batch` (no `confirm`), explain undo steps, `irreversible`
deletions and `conflicts`, then confirm on approval. `vp_list_changes`
lists what was changed.
New elements go inside a boundary with `create_element.container`;
`update_element.tagged_values` merges tags by name (null removes).
