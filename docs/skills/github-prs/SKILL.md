---
name: github-prs
description: Use when working in this repo and needing to push a branch, create a GitHub pull request, or include screenshots for UI changes. Covers `gh` authentication, PR creation, and how to add current images to the PR body.
---

# GitHub PR Workflow

Use this workflow when repo changes are ready to publish as a GitHub pull request.

## Preconditions

- Work from the repo root.
- Make sure the target branch is committed locally.
- Push the branch before opening the PR.
- Prefer `gh` over manual browser steps when authentication is available.

## Push The Branch

Check state first:

```bash
git status --short
git branch --show-current
```

If needed, create a branch and commit:

```bash
git checkout -b <branch-name>
git add <files>
git commit -m "<commit message>"
```

Push it:

```bash
git push origin <branch-name>
```

If HTTPS auth is unavailable but SSH works, push with the SSH remote form:

```bash
git push git@github.com:<owner>/<repo>.git <branch-name>
```

## Authenticate `gh`

Check whether `gh` is installed:

```bash
which gh
```

If missing on macOS with Homebrew:

```bash
brew install gh
```

Authenticate with GitHub and keep git protocol on SSH:

```bash
gh auth login --hostname github.com --git-protocol ssh --web
```

If device flow is used, give the user the URL and one-time code. Wait for confirmation that login is complete before continuing.

Verify auth:

```bash
gh auth status
```

## Create The PR

Use `gh pr create` with an explicit base branch, title, and body:

```bash
gh pr create \
  --base main \
  --head <branch-name> \
  --title "<pr title>" \
  --body-file - <<'EOF'
## Summary
- change 1
- change 2

## Verification
- command output summary
EOF
```

If the repo default branch is not `main`, replace it with the correct base.

After creation, return the PR URL to the user.

## UI Change Requirement

Every UI change in this repo must include at least one current screenshot in the PR.

- If the change affects mobile only, include a mobile screenshot.
- If the change affects desktop only, include a desktop screenshot.
- If the change is responsive or affects both, include both mobile and desktop screenshots.

## Create Screenshot Files

Store PR screenshots in a stable repo path so they can be referenced from the branch:

```bash
mkdir -p docs/pr-screenshots
```

Suggested naming:

- `docs/pr-screenshots/mobile-<short-description>.png`
- `docs/pr-screenshots/desktop-<short-description>.png`

Commit the screenshot files with the code changes.

## Add Images To The PR Body

For screenshots committed on the branch, use raw GitHub URLs in the PR body:

```md
## Screenshots
### Mobile
![Mobile state](https://raw.githubusercontent.com/<owner>/<repo>/<branch-name>/docs/pr-screenshots/mobile-<short-description>.png)

### Desktop
![Desktop state](https://raw.githubusercontent.com/<owner>/<repo>/<branch-name>/docs/pr-screenshots/desktop-<short-description>.png)
```

This is the simplest approach when using `gh pr create` from the terminal because the images are already in the branch and render directly in the PR description.

## Fallbacks

- If `gh` is unavailable and no GitHub API token is available, push the branch and provide the compare URL:

```text
https://github.com/<owner>/<repo>/compare/<base>...<branch>?expand=1
```

- Plain git cannot create a GitHub PR object by itself.
- SSH access is enough for `git push`, but not enough to create a PR without `gh` auth or a GitHub API token.
