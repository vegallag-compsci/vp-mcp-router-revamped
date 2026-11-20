# 0040: Requirement modelling (endpoint rules, IDs, tags, results)

## Context

A requirement traceability task (24 requirements and a 72-op traceability
batch) hit ten problems: bugs, missing features and token cost. Live
probes on `probe-scratch` (2026-11-20) settled how VP behaves before
anything was built.

## VP facts (probed)

- **Connection rules live in the model.** `IRelationship.setFrom`/`setTo`
  refuses a pair with "Invalid connection. <type> is not allowed with
  <rel>", where <type> is always the target. The exact pairs:
  - RQTrace, Satisfy, Verify and RequirementDerive: the target must be a
    Requirement; any source type probed is allowed.
  - Include and Extend: UseCase → UseCase only.
  - Generalization: same-kind pairs (UseCase, Actor, Class).
  - Usage: Class → Class only.
  - Dependency, Abstraction, Realization and Association: any pair.
  - Not in VP 18.1: Trace, Refine, DeriveReqt, Copy, Containment.
- **A requirement's box fields are built-in tags.** They are `Text`,
  `ID`, `source`, `kind`, `verifyMethod`, `risk` and `status`. `Text` is
  the documentation; setting either one sets both.
- **The ID the box shows is the model's user ID.** VP fills it with an
  auto-number (`REQ001`, and `UC303` for use cases). It is not the `ID`
  tag, so setting that tag never changed the box. `setUserID` changes it.
- **The auto-number is not freed by a delete.** After `REQ003` was
  reverted, the next requirement got `REQ004`. The counter is not
  reachable through the API.
- **Which tag lines a box lists** is
  `IRequirementUIModel.setShowAttributes`: 0 = follow the diagram (which
  defaults to 1), 1 = all, 2 = only tags with a value, 3 = none.
  Per-tag hiding (`hiddenTaggedValueIds`) exists as a view property but
  was not probed.

## Decision

1. Preview checks connect endpoints against
   `vpmcp/rules/connection-rules.json`, probed data shipped in the jar.
   It checks elements created earlier in the batch by their op type. It
   refuses known-bad pairs and suggests a swap when the reverse is
   allowed. Pairs of types the probe did not cover are not judged.
2. New read-only tool `vp_relationship_rules {rel_type?, from_type?,
   to_type?}`: the short catalog of names and joins. An unknown name
   such as `Trace` gets a pointer to the right one in both preview and
   the catalog.
3. Tag near-misses are refused: a name that differs from an existing tag
   only in case or spacing. On a Requirement, the `ID` and `Text` tags
   point to the new `requirement` object. Preview checks existing models;
   apply checks models made earlier in the batch.
4. `update_element.requirement {id, text, kind, source, risk,
   verify_method}` uses IRequirement's setters. `id` sets the user ID
   and keeps the `ID` tag in step. Undo restores both exactly.
   Documentation set on a requirement adds a `requirement_note` that it
   is the box's Text.
5. The `vp_revert_batch` dry run lists `numbers_not_reused`: the
   created elements' auto-numbers, read live. VP assigns them after the
   create returns, so the applied entry usually has none.
   `create_element` reports `user_id` when VP has already assigned one.
6. `style_element.show_tags`: `diagram`, `all`, `non_empty` or `none`.
7. `show_element.container`: view-only nesting, so the shared model keeps
   its owner.
8. RequirementDiagram, Requirement, Package and Satisfy are now verified
   types. RQTrace stays unverified, but its pairs are now checked.
9. A refused name now says why when a same-type sibling under the same
   owner already has it: VP silently refuses duplicates, which was the
   cause of the live "VP kept Requirement" warnings.
10. `vp_apply_batch` returns a summary by default: valid, counts per kind,
   one line per op (ids, `user_id`, warnings and notes), errors,
   compensated, backup and batch_id. `detail: "full"` gives the old
   result. The journal still records the full result.

## Tests

250 unit tests, covering:
- endpoint refusals, including for batch-created elements;
- catalog filtering;
- tag near-misses;
- requirement field write, read and restore;
- the `show_element` container in preview;
- summary size and the fields it keeps.

The live checks are listed in the commit that lands this ADR.
