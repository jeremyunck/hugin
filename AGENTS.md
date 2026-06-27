# Agent Workflow

Codex agents working in this repository should always create a pull request when they complete a feature.
Screenshots of changed UI are captured automatically as part of the pull request checks, so agents do not need to capture or attach them manually.

Agents working in this repository should follow this workflow:

1. Make sure the local `main` branch is checked out and up to date with `origin/main`.
2. Create a branch for the task from that updated `main`.
3. Complete the feature and test it.
4. Create a pull request in `ready for review` state.
5. After creating the pull request, poll the PR every 20 seconds for reviewer comments until the reviewer comment check is finished, up to 1 minute total.
6. Address all requested review changes and major concerns.
7. Push the update.
8. After each PR update, poll the PR every 20 seconds for reviewer comments until the reviewer comment check is finished, up to 1 minute total, then check the current PR rating.
9. Continue that fix, push, and re-check cycle until the reviewer gives the PR a rating of at least `4/5`.
10. Once the reviewer rating is at least `4/5`, poll the PR every 1 minute until all required checks finish.
11. Merge once those conditions are satisfied.
