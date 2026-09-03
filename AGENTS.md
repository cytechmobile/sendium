# Repository Agent Instructions

These instructions apply to all work in this repository.

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
