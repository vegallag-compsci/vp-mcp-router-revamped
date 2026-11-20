# Roadmap

Ideas not built yet, roughly in order of value. Each needs a probe
against live VP before code (AGENTS.md).

## Workflow

- `vp_layout`: trigger VP's built-in layouts (`DiagramManager.layout`,
  `LAYOUT_HIERARCHIC`, `LAYOUT_ORTHOGONAL`, ...) on a diagram or selection.
- Text export (PlantUML / Mermaid / JSON) so model changes diff in git.
- Lint auto-fix suggestions: emit a ready `vp_preview_batch` op list per
  finding.

## Modelling

- Text-to-diagram skill: requirements or PlantUML in, previewed batch out.
- Use case specifications: write flows into VP's use case description
  (`IUseCase.getUseCaseDescription`) and export as a document.
- Cross-diagram consistency checks (use case ↔ sequence/activity, class
  names across diagrams).

## Integration

- Claude Code hooks: block `vp_apply_batch` without a matching preview;
  log every apply.
- Slash commands: `/vp-review`, `/vp-fix`, `/vp-spec`, `/vp-export`.
