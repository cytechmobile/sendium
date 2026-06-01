# Release Process

Sendium releases are automated with [Release Please](https://github.com/googleapis/release-please). Developers only need to use clear Conventional Commit messages and merge the generated release pull request when a release is ready.

## Workflow Summary

The release workflow is defined in `.github/workflows/release-please.yml` and runs on every push to `main`.

On each run, the workflow:

1. Starts the `googleapis/release-please-action@v5` action.
2. Reads `release-please-config.json` for the Maven release configuration.
3. Reads `.release-please-manifest.json` for the current released version.
4. Creates or updates a release pull request when releasable changes exist.
5. Creates the GitHub release and tag after the release pull request is merged.
6. Checks out the released commit SHA.
7. Sets up GraalVM Java 25.
8. Publishes Maven artifacts to GitHub Packages with `./mvnw -B deploy -DskipTests`.
9. Publishes JVM and native Docker images to Docker Hub.

## Developer Responsibilities

Use Conventional Commit messages in PR titles and commits. Release Please uses these messages to decide whether a release is needed and what version bump to apply.

The preferred format is:

```text
type(scope): short description
```

The scope is optional in the Conventional Commits specification, but Sendium contributors should include it when it helps reviewers understand the affected area. Good scopes include `http`, `smpp`, `routing`, `webhooks`, `docker`, `docs`, `ci`, `deps`, and `release`.

| Commit Type | Scoped Example | Release Impact |
| :--- | :--- | :--- |
| `feat` | `feat(routing): add rule validation` | Minor release |
| `fix` | `fix(smpp): resolve reconnect failure` | Patch release |
| `perf` | `perf(http): reduce submit latency` | Patch release |
| `refactor` | `refactor(webhooks): simplify DLR payload mapping` | No release by default |
| `docs` | `docs(release): explain Docker image publishing` | No release by default |
| `test` | `test(routing): cover fallback rule matching` | No release by default |
| `build` | `build(maven): update compiler plugin` | No release by default |
| `ci` | `ci(docker): publish versioned images on release` | No release by default |
| `chore` | `chore(deps): refresh development tooling` | No release by default |
| `style` | `style(core): format imports` | No release by default |
| `revert` | `revert(smpp): remove invalid reconnect change` | No release by default |
| Breaking change marker | `feat(routing)!: change routing config format` | Major release |
| Breaking change footer | `feat(routing): rename routing config keys` with `BREAKING CHANGE: routing config keys were renamed` | Major release |

Prefer PR titles that describe the user-visible change. If a PR contains multiple commits, the squash commit title should still follow Conventional Commits because that is what lands on `main`.

## Release Pull Request

When releasable commits are merged into `main`, Release Please creates or updates a release PR. That PR usually contains:

- `CHANGELOG.md` updates generated from commit messages.
- Maven version changes from `-SNAPSHOT` to the release version.
- `.release-please-manifest.json` version updates.

Review the release PR like any other PR. Confirm the changelog entries and version are correct, then merge it when the release should be published.

Do not manually edit `CHANGELOG.md`, Maven version fields, or `.release-please-manifest.json` during normal feature work. Those files are owned by Release Please unless the release state needs an intentional repair.

It is normal for `.release-please-manifest.json` to show the latest released version while the Maven POMs on `main` have already moved to the next `-SNAPSHOT` development version. Do not force those values to match.

## Maven Publishing

After the release PR is merged, Release Please creates the GitHub release and sets `release_created=true`. The workflow then runs the publishing steps.

Publishing uses:

- Java distribution: `graalvm`
- Java version: `25`
- Maven command: `./mvnw -B deploy -DskipTests`
- Maven repository id: `github`
- Repository URL from `pom.xml`: `https://maven.pkg.github.com/cytechmobile/sendium`

The workflow uses `GITHUB_TOKEN` for Maven publishing. The `pom.xml` `distributionManagement` section controls where artifacts are deployed.

## Docker Publishing

After Maven publishing succeeds, the release workflow calls both Docker workflows:

- `.github/workflows/docker.yml` builds the JVM image.
- `.github/workflows/dockerNative.yml` builds the native image.

Both workflows can still be started manually with `workflow_dispatch`, but they also support `workflow_call` so the release workflow can reuse the same build logic.

Release builds use the exact commit SHA reported by Release Please. The `version` input is expected to be the Release Please tag, such as `v0.2.1`. For that release, the workflows publish:

| Image Type | Tags |
| :--- | :--- |
| JVM | `cytechmobile/sendium:v0.2.1`, `cytechmobile/sendium:0.2.1`, `cytechmobile/sendium:latest` |
| Native | `cytechmobile/sendium:v0.2.1-native`, `cytechmobile/sendium:0.2.1-native`, `cytechmobile/sendium:latest-native` |

Manual Docker workflow runs without a version input publish only the moving `latest` or `latest-native` tag.

Docker publishing requires these repository secrets:

- `DOCKERHUB_USERNAME`
- `DOCKERHUB_TOKEN`

## Tokens And Permissions

Release Please uses a GitHub App installation token generated by `actions/create-github-app-token`. The workflow expects these repository or organization values:

- `APP_ID` as a repository variable or secret.
- `APP_PRIVATE_KEY` as a repository secret.

The installed GitHub App must have access to the repository. The workflow requests these permissions for the generated app token:

- `Contents: Read and write` to create branches, tags, releases, and version commits.
- `Pull requests: Read and write` to create and update the release PR.
- `Issues: Read and write` for Release Please labels and PR comments.
- `Metadata: Read-only`, which GitHub grants automatically.

`Workflows: Read and write` is only needed if release automation must update files under `.github/workflows/`.

The workflow grants these `GITHUB_TOKEN` permissions:

- `contents: read` for checkout and repository reads.
- `packages: write` on the release job so Maven can publish artifacts to GitHub Packages after a release is created.

Release PR creation, tag creation, release creation, and Release Please issue/PR metadata use the GitHub App token, not the workflow `GITHUB_TOKEN`.

The release PR commit is authored by the GitHub App identity. If DCO checks fail, inspect the generated release PR commit author and configure DCO or Release Please signoff to match that identity.

## Troubleshooting

If no release PR appears after merging to `main`, check:

- The merged commit or squash commit uses a releasable Conventional Commit type such as `fix:` or `feat:`.
- The workflow ran successfully in GitHub Actions.
- `release-please-config.json` still points to the Maven release type.
- `.release-please-manifest.json` contains the expected current version.
- The GitHub App is installed on this repository.
- `APP_ID` and `APP_PRIVATE_KEY` are configured correctly.
- The GitHub App permissions allow creating branches and pull requests.

If artifacts are not published after merging the release PR, check:

- The Release Please step created a release and set `release_created=true`.
- The checkout step used `steps.release.outputs.sha`.
- Java 25 setup completed successfully.
- `./mvnw -B deploy -DskipTests` passed.
- GitHub Packages permissions allow writes for this repository.

If Docker images are not published after a release, check:

- Maven publishing succeeded first.
- `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` are configured.
- The called Docker workflow received the Release Please `sha` and `tag_name` outputs.
- Docker Hub permissions allow pushing to `cytechmobile/sendium`.
