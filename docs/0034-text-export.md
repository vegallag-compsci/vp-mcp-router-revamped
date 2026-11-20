# 0034: Text export for git (PlantUML, Mermaid, JSON)

## Context

`.vpp` files are binary, so git cannot show what a batch changed. Users
also want diagram text for reports and READMEs. The extractor already
produces a full JSON graph; it carries rendering noise (colours, z-order,
caption boxes) and its order follows VP's internal element order.

## Decision

- `export.TextExporter` turns an extractor graph into `plantuml`,
  `mermaid` or `json`. Pure function, no VP calls.
- Deterministic by construction: nodes sorted by (model type, name, id),
  edges by (from name, type, to name, id); aliases derived from names
  with numeric suffixes on collision, assigned in sorted order. No
  colours, z-order or caption geometry.
- Positions (`bounds`) appear only in `json`, so a pure layout change
  shows up there and leaves PlantUML/Mermaid untouched.
- PlantUML: actors, use cases, classes/interfaces/tables (with members),
  containers nested (System → `rectangle`, others → `package`), and
  arrows per relationship type (include/extend labelled, aggregation and
  multiplicities on associations). Unknown types fall back to
  `rectangle <<Type>>` and `-->` with a `<<Type>>` label.
- Mermaid: `classDiagram` for class diagrams; `flowchart LR` otherwise
  (Mermaid has no use case diagram), with containers as subgraphs.
- An edge whose end is not on the diagram is skipped with a comment.
- `vp_export_text {vpp_url? | all, format, out_dir?}`: inline text for
  one diagram (active by default), or files `<diagram name>.<puml|mmd|json>`
  in an absolute `out_dir` (name clashes get `-<id>`).

## Consequences

- Activity, sequence and state diagrams export structurally (nodes and
  edges) but not with their native PlantUML syntax; good enough for
  diffs, not for re-rendering. Native syntax is a later increment.
- Renaming an element renames its alias, so dependent lines diff too.
- Live run 2026-11-20 (VP 18.1, WashWatch): PlantUML and Mermaid of the
  use case diagram were correct (6 actors, 18 use cases nested in the
  boundary, 2 associations, include, extend); `all` JSON exported twice
  was byte-identical. File names are now reduced to `[A-Za-z0-9._-]`
  with single dashes (`UC-WashWatch-All.json`); the first build wrote
  `UC---WashWatch-(All).json`; the fix was confirmed live
  (`Layout-Test-copy.json`).
- Unit-tested on fixture graphs (`TextExporterTest`): shuffle-invariance,
  completeness, nesting, move isolation, alias collisions, escaping.
