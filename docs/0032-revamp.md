# 0032: Revamped fork — cleanup, Windows, attribution

## Context

vp-mcp-router-revamped publishes the router with the Tier 1 workflow
features (ADR-0027..0031) and first-class Windows and Claude Code
support. The tree still carried leftovers: the vendored `VpMcpPlugin`
entry (superseded by `RouterPlugin`, ADR-0003, never loaded since
`plugin.xml` points at the router), a `DevServer` harness nothing used,
a HotSwap recipe whose agent jar was never committed, and machine-specific
notes.

## Decision

- Keep the plugin id `vp.router`, jar name, tool names and op shapes, so
  installs and agent setups carry over. Change only the display name.
- Delete `VpMcpPlugin` and `DevServer`; move `EchoTool` (used only by the
  protocol tests) to test sources. The vendored core is otherwise kept as
  is.
- Releases: `scripts/release.ps1` (Windows) and `opencode/release.sh`
  (Linux/macOS) produce the same `vp-router-revamped-<v>.zip`, now with
  `install-windows.ps1` (release mode), the Claude Code skill, `LICENSE`
  and `NOTICE.txt`.
- Agents never restart VP themselves; machine notes live in a
  git-ignored `CLAUDE.local.md`.
- Attribution chain (Avernus → chramiq → revamped) in `LICENSE`,
  `plugin/NOTICE.txt` and the README.

## Consequences

- ADR-0003's "vendored entry beside the router" no longer holds; the
  router is the only entry.
- Version 0.17.0 marks the revamp; earlier ADRs stay as history.
