# 0039: Use case layout strategy, vp_plan_layout and route_connector

## Context

Phase 2 of `PLAN-diagram-design.md`: tidy an existing use case diagram
in one previewed batch, with the score measured before and after. Live
runs on WashWatch (18 use cases, 6 actors) also turned up VP behaviour
the plan had not covered.

## Decision

- `layout.UseCaseStrategy` (pure, deterministic) plans target geometry:
  - **Actor sides:** an explicit `sides` override wins; otherwise a system
    keyword in the stereotype or name sends the actor right (an external
    system), else left (a person). Documentation is never used: prose
    about people mentions devices too.
  - **Order:** use cases in number order, grouped by actor, with
    include/extend partners right after their base.
  - **Columns:** two columns when there are more than `max_column` use
    cases or any belongs to a right-side actor.
  - **Sizes:** width = measured name + padding, rounded to the grid and
    equal within a column. The boundary fits its contents.
  - **Actors:** each sits at the mean height of its use cases, with even
    spacing.
  - **Lines:** straight, edge to edge, or a bracket outside the column
    when shapes sit between two partners. A labelled bracket widens the
    gap to the next column so its label fits.
  - **No-ops:** lines that already follow the plan, or that VP has
    already drawn straight between the right shapes, are left alone.
- `vp_plan_layout` (read-only) returns ordinary `move_element` and
  `route_connector` ops, the side reasons, the layout score before and
  predicted, the findings by rule, and `recommended`. Applying always
  goes through preview, approval and apply, so backup, journal and revert
  all apply.
- New batch op `route_connector {element, points, label?}` replaces a
  line's whole path and moves its label. Undo restores both.
- `move_element` now carries an actor's name along with it, and redraws
  straight two-point lines attached to the moved shape. Undo restores
  both.
- New lint rule `label_misplaced`: a line's label more than 20 px from
  its own line, or nearer another line.

## VP facts (probed live, 2026-11-20)

- A line's stored points and label box do not follow when shapes move.
  An actor's name box does not follow the actor either.
- VP redraws straight lines its own way (for example horizontal), so a
  planned straight route is never matched point for point.
- **«extend» direction:** VP draws the arrowhead and adds the extension
  point at the `from` end, both for lines drawn by hand and for lines
  made by the API. So in VP an «extend» runs from the base use case to
  the extending one. PlantUML and Mermaid export swap the ends to show
  the UML arrow (extension to base). The skills and the `connect` op
  description say so.
- `System` boundaries create, nest and read back reliably, so `System`
  is now a verified type.

## Results

- WashWatch layout score went from 65 to 97, the planner's prediction
  exactly.
- After the «extend» was recreated, the score went from 67 to 94, again
  as predicted. Labels sit beside their own lines.
- 241 unit tests pass, checking invariants: no overlaps, on the grid,
  equal column widths, names fit, labels beside their own line and clear
  of shapes, determinism, and idempotence (a second plan makes no moves
  and no routes).

## Also fixed in the same live session

- `vp_list_models` listed relationships twice: once from the project
  and once from their relationship container.
- `vp_export_image` now says when a diagram is empty.
- When none of a batch's ids resolve, the validation result carries a
  `hint` naming the open project, the usual cause being a project
  switch.
- The `vp_revert_batch` dry run now lists `also_removed`: views added to
  a diagram the batch created, which would be deleted along with it.

## Not yet verified live

- Redrawing straight lines on `move_element`. It is covered by the build
  only, and the first live run should check it on a scratch diagram.
