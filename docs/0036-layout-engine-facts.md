# 0036: VP geometry facts the layout engine builds on

## Context

Phase 0 of `PLAN-diagram-design.md`: before writing a layout engine,
capture how VP 18.1 behaves for sizing, nesting, line routing and
swimlanes. Probed 2026-11-20 with a throwaway tool (removed, never
committed) on scratch diagrams of a Teamwork project; everything created
was removed afterwards.

## Findings

1. **No auto-fit.** A use case created 60 px wide with a 49-character
   name keeps 60×40 and renders wrapped and truncated ("UC-18 Manage...").
   `setRequestResetCaptionSize(true)` and
   `setRequestResetCaptionFitWidth(true)` change nothing, before or after
   a render. The engine must size shapes itself.
2. **Text measurement matches VP.** AWT `FontMetrics` inside VP's JVM
   (not headless; font `Dialog` plain 11, line height 15) gives widths
   that agree with VP's rendering:
   - actor caption boxes are measured width + 21 px in every sample
     (Student 37→58, Maintenance Staff 88→109, Notification Service
     93→115);
   - a 207 px name fits on one line in a 221 px use case; a 262 px name
     wraps in 240 px.
   Rule for one-line use cases: width ≥ text + ~30 px, rounded up to the
   grid. To be confirmed visually in Phase 1.
3. **Containers do not drag children.** Moving a System boundary 100 px
   leaves its nested use case in place (still parented, now sticking
   out). The engine moves children explicitly.
4. **`duplicate_diagram` drops nesting** (and shifts shapes ~1 px, see
   ADR-0035): copies have no parent links. Fine as a layout test bed for
   flat geometry, not for containment.
5. **Waypoints on existing connectors work.** `clearPoints` + `addPoint`
   on an association is kept and rendered exactly (orthogonal route).
   Connectors created through the API report no points until set.
   A `route_connector` op is feasible.
6. **Swimlanes need view parts the public API cannot build.** A
   VP-drawn swimlane is:
   - an `ActivitySwimlane2` view whose raw properties list
     `horizontalPartitionIds` / `verticalPartitionIds` (header view ids)
     and `compartmentIds`;
   - per lane, an `ActivityPartitionHeader` view (15 px strip, model =
     the `ActivityPartition`, `horizontal` flag);
   - per lane, an `ActivitySwimlane2Compartment` view (no model) whose
     `horizontalPartitionId` / `verticalPartitionId` points at its header.
   Partitions are model children of the swimlane. Building only the
   swimlane view (`createDiagramElement(diagram, swimlaneModel)`) gives
   an invisible shape: no headers, no lanes. `IActivitySwimlane2UIModel`
   exposes no setters for the id lists. Assigning an action works on the
   model side (`partition.addContainedElement`) and as a view child
   (`addChild`). Partitions added with `addVerticalPartition` are not
   deleted with their swimlane (orphans); VP-drawn ones are.

## Decision

- Phase 1 sizes shapes from measured text (finding 2) and snaps to the
  grid; no reliance on VP fitting.
- Strategies move containers and their children together (finding 3).
- Phase 2 adds `route_connector` (finding 5).
- Phase 4 (activity) starts with swimlanes the user drew: the engine
  reads lanes from headers/compartments and places and assigns nodes.
  Creating swimlanes programmatically needs a further probe (creating
  header and compartment views by shape type, then setting the id lists
  through the implementation class); until then it is out of scope.
  Superseded by ADR-0037: swimlanes are created by importing a minimal
  VP XML fragment.

## Consequences

- Text widths depend on the diagram font; profiles must name it.
- Test beds for containment must be built with `create_element
  container`, not `duplicate_diagram`.
