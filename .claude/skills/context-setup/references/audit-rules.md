# Audit rules

Each section is a check to run by hand during an audit. Read only the sections whose condition you actually see.

## ROOT_TOO_LONG
Root CLAUDE.md/AGENTS.md is over the line limit (warn > 80, info > 50). It loads in every session.
Fix: move module-specific content into that module's CLAUDE.md; move topic rules into path-scoped `.claude/rules/`; replace long explanations with a pointer to a doc; delete generic advice.

## MODULE_TOO_LONG
A nested CLAUDE.md is over 100 lines. It loads whenever Claude touches that module.
Fix: keep commands, layout and pitfalls; move reference material (API lists, schemas, long how-tos) into a doc in the module and point to it.

## EAGER_IMPORT
An `@path` import pulls a large file (> 50 lines) into context whenever the importing file loads.
Fix: replace `@docs/x.md` with a plain sentence: "See docs/x.md when <situation>." Keep `@imports` only for small files that are needed in every session (e.g. `@AGENTS.md`).

## IMPORT_MISSING
An `@path` import points to a file that doesn't exist. It is silently useless.
Fix: correct the path or remove the line.

## UNSCOPED_RULE
A `.claude/rules/*.md` file has no `paths:` frontmatter, so it loads in every session.
Fix: if it applies only to part of the repo, add `paths:` globs. If it truly applies everywhere and is short, leave it — this is info only.

## DUPLICATE_CONTENT
The same line appears in more than one context file, so it is paid for twice when both load.
Fix: keep it in the most specific file that needs it (root if repo-wide, module file otherwise) and delete the others. Exception: a deliberate `AGENTS.md`/`CLAUDE.md` pair — use an import instead of copying.

## AGENTS_CLAUDE_DUPLICATE
Both AGENTS.md and CLAUDE.md exist at the same level with overlapping content.
Fix: make CLAUDE.md `@AGENTS.md` plus Claude-only notes.

## SKILL_TOO_LONG
A SKILL.md body is over 500 lines (warn) or 300 (info). The whole body loads when the skill triggers.
Fix: move detail into `references/` files and add a line in SKILL.md saying when to read each one.

## SKILL_DESCRIPTION
A skill's description is missing, very short (< 50 chars, will undertrigger), or long (> 1024 chars, always-loaded cost).
Fix: one or two sentences on what it does plus concrete trigger situations.

## GENERIC_ADVICE
Lines that look like advice Claude follows anyway ("write clean code", "follow best practices", "handle errors appropriately").
Fix: delete them, or replace with the specific, non-obvious rule the author actually meant.

## LARGE_DIR_NOT_DENIED
A large generated/vendored directory (node_modules, build, dist, target, .venv, generated, vendor) exists and is not in a `permissions.deny` Read rule.
Fix: suggest the deny rules from "Excluding paths" in `templates.md`, after checking Claude doesn't need to read those files. Info only.

## NO_ROOT_FILE
No CLAUDE.md or AGENTS.md at the repo root.
Fix: switch to the Setup workflow.
