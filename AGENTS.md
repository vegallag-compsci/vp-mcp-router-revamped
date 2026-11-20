# AGENTS.md

vp-mcp-router-revamped: an in-process Visual Paradigm plugin that
serves the open project as JSON over Streamable HTTP, reviews it, and
writes back through previewed, compensable, backed-up batches. Writes are tiered — verified
families behave exactly as documented, schema-pack types are accepted
but flagged unverified. Schema packs are versioned per VP minor so VP
updates ship as data, not rebuilds.

## Repo layout

| Path | What lives there |
|---|---|
| `plugin/` | Java plugin source: zero-dep MCP core, EDT invoker, extractor, write path |
| `schemas/` | versioned JSON schema packs per VP minor (`v18.1/`); `autovendor.py` regenerates from `openapi.jar` |
| `install.sh` | Linux/macOS bootstrapper: downloads a GitHub release and runs the bundled `opencode/install.sh` (the curl-pipeable install path) |
| `scripts/` | Windows: `install-windows.ps1` (build or release-zip install), `release.ps1` (cut a release) |
| `opencode/` | Linux/macOS `install.sh` and `release.sh`, opencode `mcp.json` snippet, opencode skill |
| `.claude/skills/vp-router/` | Claude Code skill (the same workflow as the opencode skill) |
| `CLAUDE.md` | Claude Code specifics on top of this file; `CLAUDE.local.md` (gitignored) holds private notes |
| `docs/` | architecture decision records (`INDEX.md` + one file per ADR), `INSTALL.md` |
| `dist/` | release zips built by the release scripts (gitignored; published as GitHub Release assets) |
| `var/` | runtime state and probe captures (gitignored) |
| `TODO.md` | roadmap: deferred ideas and tasks |

## Glossary

Terms below are the project's shared vocabulary: use them verbatim in code,
docstrings, prompts, and docs. If you spot a stale or conflicting use,
report it with file/line evidence and a proposed correction.

| Term | Definition |
|---|---|
| open project | The `.vpp` currently open in the running VP instance. The only project the plugin can see. |
| diagram view | A shape or connector on a diagram (`IDiagramElement`): bounds, z-order, visual properties. |
| model element | The logical element behind a view (`IModelElement`): name, type, documentation, stereotypes. |
| member | A child model element (attribute, operation, column). Not in VP's project id index — reachable only through its parent (`ModelLookup`). |
| extractor | The VP-to-JSON transform producing `{diagram, nodes[], edges[], warnings[]}`. |
| schema pack | Versioned JSON schemas for one VP minor under `schemas/<version>/`. |
| tier | Write-type gate: verified (probe-tested, exact behavior), pack (schema-pack member, accepted but flagged `unverified`), or impossible (VP cannot create it, hard error). |
| applied entry | Per-op apply result: `vp_id` is the model id, `view_id` is added when a view exists. Both ids are needed downstream — move/style/delete_element take views, update/delete_model take models. |
| probe | A throwaway script capturing real `openapi.jar`/VP output. Code is written against captures, never from docs alone. |
| capabilities | The `vp_capabilities` response: VP version, plugin version, schema version, schema guard report. |

## Rules

### Workflow

- Small increments: never land large untested code. If it doesn't work it just pollutes the repo.
- Stop after each phase and wait for review before starting the next.
- One scoped commit per phase, message documents what changed.
- Versions change only through a release script —
  `scripts/release.ps1 -Version <v>` (Windows) or
  `opencode/release.sh <v>` (Linux/macOS). Both refuse dirty trees,
  test, build and package `dist/`. Never hand-edit the VERSION string.
- Order: research → probe → implement → test → review → live run → docs.
- Probe-first for anything touching `openapi.jar` or live VP.

### VP safety

- Never mutate the open project (create, update, delete, save) without explicit user approval per action.
- All writes go through `vp_apply_batch` with `confirm: true`, re-validated
  immediately before the first mutation; creations and updates compensate
  in reverse order on failure, deletions are final. Apply never saves —
  `vp_save_project` (also confirm-gated) is the only path to disk.
- Unverified-tier writes may hit VP's silent vetoes (names that don't
  stick, refused placement). The applied entry carries the effective
  values and `name_warning`; always read back what actually landed.

### VP restarts

- Agents never launch, kill or restart Visual Paradigm on their own. Ask
  the user, every time. Restarts can drop license activation (observed on
  an academic license) and lose unsaved work.
- Before asking for a restart, offer `vp_save_project` (confirm-gated)
  if the agent has made unsaved edits; with a save, the `.vpp` mtime moves
  5-20s later (writes land async).
- After a restart, the endpoint answers before the project finishes
  loading — early reads can show an empty project. Poll
  `vp_list_diagrams` until the diagram count is stable.
- Launching VP with the project path skips the startup dialog
  (`visual-paradigm <file>.vpp` on Linux; the `.vpp` file on Windows).
- Never copy a jar over a running VP: it tore the classloader with a
  ZipException. The installers refuse while VP runs and stage the jar
  next to the target before swapping it in.

### Code

- New decisions get an ADR in `docs/`, appended to `docs/INDEX.md`.
- MCP tool descriptions are the single source of truth for tool use; the skill never duplicates them.
- Tool failures return explicit structured errors: never silent empties, never raw stack traces as the answer.

### Tests

- Tests assert behavior, never literals: structural invariants (counts
  derived from fixture builders, referential integrity, ordering,
  error contracts), never golden JSON blobs or hardcoded VP ids.
- No fake coverage: a test must fail if the code under it breaks. No
  tautologies (asserting the fixture back to itself), no mocks of the
  unit under test, no `assertTrue(true)` padding.
- Brittle-by-construction is banned: tests must survive irrelevant
  changes (new optional fields, reordered keys, extra warnings) and
  break only on contract changes.
- Two layers: unit tests on plain JDK (`mvn test`, proxy fakes, no VP)
  for logic; live runs over HTTP against VP for integration. Neither
  replaces the other.
