---
name: explore-github-repository
description: Use when starting work in an unfamiliar repository with limited context. Helps the agent orient itself quickly, avoid huge file dumps, and move from repo structure to the exact files worth reading.
---

# Explore A GitHub Repository

Use this skill when the user asks you to debug, change, review, or understand a repository you have not already mapped out in the current conversation.

## Goal

Build working context quickly without flooding yourself with irrelevant files or giant tool outputs.

## Workflow

1. Start with focused discovery, not a full recursive tree.
2. Identify the likely app areas, entrypoints, and configuration files.
3. Narrow to the exact files that matter.
4. Read only the sections you need before editing.

## Recommended Tool Order

1. `repo_index`:
   Use `build` if needed, then `search` for the feature, screen, endpoint, model, class, or filename the user mentioned.
2. `find_path`:
   Use for approximate filenames, directory names, or path fragments.
3. `find_files`:
   Use for glob searches like `*.tsx`, `pom.xml`, or `src/**/*.java`.
4. `grep_search`:
   Use to find exact strings, labels, routes, CSS classes, errors, or method names.
5. `list_files`:
   Prefer shallow directory listings. Only recurse after narrowing to one subtree.
6. `read_file`:
   Read the exact file you found. For large files, use `start_line` and `line_count`.

## Good First Targets

Look for:

- `README*`
- package manifests like `package.json`, `pom.xml`, `Cargo.toml`
- app entrypoints
- routing files
- obvious feature names from the user prompt
- tests that already cover the area

## Avoid

- recursive `list_files` on the repo root unless you truly need it
- reading an entire giant file when a targeted range would do
- assuming the path in the user prompt is exact
- editing before you have found the real implementation file

## Exit Criteria

Before changing code, you should know:

- which subproject owns the behavior
- which files define the current implementation
- which test or verification path should confirm the fix
