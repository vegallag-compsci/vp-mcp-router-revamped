# Install

Quick paths (details below):

| Platform | Command |
|---|---|
| Windows, from a release | unzip `vp-router-revamped-<v>.zip`, close VP, run `.\install-windows.ps1` |
| Windows, from a checkout | close VP, run `.\scripts\install-windows.ps1` (builds with Maven) |
| Linux/macOS | `curl -fsSL https://raw.githubusercontent.com/vegallag-compsci/vp-mcp-router-revamped/main/install.sh \| bash` |

## Windows

`scripts/install-windows.ps1` (also shipped as `install-windows.ps1` in
release zips):

- **Release mode** (run next to `router.jar` in an unpacked zip): installs
  the prebuilt jar. No JDK or Maven needed.
- **Source mode** (run from a checkout): `mvn package` first (unit tests
  included); `-SkipBuild` reuses `plugin\target\router.jar`.
- Installs into `%APPDATA%\VisualParadigm\plugins\vp.router\`, keeping
  `mcp.properties`, `backups\`, `journal\` and the log.
- Refuses while Visual Paradigm is running, and while the original
  read-only Avernus plugin (`plugins\vpmcp.server`) is still active (both
  use port 8899).
- If `python` and `javap` are on PATH, regenerates the schema pack from
  your VP build's `lib\lib02.jar` into the installed copy, so the schema
  guard reports a match. Otherwise the shipped pack is used (a guard
  warning is harmless).
- Options: `-VpHome`, `-PluginsDir`, `-SchemaVersion`, `-SkipBuild`,
  `-KeepRepoSchemas`, `-DryRun`.

## Linux / macOS

The curl one-liner downloads the latest release and runs its bundled
`install.sh` interactively: VP plugins directory, schema pack, opencode
MCP entry, skill. Pin a version with `bash -s -- 0.17.0`.

From a checkout the same installer is `opencode/install.sh` (it builds
from source with Maven if needed; `--dry-run` previews; env overrides in
the script header make it testable).

## Build

```bash
cd plugin
mvn package
```

Requires JDK 11+ and Maven. `openapi.jar` defaults to
`C:/Program Files/Visual Paradigm 18.1/lib/openapi.jar` on Windows and
`/usr/share/visual-paradigm/Application/lib/openapi.jar` elsewhere;
override with `-Dvp.openApi=/path/to/openapi.jar`. Output is
`target/router.jar` (gson bundled, VP provides `openapi`). Unit tests need
no running VP.

## Manual install

Copy three things into the VP user plugins directory
(`%APPDATA%\VisualParadigm\plugins\` on Windows,
`~/.config/VisualParadigm/plugins/` on Linux):

| Into `plugins/vp.router/` | From the repo |
|---|---|
| `plugin.xml` | `plugin/src/main/resources/vp.router/plugin.xml` |
| `lib/router.jar` | `plugin/target/router.jar` |
| `schemas/<version>/` | `schemas/<version>/` (match your VP, see below) |

Do this with VP closed. Restart Visual Paradigm: **Tools → MCP Router**
shows the status, and `plugins/vp.router/vpmcp.log` ends with
`MCP server listening on http://127.0.0.1:8899/mcp`.

## Configuration

Optional `plugins/vp.router/mcp.properties`:

| Key | Default | Meaning |
|---|---|---|
| `port` | `8899` | MCP port (also `-Dvpmcp.port=` on VP's command line) |
| `bind` | `127.0.0.1` | Bind address; keep loopback |
| `backup` | `true` | Copy the `.vpp` before writes |
| `backup_dir` | `<plugin dir>/backups` | Where copies go (`<dir>/<project>/<project>-<stamp>.vpp`) |
| `backup_keep` | `20` | Copies kept per project, oldest pruned |
| `journal` | `true` | Write the change journal to disk (`false`: memory only; revert still works) |
| `journal_dir` | `<plugin dir>/journal` | Where `<project>.jsonl` journals go |

## Schema pack version

Pick the directory under `schemas/` matching the running VP. Regenerate
for a new VP build with

```bash
python3 schemas/autovendor.py --openapi <VP>/lib/lib02.jar --out schemas/vX.Y --vp-version X.Y
```

then diff and commit. A mismatch degrades unknown types to standard dumps
rather than failing.

## Connect an agent

- **Claude Code:**
  `claude mcp add -s user --transport http visual-paradigm http://127.0.0.1:8899/mcp`,
  and copy the skill (`.claude/skills/vp-router`, or `claude-skills/vp-router`
  in a release zip) to `~/.claude/skills/`.
- **opencode:** merge `opencode/mcp.json` into your `opencode.json` and copy
  `opencode/skills/vp-router/` to `.opencode/skills/` (project) or
  `~/.config/opencode/skills/` (global).
- **Any MCP client:** a remote/HTTP server at `http://127.0.0.1:8899/mcp`.

VP must be running with a project open before the agent calls any tool.

## Uninstall

Close VP, delete `plugins/vp.router/` (move `backups/` and `journal/` out
first if you want to keep them), and restart Visual Paradigm.

## Debugging

Launch VP with
`JAVA_TOOL_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005"`
to attach a Java debugger to the plugin.
