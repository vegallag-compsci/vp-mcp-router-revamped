# 0028: Tagged values on update_element

## Context

Reads already return tagged values (use cases carry Preconditions,
Post-conditions, Level, Complexity, Status ...), but no op could set them.
VP 18.1 exposes `ITaggedValueContainer` (get by name, create, remove) and
typed `ITaggedValue.setValue` overloads.

## Decision

- `update_element.tagged_values`: an object merged by tag name. A string,
  number or boolean sets the tag (creating it when absent); `null` removes
  it; unnamed tags are untouched.
- Existing tags keep their VP type: integer/float/boolean tags parse the
  value; model-element/date/time tags are left unchanged with a warning.
  New tags take the JSON type.
- Every write is read back; a value VP did not keep (enumerations snapping
  to a literal) comes back in `tagged_value_warnings`, not as a failure.
- Compensation snapshots the touched tags (old text, or absent) and
  restores them; created tags are removed.
- Logic in `write.TaggedValueWriter`, tested on stateful fakes
  (mutation-checked: dropping the removal fails `nullRemovesATag`).

## Consequences

- Use case specs can be written into the model, not only into docs.
- Live run 2026-11-20: the built-in use case tags (Preconditions,
  Complexity) took text and kept it ("Medium" stuck, no warning);
  setting "" restored them. Typed/enumeration behavior beyond that stays
  covered by the read-back warning.
