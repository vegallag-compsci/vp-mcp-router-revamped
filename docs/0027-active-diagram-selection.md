# 0027: Active diagram and selection reads

## Context

Every read and write needed a diagram id the user had to find and paste.
The operator is already looking at the diagram, often with the elements
they mean selected. `DiagramManager.getActiveDiagram()` and
`getSelectedDiagramElements()` exist in the 18.1 API (javap surface
review); `IDiagramUIModel.getSelectedDiagramElement()` is the fallback.

## Decision

- `vp_get_active_diagram {detail?}`: the active diagram in the same graph
  shape as `get_diagram_by_url`, plus `selected_ids`.
- `vp_get_selection {}`: a light list per selected element (`view_id`,
  `model_id`, `name`, `model_type`, `shape_type`, `kind`, bounds or
  from/to, `parent_id`), ids ready for batch ops.
- No active diagram is a structured error; nothing selected is
  `selected_count: 0`, never an error. The skill says: ask, don't guess.
- Description lives in `vp.SelectionReader.describe`, separate from the
  VP singletons, so the JSON contract is unit-tested with fakes.

## Consequences

- "Connect these", "fix what I selected" work without links.
- Live run 2026-11-20: `getSelectedDiagramElements()` reports the
  selection while the VP window is unfocused (a freshly created element,
  which VP auto-selects, came back as the selection). Empty means nothing
  is selected.
