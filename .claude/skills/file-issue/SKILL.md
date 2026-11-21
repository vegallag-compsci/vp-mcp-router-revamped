---
name: file-issue
description: File, find and resolve GitHub issues for this repo with the gh CLI. Use when the user reports a bug or missing feature to track ("log this", "file a bug", "make an issue"), asks what is open, or says "work on #N" / "fix #N".
---

# GitHub issues for vp-mcp-router

The repo `vegallag-compsci/vp-mcp-router-revamped` is **public**: every
issue, comment and label is published. Forms live in
`.github/ISSUE_TEMPLATE/` (bug, feature); labels come from
`scripts/setup-labels.ps1`.

`gh` may not be on PATH in a fresh shell. In PowerShell, reload it first:
`$env:Path = [Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [Environment]::GetEnvironmentVariable("Path","User")`.

## Filing (user reports a bug or a missing feature)

1. **Gather facts yourself; don't ask for what you can read.**
   - Versions: `vp_capabilities`.
   - Failing op, error text and batch id: the conversation, `vp_list_changes`.
   - Plugin log: `%APPDATA%\VisualParadigm\plugins\vp.router\vpmcp.log`.
   - Code location: `file:line` when you know it.
2. **Check for duplicates:** `gh issue list --search "<key words>" --state all`.
   If one matches, offer a comment on it instead of a new issue.
3. **Draft it the way the form shapes it.**
   - Bug: What happened, What should happen, How to reproduce (minimal ops
     on a throwaway diagram), Versions, Severity, Evidence.
   - Feature: need, gap, possible approach.
   - Title: short and specific, e.g. "Preview accepts RQTrace to a use
     case that apply rejects".
   - Labels: `bug` or `enhancement`, one `area:*`, plus `needs-probe` when
     VP's behaviour is unknown, and `vp-behaviour` when VP itself is the
     cause.
4. **Scrub before showing.** The issue is public. Remove:
   - the user's name and email, and VP author fields (`pmAuthor`, `userID`
     of people);
   - local paths beyond `%APPDATA%\...` (never `C:\Users\<name>`);
   - course material: the vp-course skill, professor, assignment and
     course conventions;
   - real model content (project, diagram and element names from the
     user's models). Use neutral stand-ins such as "UC-01 Do Thing" and
     "Requirement A".
5. **Show the full draft** (title, labels, body) and wait for the user's
   OK. Creating an issue publishes it; the approval covers that one issue
   only.
6. **Create it:** write the body to a scratchpad file, then run
   `gh issue create --title "..." --label bug --label area:write --body-file <file>`.
   Report the issue URL.

Several bugs at once: draft them all, get one approval for the list, then
create them one by one.

## Resolving ("work on #N", "fix #N")

1. `gh issue view N --comments`. Restate the bug and confirm you can
   reproduce it (probe first when it is labelled `needs-probe`).
2. Fix it under the usual rules (AGENTS.md: tests on behaviour, ADR for
   new decisions, live check), then install and test.
3. Give the user a commit message ending in `Fixes #N`. The issue closes
   when they push to main. Don't commit or push yourself unless asked.
4. If the fix isn't possible (VP refuses, out of scope), draft a comment
   explaining why and suggest the `vp-behaviour` or `wontfix` label. Post
   it only on the user's OK.

## Reading

- What's open: `gh issue list --state open`, or add `--label bug`.
- One issue: `gh issue view N`.
- Reading needs no approval. Comments, labels, closing and reopening all
  publish something, so ask first each time.
