# 0026: Association aggregation fields

## Context

Association reads report `from_aggregation` and `to_aggregation`, but the
write path only applied multiplicities and navigability. Callers could not
turn suitable design-model associations into shared aggregation or
composition.

## Decision

- `connect` accepts optional `from_aggregation` and `to_aggregation` with
  values `none`, `shared`, or `composite` (case-sensitive to match VP).
- Validation rejects every other value.
- Apply uses `IAssociationEnd.setAggregationKind` and returns the effective
  value under the same field name in the applied entry.
- Existing operations without these fields remain unchanged.

## Consequences

Association ends round-trip aggregation kinds through preview/apply and
`get_diagram_by_url`. The behavior needs a live VP read-back in addition to
validator unit tests because the setter is supplied by `openapi.jar`.
