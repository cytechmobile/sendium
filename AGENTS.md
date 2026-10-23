# Repository Agent Instructions

These instructions apply to all work in this repository.

## Release Notes and Commit Messages

- Classify changes by the distributed artifact and behavior they affect, not merely whether the work introduces something new. Sendium application releases publish Maven artifacts and rebuild the JVM and native application images.
- Use `feat:` for new capabilities and `fix:` for meaningful corrections to the distributed Sendium application, including its runtime behavior, APIs, configuration, installation, dependencies, and packaging.
- Use `ci:` for workflow automation, benchmark orchestration, performance-harness capabilities, and CI diagnostics that do not change the distributed Sendium application, even when they add a new capability for maintainers. Such tooling-only changes should not independently trigger an application release or image rebuild.
- Use `test:`, `docs:`, `build:`, `refactor:`, or `chore:` when those more accurately describe the change. For changes affecting both the application and supporting tooling, choose the type based on the application impact and consolidate related supporting work into the same release entry.
- Do not relabel application changes to suppress releases. Conversely, do not classify tooling-only capabilities as application features merely because they are new. Review release automation when changing commit-type or versioning rules.
- Write application release notes around delivered capabilities and meaningful user or operator impact, not individual implementation tasks. Use one entry per cohesive application feature, consolidating its implementation steps, supporting documentation, and fixes made before its first release. Preserve distinct fixes to previously released behavior, breaking changes, and migration instructions.
- Include standalone documentation release notes only when they communicate a significant change for application users or operators; routine CI and benchmark documentation belongs with its tooling change.
- Before merging, review all commit messages that will land on `main`, including their bodies. Release Please can interpret additional conventional-commit messages in a body as separate changelog entries. Prefer a squash merge with one capability-level summary for a cohesive feature, and preserve the required DCO trailer.
- Before a release, review the generated changelog and consolidate redundant entries. Keep `CHANGELOG.md` and the release PR description consistent. Release Please may regenerate both, so verify the curated entries after its final run; do not assume a manual PR-description edit is persistent.

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
