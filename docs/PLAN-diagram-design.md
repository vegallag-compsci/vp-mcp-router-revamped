# Plan: well-designed diagrams by construction

Goal: when the agent creates or tidies a diagram, the result is neat,
consistent and logically organized without hand-nudging, and every
result is previewed, backed up, journaled and undoable.

Status: Phases 0 and 0b (probes) done (ADR-0036, ADR-0037); plan
approved 2026-11-20. Phase 1 done (ADR-0038): WashWatch baseline
layout score 65.

## Why the current tools are not enough

- The agent picks x/y by eye, so spacing, sizes and alignment drift
  between batches and between diagrams.
- VP's own layouts (`vp_layout`) ignore meaning and containment: live,
  hierarchical layout moved all 18 WashWatch use cases out of the System
  boundary.
- VP does not size shapes to their names; it wraps and truncates.
- `vp_lint_diagram` has only two layout rules, so "looks bad" is not
  measurable yet.

## Approach

Put design decisions in tested code, not in the agent's judgement:

1. A **style profile** says what good looks like.
2. A **layout engine** per diagram type places and sizes elements by
   meaning (who connects to whom, lanes, layers).
3. The engine only **plans**: it returns ops, which go through
   preview → user approval → apply (backup, journal, revert all apply).
4. **Layout lint + a score** measure the result; the agent also looks at
   a PNG before calling it done.

## What the probes established (ADR-0036, ADR-0037)

| Fact | Consequence |
|---|---|
| AWT text measurement inside VP matches VP's rendering | Shapes are sized from their names, on the grid |
| VP never resizes shapes to fit names | The engine always sets sizes |
| Moving a container does not move its children | Strategies move children explicitly |
| Existing lines accept waypoints (kept and rendered) | Neat orthogonal routing via a new `route_connector` op |
| Every saved property is reachable by name through the public `IDiagramElementProperty` API | Most "hidden" settings are rung 1, not reflection |
| Some structures (swimlane lane lists) can't be set by any setter | They are created by importing a minimal VP XML fragment |
| A minimal fresh-id XML fragment imports cleanly (0.3 s) and yields a fully working swimlane (dividers drag) | XML fragments are the creation route for anything the API can't complete |
| Whole-project XML re-import has side effects | Never done; only minimal fragments |
| VP's built-in Align/Distribute/Fit commands are unreachable from plugins | Not needed: the engine computes geometry itself |

## Access ladder (in order of preference)

| Rung | Route | Used for |
|---|---|---|
| 1 | Public API, including the generic property API | All edits: geometry, styles, names, scalar properties, waypoints, model wiring |
| 2 | Minimal VP XML fragments, generated from templates and imported | Creating structures the API can't complete (swimlanes first) |
| 3 | Internal methods by reflection | Only small gaps with a public fallback (e.g. `resetCaption`) |
| 4 | Driving VP's UI (computer use) | Not planned; unavailable in most sessions and slow |

## Components

### 1. Style profile (data)

`schemas/style/default.json`; a private course profile (git-ignored)
overrides it for course projects. Contents:

- Grid 10 px; everything snaps to it.
- Sizes: actor 30×60; use case height 40, width = measured name + 30,
  rounded up to the grid, equal within a column; activity/action width
  from the name; class width from the longest member; lane width from
  content.
- Spacing: sibling gaps, boundary and lane padding, rank gap for flows.
- Placement: primary actors left, external actors right; layers
  top-down; happy path straight down, alternates to the right.
- Colours and fonts per element kind and per architecture layer.
  Styling freedom (decided 2026-11-20): tidies may recolour, resize,
  restyle and add labels and notes, always as a previewed batch.

### 2. Geometry core (`vpmcp.layout`, pure Java)

Text measurement, snap, packing, barycenter ordering, crossing counter,
overlap/alignment checks, on a graph model built from the extractor
JSON. Unit-testable without VP.

### 3. Strategies, one per diagram type

| Diagram | Strategy |
|---|---|
| Use case | Boundary centred; use cases in one column (two past ~12), by number then grouped by actor; «include»/«extend» targets beside their base; actors left/right per profile at the centre of their use cases; boundary fits content. |
| Activity | Lanes as columns in profile order (actor, screens, services, external); nodes ranked top-down along control flow, each in its lane; happy path straight down, alternates right, finals at the bottom, object nodes beside producers. |
| Class | Generalization parents above children; associations as horizontal neighbours; unconnected classes in a grid below. |
| Architecture / BDD | Layers as coloured horizontal bands; external systems at the side; nested blocks inside parents. |
| Sequence | Lifelines in first-message order, evenly spaced; messages evenly spaced. |

Deterministic: same model in, same layout out.

### 4. Tools and ops

- `vp_plan_layout {vpp_url?, profile?}` (read-only): returns the ops to
  tidy an existing diagram, plus before/after scores.
- `vp_plan_diagram {spec, profile?}` (read-only): returns the ops (and
  fragments, where needed) that build a new diagram from a spec.
- New batch ops: `route_connector {element, points}`,
  `set_property {element, name, value}` (the generic property API,
  verified tier for known names), `resize` via `move_element`.
- New fragment writer: `import_fragment` as a batch op, so it gets the
  same preview, approval, backup and journal. Its undo deletes what the
  fragment created, by id, and its applied entry lists every created id.
  Templates are captured per VP minor from VP-drawn samples (like schema
  packs) and verified by import + read-back.

### 5. Layout lint and score

New rules: `off_grid`, `inconsistent_size`, `misaligned`,
`line_crossing`, `line_through_shape`, `label_overlap`, `name_truncated`
(name wider than its shape), `uneven_spacing`, `actor_wrong_side`,
`outside_container`, `boundary_padding`; plus `layout_score` (0–100).

### 6. Skill workflow

Create or tidy → `vp_plan_*` → preview, explained in plain words → user
approves → apply → read back → lint score → PNG check → at most two fix
rounds → offer save.

## Phases

Each phase: build → unit tests → live run on a scratch project or copy
→ ADR → review stop → its own commit.

| # | Phase | Delivers |
|---|---|---|
| 1 | Profile, geometry core, layout lint + score | WashWatch gets a measured score and a list of concrete layout problems |
| 2 | Use case strategy, `vp_plan_layout`, `route_connector`, `set_property` | WashWatch tidied in one previewed batch, score before/after |
| 3 | `vp_plan_diagram` for use cases | New use case diagrams built neat from a spec |
| 4 | Fragment writer + swimlane template + activity strategy | Course-style activity diagrams with real, resizable swimlanes, created from a spec |
| 5 | Class and architecture/BDD strategies | Layered class and block diagrams |
| 6 | Sequence strategy | Evenly spaced interactions |

Phases 1–2 fix existing use case diagrams; 3–4 make new use case and
activity diagrams from scratch.

## Testing

- Unit tests assert invariants, never coordinates: no overlaps, children
  inside containers, actors on their side, on-grid, equal sizes in a
  column, names fit their shapes, zero crossings where possible,
  determinism (shuffled input, same ops).
- Live runs in `probe-scratch` or on copies: plan, apply, score
  before/after, PNG, revert.
- Fragment writer: import, read back every created id, check lists and
  links, user drag test once per template.
- Reference diagrams from the course examples (private) as the visual
  acceptance set.

## Open decisions (defaults apply until answered)

1. Diagram order after use cases: activity → architecture → class →
   sequence?
2. Course profile as default for course projects, generic profile
   otherwise?
