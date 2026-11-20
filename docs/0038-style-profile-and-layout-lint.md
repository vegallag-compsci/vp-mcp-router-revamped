# 0038: Style profile, layout lint and layout score

## Context

Phase 1 of `PLAN-diagram-design.md`: before the agent tidies or builds
diagrams, "looks good" must be defined and measurable. `vp_lint_diagram`
had only `overlap` and `crowded` for layout.

## Decision

- `layout.StyleProfile`: a JSON profile (grid, font, line height, sizes
  per model type with a `default` entry, spacing, actor sides, score
  weights). The built-in default ships in the jar
  (`vpmcp/layout/default-profile.json`); `style_profile=<path>` in
  `mcp.properties` deep-merges an override (e.g. a private course
  profile). Unreadable overrides fall back to the default and log.
- `layout.TextMeasure`: name widths from AWT `FontMetrics` in the
  profile font (matches VP, ADR-0036); word-wrap like VP captions. Tests
  use a fixed-width measure.
- `layout.Geometry`: grid snap, containment, inset, segment and polyline
  crossings, segment-through-box with an inset so grazing lines and
  lines ending at a border do not count.
- `lint.LayoutLinter` adds rules, all thresholds from the profile:
  `off_grid` (one finding listing every off-grid shape),
  `inconsistent_size` (types with fixed sizes in the profile),
  `name_truncated` / `name_wraps` (use cases and activities),
  `line_crossing`, `line_through_shape`, `label_overlap` (actor
  captions), `misaligned` (near-miss columns/rows per container and
  type), `uneven_spacing` (gaps within an aligned column),
  `actor_wrong_side`, `boundary_padding`, `container_slack`.
- `vp_lint_diagram` returns a `layout` block: `score` 0-100 =
  round(100·S/(S+P)), S = 10 + shape count, P = Σ severity weights of
  layout findings (error 15, warning 5, info 1 by default; info always
  counted). 100 means no layout finding; every finding lowers it; size
  normalizes it so large diagrams are comparable.
- Swimlane headers and lane areas are excluded from overlap checks, and
  set lookups are null-safe (lane areas have no model type; the old
  containment rule would have thrown on any swimlane diagram).

## Consequences

- Live run 2026-11-20 on "UC - WashWatch (All)" (24 shapes): score 63,
  then 65 after calibration. Findings matched the PNG: the touching
  UC-11…18 stack and UC-07/UC-03/UC-10 (`crowded`), UC-18 wrapping, two
  ragged columns, UC-18 10 px from the boundary edge, UC-07 taller (its
  extension-points compartment), 288 px of empty boundary below the
  last use case (`container_slack`, added after the run).
- Calibration from that run: every one-line use case name had ≥ 12 px
  spare width, the one wrapping name −22 px. The wrap margin is 10 px
  (was 14, which flagged three names that render on one line).
- Not yet covered: connector label overlap (edge captions), visual
  balance. Candidates for later phases.
- Unit-tested on hand-built graphs (`LayoutLinterTest`,
  `StyleProfileAndGeometryTest`): a tidy diagram scores 100 with no
  layout finding; each rule has a positive and a negative case.
