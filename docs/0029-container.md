# 0029: create_element inside a container

## Context

New use cases landed outside the System boundary: `create_element` had no
parent. The UI's drop-into-boundary nests both the view
(`IDiagramElement.addChild(IShapeUIModel)`) and the model
(`IModelElement.addChild`); reads show both (`parent_id`, System members).

## Decision

- Optional `create_element.container`: a view id or plan ref of a shape on
  the diagram (System boundary, Package, ...). Bounds stay absolute.
- View nesting is required: if VP refuses, the op throws and the batch
  compensates. Model nesting is best-effort; a refusal is reported as
  `container_note` on the applied entry.
- Bounds are re-asserted after nesting so the requested position holds.
- Applied entries add `container_view_id`.

## Consequences

- Validation covers existing and planned containers (unit tests).
- Live run 2026-11-20: a UseCase created with `container` = System
  boundary view nested in both view and model (System parent), and kept
  its absolute bounds exactly (no re-basing).
