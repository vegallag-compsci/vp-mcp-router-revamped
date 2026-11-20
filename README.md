# vp-mcp-router-revamped

An MCP server that runs **inside Visual Paradigm** and lets an AI agent
(Claude Code, opencode, or any MCP client) read, review and edit the
project you have open — safely.

- **Reads** — diagrams with full formatting, the model tree (including
  members), neighborhoods, PNG/SVG/PDF export, and **what you are looking
  at**: the active diagram and your current selection.
- **Review** — `vp_lint_diagram` flags unconnected actors and use cases,
  shapes past their container, overlaps and crowding, duplicate names,
  numbering gaps, bad include/extend ends and missing documentation, with
  element ids ready for a fix.
- **Writes** — create (optionally inside a container such as a System
  boundary), connect, move, style, document, set tagged values, delete.
  Every change is previewed (`vp_preview_batch`), applied only with
  explicit confirmation (`vp_apply_batch`), rolled back in reverse on
  partial failure, and preceded by an automatic `.vpp` backup. Nothing is
  saved until you say so.
- **Schema packs** — VP's type universe is versioned data per VP minor
  (`schemas/v18.1/`), so a VP update means regenerating a pack, not
  rewriting the plugin.

Tested on Visual Paradigm 18.1 (Windows and Linux). Java 11+ runtime (VP
bundles one).

## What's new in the revamp

Built on [chramiq/vp-mcp-router](https://github.com/chramiq/vp-mcp-router):

| Added | Why it matters |
|---|---|
| `vp_get_selection`, `vp_get_active_diagram` | "Fix these" works without pasting diagram links |
| `vp_lint_diagram` | Consistent, structured reviews the agent can turn into a fix batch |
| `update_element.tagged_values` | Fill in use case specs (Preconditions, Level, ...) in the model |
| `create_element.container` | New use cases land inside the System boundary |
| Automatic `.vpp` backups before writes | A restore point even for batches you regret |
| `vp_list_changes`, `vp_revert_batch` | A change journal: see what the agent changed, undo one batch |
| `vp_layout` | VP's auto-layouts, journaled so one call undoes them |
| `vp_export_text` | PlantUML / Mermaid / JSON text of diagrams for git diffs and reports |
| Layout lint + score in `vp_lint_diagram` | "Looks untidy" becomes concrete findings and a 0-100 score |
| Windows installer and release script, Claude Code skill | First-class Windows + Claude Code support |

See [CHANGELOG.md](CHANGELOG.md) for details.

## Install

### Windows

1. Download `vp-router-revamped-<version>.zip` from
   [Releases](https://github.com/vegallag-compsci/vp-mcp-router-revamped/releases)
   and unzip it.
2. Close Visual Paradigm, then in the unzipped folder:

   ```powershell
   .\install-windows.ps1
   ```

   (From a source checkout: `.\scripts\install-windows.ps1` builds with
   Maven first. Pass `-VpHome` if VP isn't in
   `C:\Program Files\Visual Paradigm 18.1`.)

### Linux / macOS

```bash
curl -fsSL https://raw.githubusercontent.com/vegallag-compsci/vp-mcp-router-revamped/main/install.sh | bash
```

Interactive: asks for the VP plugins directory and schema pack, and can
wire up opencode. Needs `bash`, `curl`, `unzip`, `python3`.

### Check it

Open Visual Paradigm with a project: **Tools → MCP Router** shows
`running on port 8899`.

Full build, manual install, configuration and uninstall:
[docs/INSTALL.md](docs/INSTALL.md).

## Connect your agent

The server speaks MCP over Streamable HTTP at `http://127.0.0.1:8899/mcp`
(loopback only).

**Claude Code**

```bash
claude mcp add -s user --transport http visual-paradigm http://127.0.0.1:8899/mcp
```

For the modelling workflow, copy `claude-skills/vp-router` from the
release zip (or `.claude/skills/vp-router` from a checkout) to
`~/.claude/skills/`.

**opencode** — merge [opencode/mcp.json](opencode/mcp.json) into your
`opencode.json` and copy `opencode/skills/vp-router` to your skills folder
(the Linux installer offers to do both).

**Other MCP clients** — add a remote/HTTP MCP server pointing at the URL
above.

## Using it

Ask in plain words, with the diagram open in Visual Paradigm:

- "Review the diagram I have open."
- "Connect the selected actor to these use cases."
- "Add a use case *Pay Fine* inside the system boundary and document it."
- "Fill in the preconditions for UC-04."

The agent previews every batch and waits for your go-ahead. Tool
descriptions are the contract; the skill
([Claude Code](.claude/skills/vp-router/SKILL.md),
[opencode](opencode/skills/vp-router/SKILL.md)) is the workflow.

## Safety model

- Loopback-only server, no auth: for single-user machines.
- No write without `confirm: true`; preview first.
- Creations and updates compensate in reverse on failure; deletions are
  final and called out in the preview.
- The `.vpp` is copied to `<plugin dir>\backups\<project>\` before writes
  (`backup`, `backup_dir`, `backup_keep` in `mcp.properties`).
- Every batch is journaled to `<plugin dir>\journal\<project>.jsonl`;
  `vp_revert_batch` (dry run first, then `confirm: true`) undoes one
  batch from the current VP session.
- Never auto-saves; `vp_save_project` is confirm-gated.

## Develop

```bash
cd plugin && mvn package     # builds router.jar and runs the unit tests (no VP needed)
```

Needs JDK 11+, Maven, and VP's `openapi.jar` (found automatically on
Windows; pass `-Dvp.openApi=...` elsewhere). Agent and contributor rules:
[AGENTS.md](AGENTS.md); design decisions: [docs/INDEX.md](docs/INDEX.md);
roadmap: [TODO.md](TODO.md).

## Credits and license

[MIT](LICENSE). Revamp of
[chramiq/vp-mcp-router](https://github.com/chramiq/vp-mcp-router), which
vendors the MCP core from
[Avernus Software's Visual Paradigm MCP plugin](https://github.com/avernussoftware/visualparadigmmcp).
Attribution: [plugin/NOTICE.txt](plugin/NOTICE.txt). Not affiliated with
Visual Paradigm International.
