# Repository Agent Instructions

These instructions apply to all work in this repository.

## Scope Ownership and Downstream Consumers

- Implement only behavior required by this repository's approved task. Do not expand scope based on features discovered in downstream consumers, related repositories, or hypothetical future integrations.
- A request to review compatibility or keep contracts reusable authorizes compatibility analysis and appropriate extension boundaries. It does not authorize implementing consumer-specific workflows, adapters, policies, or lifecycle behavior.
- Requirements belonging to another repository or team remain their responsibility. Record relevant findings and explain potential integration needs; do not implement them without explicit approval.
- Before adding behavior primarily motivated by another consumer, identify:
  1. Which current-task requirement needs it.
  2. Which repository or team owns that behavior.
  3. Whether the task can be completed without it.
  If it is not required for the approved task, defer it.
- Present any proposed scope expansion separately, with its motivation, implementation impact, and a recommendation. Wait for explicit approval before adding it to the implementation plan or writing code. General approval to proceed does not authorize an undisclosed expansion.
- Do not introduce speculative abstractions or implementation machinery merely to make future adoption easier. Prefer the smallest contract that satisfies the current task and leaves a clear extension path.
- When a missing prerequisite is discovered, distinguish work genuinely necessary to complete the approved task from optional support for another consumer. Do not describe optional consumer support as a required prerequisite.
- Apply this scope check when creating the plan, not only while coding. Clearly label cross-repository additions so they cannot enter scope through a broad task title or an assumed design decision.

## Commits

- Every commit must include a Developer Certificate of Origin `Signed-off-by:` trailer. Create commits with `git commit -s` and use the contributor's configured name and email.
- Cryptographic signing does not replace the DCO trailer. When a cryptographic signature is required, use both options: `git commit -S -s`.
- Before pushing, verify the commit message contains the expected `Signed-off-by:` trailer.

## Pull Requests

- A pull request may be opened only when its branch contains the latest `origin/main`.
- Immediately before opening a pull request, run `git fetch origin main` and `git rebase origin/main`.
- Verify `git rev-list --count HEAD..origin/main` returns `0`. Do not open the pull request if the branch is behind or the rebase is unresolved.
- Rebasing rewrites commits. Preserve all DCO trailers and recreate cryptographic signatures when required, for example with `git rebase --gpg-sign origin/main`.
- If a published branch must be updated after a rebase, use `git push --force-with-lease`, never `git push --force`.
