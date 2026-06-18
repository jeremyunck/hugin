# Workspace Skills

Hugin now scans repo-local skills from:

- `skills/**/SKILL.md`
- `docs/skills/**/SKILL.md`

When the agent has workspace tools, it is prompted to read a relevant `SKILL.md` before doing substantial work.

Use `skills/` for general reusable skills that should travel with the repository. Keep each skill in its own folder with a `SKILL.md` file containing a short front matter block:

```md
---
name: my-skill
description: One sentence saying when the skill should be used.
---
```
