# 0035: vp_layout with journaled undo

## Context

Arranging shapes with `move_element` is slow. `openapi.jar` (VP 18.1,
`javap`) exposes `DiagramManager.layout(IDiagramUIModel, int)` with
`LAYOUT_*` constants (hierarchic, orthogonal, organic, smart organic,
tree variants, circular, compact, auto, connector re-routing), plus
`openAndLayoutDiagram(diagram, LayoutOption)` and
`IDiagramUIModel.layoutDiagramElement(IDiagramElement[])` /
`isAllowLayout()`. Layout is not reversible by VP's API, and it moves
everything on the diagram.

## Decision

- `vp_layout {vpp_url?, algorithm, confirm?}` lays out a whole diagram
  (active by default) with `layout(diagram, code)`. Default algorithm:
  hierarchical.
- Without `confirm`: dry run (shape/connector counts, `allowed` from
  `isAllowLayout`).
- With `confirm`: backup like apply (ADR-0031), then
  `write.LayoutSnapshot` captures every shape's bounds and every
  connector's waypoints and pin ratios, the layout runs, and the views
  whose geometry changed are reported and journaled (ADR-0033) as one
  `layout` batch whose single undo step restores the snapshot. So
  `vp_revert_batch` undoes a layout, subject to the usual conflict rule.
- If the layout call throws, the snapshot is restored at once.
- `layoutDiagramElement` (selection layout) and `LayoutOption` tuning are
  not exposed until probed.

## Consequences

- Layouts ignore modelling conventions (actors left/right, boundary
  spacing); the skill requires an image check afterwards and offers a
  revert.
- A layout that moves nothing is reported and not journaled.
- Unit-tested with mutable proxy views (`LayoutSnapshotTest`): exact
  restore, lossy-setBounds correction, unreachable shapes reported, and
  change detection.
- Live run 2026-11-20 (VP 18.1, copy of the WashWatch use case diagram,
  25 shapes, 4 connectors): `layout` is synchronous; hierarchical moved
  all 25 shapes and re-routed all 4 connectors. It ignores containment:
  the System boundary stayed put and every use case landed outside it,
  unconnected use cases stacked in rows. Use case diagrams with a
  boundary should not get whole-diagram layouts; the skill says so.
- Same run: a plain `setBounds` restore came back 1 px up-left on most
  shapes and 1-2 px narrower on some (VP keeps doubles; the int setter
  and getters round). `duplicate_diagram` shows the same 1 px shift.
  `LayoutSnapshot.restore` therefore runs up to four passes, aiming past
  each observed error, and throws (step reported as not undone) if any
  shape still differs. Re-run live with the fix: hierarchical layout on a
  fresh copy, then revert; the normalized JSON export before the layout
  and after the revert was byte-identical (all 25 shapes exact).
