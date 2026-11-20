# Changelog

## Unreleased

### Added

- Change journal: every batch that changes the project gets a `batch_id`
  and a line in `<plugin dir>/journal/<project>.jsonl`. `vp_list_changes`
  lists them; `vp_revert_batch` (dry run, then `confirm: true`) undoes one
  batch from the current VP session, refusing when a later batch touched
  the same elements (ADR-0033).
- `vp_export_text`: deterministic PlantUML, Mermaid or normalized JSON for
  one diagram or all of them, inline or written to a folder (ADR-0034).
- `vp_layout`: run a VP auto-layout on a whole diagram; every view's
  geometry is snapshotted and journaled, so `vp_revert_batch` undoes it
  (ADR-0035).
- Skills: look at a PNG after every batch, and an auto-layout workflow.
- Layout lint and score (ADR-0038): a style profile (built-in default,
  `style_profile` override in `mcp.properties`), names measured in the
  diagram font, 12 layout rules (`off_grid`, `name_wraps`, `line_crossing`,
  `misaligned`, `container_slack`, ...) and a 0-100 `layout.score` in
  `vp_lint_diagram`.
- `vp_plan_layout` (ADR-0039): plans a tidy of a use case diagram by
  meaning: actor sides (course rule), number order, columns, fitted
  sizes, boundary, edge-to-edge lines with their labels. It returns
  ordinary ops plus the score now and predicted.
- Batch op `route_connector {element, points, label?}`: sets a line's
  whole path and moves its label.
- Lint rule `label_misplaced`: a line's label away from its own line.
- `vp_relationship_rules` (ADR-0040): relationship names and the element
  types each can join, as probed in VP; names VP lacks (Trace, Refine,
  DeriveReqt) point to the right one.
- `update_element.requirement {id, text, kind, source, risk,
  verify_method}`: `id` sets the ID a requirement box shows (replacing
  VP's REQ001 auto-number).
- `style_element.show_tags` (`diagram`/`all`/`non_empty`/`none`) for
  requirement boxes; `show_element.container` frames a view without
  moving its shared model.

### Changed

- `move_element` carries an actor's name along and redraws straight lines
  attached to the moved shape. Undo restores both.
- `System`, `Requirement` and `Package`, the `RequirementDiagram` and the
  `Satisfy` relationship are verified types.
- `vp_apply_batch` returns a short summary by default (counts, one line
  per op with ids and warnings, errors, backup, batch id);
  `detail: "full"` returns everything.
- «extend» follows VP's own convention: `from` is the base use case,
  where VP draws the arrowhead and extension point. PlantUML and Mermaid
  export now draw the UML arrow from the extension to the base.

### Fixed

- Preview refuses connect endpoints VP rejects at apply (e.g. a use case
  as the target of RQTrace), including elements created earlier in the
  batch.
- Tag names that differ from an existing tag only in case or spacing are
  refused instead of silently becoming a second tag; on a requirement,
  `ID`/`Text` tags point to `requirement`.
- Documentation set on a requirement reports that it is the box's Text.
- The revert dry run lists the auto-numbers (REQ00n) VP will not reuse.
- `name_warning` explains a refused name when a same-type sibling already
  has it (VP refuses duplicates silently).

- `vp_lint_diagram` no longer fails on diagrams with swimlanes, and no
  longer reports nodes as overlapping the lane they sit in.
- `vp_list_models` no longer lists relationships twice.
- `vp_export_image` says when a diagram is empty.
- A batch whose ids all fail to resolve gets a `hint` naming the open
  project (a project switch since preview).
- The `vp_revert_batch` dry run lists `also_removed`: views added to a
  diagram the batch created.

## 0.17.0 — vp-mcp-router-revamped (first release)

Revamp of [chramiq/vp-mcp-router](https://github.com/chramiq/vp-mcp-router)
0.16.2. Tool names, op shapes and the plugin id (`vp.router`) are
unchanged, so existing agent setups keep working.

### Added

- `vp_get_selection` and `vp_get_active_diagram`: read what the user has
  in front of them and selected (ADR-0027).
- `vp_lint_diagram`: 14 structural and layout rules with ids ready for a
  fix batch (ADR-0030).
- `update_element.tagged_values`: merge-by-name tag writes with typed
  coercion, veto warnings and compensation (ADR-0028).
- `create_element.container`: nest new shapes in a System boundary or
  other container, view and model (ADR-0029).
- Automatic `.vpp` backup before writes, configurable in `mcp.properties`
  (ADR-0031).
- Windows: `scripts/install-windows.ps1` (release or source mode) and
  `scripts/release.ps1`; Maven finds VP's `openapi.jar` on Windows.
- Claude Code: `CLAUDE.md` and the `vp-router` skill under `.claude/`.

### Changed

- Release zips are `vp-router-revamped-<v>.zip` and also carry
  `install-windows.ps1`, the Claude Code skill, `LICENSE` and `NOTICE.txt`.
- Both installers stage the jar and swap it in, never writing over a
  loaded jar.
- Agents never restart Visual Paradigm themselves (AGENTS.md).
- Plugin display name: "VP MCP Router (Revamped)".

### Removed

- The unused original Avernus entry point (`VpMcpPlugin`) and the
  `DevServer` harness; `EchoTool` moved to test sources (ADR-0032).
- The HotSwap recipe from INSTALL.md (its agent jar was never in the repo).
