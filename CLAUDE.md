# CLAUDE.md

@AGENTS.md

AGENTS.md (above) is the agent contract for this repo. This file adds what
is specific to **Claude Code**. Put machine- or project-specific notes in a
`CLAUDE.local.md` next to this file; it is git-ignored and loaded too.

## MCP connection

- Register once (user scope, so every project sees it):
  `claude mcp add -s user --transport http visual-paradigm http://127.0.0.1:8899/mcp`
- Tools appear as `mcp__visual-paradigm__vp_*` plus
  `mcp__visual-paradigm__get_diagram_by_url`.
- VP must be running with a project open. ECONNREFUSED means VP is closed or
  still loading: tell the user, don't retry in a loop.
- The `vp-router` skill (`.claude/skills/vp-router/`) is the modelling
  workflow. Load it before any read or write session on a diagram.

## Build, test, install

See [docs/INSTALL.md](docs/INSTALL.md). In short: `mvn -B package` in
`plugin/`, then `scripts\install-windows.ps1` (Windows) or
`opencode/install.sh` (Linux/macOS). Both refuse or warn while VP is open;
ask the user to close it. Never launch, kill or restart VP yourself
(AGENTS.md, "VP restarts").

## Editing the user's model

- `vp_preview_batch` first, explain the plan in plain words, then
  `vp_apply_batch {confirm: true}` only after the user approves **that**
  batch. Read back what landed.
- Prefer `vp_get_selection` / `vp_get_active_diagram` over asking for
  links, and `vp_lint_diagram` for reviews.
- Never save unasked; offer `vp_save_project` after clean batches, and
  mention the first `backup` path an apply reports.
