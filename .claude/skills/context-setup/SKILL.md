---
name: context-setup
description: Sets up and audits token-efficient agent context for a repository — CLAUDE.md / AGENTS.md files, per-module memory files, .claude/rules, and skills. Use this whenever the user wants to create or initialize CLAUDE.md or AGENTS.md, organize instructions for a monorepo or multi-module project, reduce token usage or context size, split or shrink memory files, make skills smaller, or check whether existing context files have grown too large — even if they just say "my CLAUDE.md is a mess" or "Claude Code is burning tokens".
---

# Context Setup & Audit

Every line in an always-loaded file is paid for on every request. The goal is a small root file with pointers, module details that load only when Claude works in that module, and skills that load only when relevant.

Pick the mode from the user's request:

- **Setup** — the repo has no CLAUDE.md/AGENTS.md, or the user wants a fresh structure.
- **Audit** — context files exist; the user wants them checked, trimmed or reorganized.

If unclear, inventory the context files first (step 1 of the audit): that shows which situation you're in.

## How Claude Code loads context (the rules everything below follows)

- Root `CLAUDE.md` and every `CLAUDE.md` in parent directories of the cwd load at startup.
- `CLAUDE.md` files in subdirectories load **only when Claude reads files in that subtree**. This is the main lever for multi-module projects.
- `@path/to/file` imports are expanded when the importing file loads, so `@README.md` in the root file puts the whole README in every session. Use a plain-text pointer ("See docs/architecture.md for …") when the file is only sometimes needed.
- `.claude/rules/*.md` load at startup unless they have `paths:` frontmatter, which limits them to matching files.
- Skills: only `name` + `description` are always loaded; the SKILL.md body loads when the skill triggers; files in `references/` load only when read.

## Setup workflow

1. **Map the repo.** List top-level directories and build files (package.json, pom.xml, build.gradle, Cargo.toml, go.mod, pyproject.toml, *.sln, etc.) to identify modules. Read the README and any existing docs briefly — skim, don't load everything.
2. **Collect the essentials** per module: build/test/lint commands, entry points, conventions that differ from the rest of the repo, and known pitfalls. Ask the user for pitfalls you can't infer; these are the most valuable lines.
3. **Write the root file** using the root template in `references/templates.md`. Target ≤ 50 lines, hard ceiling 80.
4. **Write one CLAUDE.md per module** that has its own commands or conventions, using the module template. Target ≤ 60 lines. Skip modules with nothing specific to say.
5. **Move topic rules** (testing, security, style) that apply to only part of the repo into `.claude/rules/<topic>.md` with `paths:` frontmatter.
6. **Handle AGENTS.md** if the team uses other agents: keep the shared content in `AGENTS.md` and make `CLAUDE.md` a one-line `@AGENTS.md` import plus any Claude-only notes, so there is a single source of truth.
7. **Suggest exclusions** for large generated or vendored directories (see "Excluding paths" in `references/templates.md`).
8. **Run the audit** (below) on the result and fix any warnings before reporting.

Show the user the planned file list before writing files if the repo has more than ~5 modules or already has context files you'd change.

## Audit workflow

1. Inventory the context files: every `CLAUDE.md`/`AGENTS.md` (root and nested, including `.claude/CLAUDE.md`), `.claude/rules/*.md`, and `SKILL.md`. Use Glob to find them and `wc -l` for line counts. Estimate tokens as characters ÷ 4.
2. Estimate startup cost: root files + rules without `paths:` frontmatter + skill descriptions + any `@imports`. Skill bodies and nested CLAUDE.md files load on demand and don't count.
3. Check each file against `references/audit-rules.md` (each section states its threshold and fix) and read only the sections that apply.
4. Propose concrete changes: what to move where, what to delete, which `@import` to turn into a pointer. Show before/after startup token estimates.
5. Apply changes only after the user agrees, unless they asked you to just fix it. Then recount to confirm.

The thresholds are heuristics. Use judgment: an 85-line root file in a huge monorepo can be fine; a 40-line one full of generic advice is not.

## What to cut (applies to both modes)

Delete lines that Claude follows without being told: "write clean code", "handle errors properly", "use meaningful names", restated language conventions, and long explanations of well-known frameworks. Keep lines that change behavior: exact commands, non-obvious conventions, "never do X here because Y", and directory maps. Prefer one short example over a paragraph of description.

## Reporting

End with a short summary: files created or changed, startup token estimate before and after, and anything left for the user to decide.
