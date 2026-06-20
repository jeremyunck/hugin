# Agent Workflow

Codex agents working in this repository should always create a pull request when they complete a feature.
Any UI change must include at least one current screenshot of the changed state in the pull request.
When a task updates specific screens, agents should capture screenshots of each specific updated screen after the work is complete, not just a generic app shell.
Screenshots must show the actual functionality changed by the feature so reviewers can verify the behavior or UI delta directly from the pull request.

## Screenshot Login

Use the seeded screenshot test account when verifying authenticated UI flows and capturing screenshots.
The account is only created when `AUTH_TEST_USER_PASSWORD` is set (there is no default, so the public
build never ships a known credential). Set `AUTH_TEST_USER_USERNAME` and `AUTH_TEST_USER_PASSWORD` in
the environment and read the credentials from there before logging in.
Agents should use that account for post-change UI screenshots unless the task specifically requires a different user state.

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
10. Once the reviewer rating is at least `4/5`, wait for checks to finish and confirm there are no critical-level concerns left in review.
11. Merge once those conditions are satisfied.
