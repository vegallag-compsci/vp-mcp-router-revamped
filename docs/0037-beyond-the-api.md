# 0037: Reaching what the public API cannot build

## Context

ADR-0036 found that swimlanes cannot be built with the obvious API calls.
The user wants the agent able to do anything they can do in VP. Phase 0b
(2026-11-20) probed the workaround rungs of `PLAN-diagram-design.md` with
a throwaway tool (removed, never committed) on the WashWatch project
(read-only plus reverted scratch diagrams) and a throwaway `probe-scratch`
project.

## Findings

1. **Generic property API (public).** `IDiagramElement
   .getDiagramElementPropertyByName(name)` returns
   `com.vp.plugin.diagram.property.IDiagramElementProperty` with
   `getValue`/`setValue(Object|boolean|int|long|double)`. Every property
   VP writes in its XML is reachable by name. Scalar values stick
   (`horizontal`, `verticalPartitionId`, verified by read-back and reload).
2. **Some list properties are not settable by any setter.** A swimlane's
   `verticalPartitionIds` / `horizontalPartitionIds` / `compartmentIds`
   ignore both the hidden setter (`v.bgk.k.setVerticalPartitionIds`) and
   the property API, even on a VP-drawn swimlane. Without them the
   swimlane renders and survives reload, but its lane dividers cannot be
   dragged (user test).
3. **VP XML export.** `ModelConvertionManager.exportXML(File)` is silent,
   ~1-3 s for WashWatch, and treats the argument as a folder
   (`project.xml`, `data.zip`, one PNG per diagram). The XML is complete:
   every view property by internal name, nesting as nested `<Shape>`,
   connectors with from/to ids, string lists as
   `<StringArrayProperty><Values><Value value=".."/></Values></...>`.
   Shape `x`/`y` attributes are image-relative; the `x`/`y` properties are
   absolute.
4. **VP XML import.** `importXML(File)` is silent and fast (0.3-1.1 s),
   keeps the same project object, and:
   - updates existing elements matched by id (a rename landed), but does
     not write the swimlane lists onto an existing swimlane;
   - creates elements with unknown ids, and the loader *does* apply the
     lists: a minimal fragment (one new diagram, its models, fresh ids,
     lists in the `<Values>` form) produced a swimlane whose lists match a
     VP-drawn one and whose dividers drag (user test);
   - re-importing a whole project over itself has side effects (it added
     stray partitions to another swimlane). Only minimal fresh-id
     fragments are safe.
5. **Building swimlane parts by API works** (it just lacks the lists):
   partition models must be both `addVerticalPartition`ed and
   `addChild`ed to the swimlane model (else they stay orphans); a view
   created for a partition model is an `ActivityPartitionHeader`;
   `createDiagramElement(diagram, "ActivitySwimlane2Compartment")` makes a
   lane area; header captions stay 0×0 until `resetCaption()` (internal)
   or an explicit caption `setBounds` (public).
6. **No route to VP's built-in menu commands.** `ActionManager` only
   returns plugin actions. Driving VP's UI (computer use) is the only way
   to Align/Distribute/Fit Size, and is not available in every session.

## Decision

- Rung 1 is the public API **including** the property API: edits to
  existing elements, scalar properties, geometry, styles, waypoints.
- Rung 2 is **minimal XML fragments** for creating structures the API
  cannot complete (swimlanes first): generate a one-diagram fragment with
  fresh ids from a template captured from a VP export, import it, read it
  back. Never import a whole-project document.
- Rung 3 (internal methods by reflection) only for small gaps with a
  public fallback (`resetCaption` → caption `setBounds`).
- Templates for fragments live as data per VP minor (like schema packs),
  each captured from a VP-drawn sample and verified by import + read-back.

## Consequences

- Imports bypass the batch applier, so the fragment writer must provide
  its own preview, backup, journal entry and undo (delete what was
  created, by id).
- VP-drawn swimlanes in user projects already have their lists, so the
  layout engine can resize and fill them through rung 1 alone.
- A working 3-lane vertical fragment is kept as a local sample in
  `var/probe/` (git-ignored) for the template work.
