# docs/infra

The L3 lab's infrastructure docs.

| Doc | What it covers |
|---|---|
| [`teardown-design.md`](teardown-design.md) | The three-layer teardown design (TD) |
| [`teardown-tests.md`](teardown-tests.md) | The T1–T7 test record, including the T3 runbook |
| [`../../infra/teardown/README.md`](../../infra/teardown/README.md) | The teardown code, its tests and the bootstrap naming contract |

## Workflows

| Workflow | Trigger | What it does |
|---|---|---|
| `.github/workflows/lab-teardown.yml` | Hourly at :17, plus `workflow_dispatch` (`force`) | Layer 2 (and, with `force`, layer 1): `teardown.decide`, then `terraform destroy`, `teardown.verify` and an expiry reset. On failure it opens or updates the issue "lab teardown failed" (label `lab-teardown`) |
| `.github/workflows/lab-apply.yml` | `workflow_dispatch` only (`ttl_hours`, default 4, maximum 12) | `teardown.preflight`, then `terraform apply`. Until FT-3 adds `infra/lab`, it fails with "lab stack not present" |
| `.github/workflows/infra.yml` | Every PR and push to `main`/`develop` | T1 and T2 (`teardown-tests`) and `actionlint` |

## GitHub settings the lab depends on

**Arlo creates all of these.** None exists until they do, and no value belongs in the repo.

### `LAB_ENABLED` (repository variable)

- **What it does:** the on/off switch for the lab (FT-7).
  - `lab-teardown.yml` runs its job only when it is `'true'`. Otherwise the hourly run shows the job as **skipped**, not failed, and opens no issue.
  - `teardown.preflight` (in `lab-apply.yml`) **fails** unless it is exactly `true`, so no apply can start while the lab is off.
- **Where:** a **repository** variable (Settings → Secrets and variables → Actions → Variables), not an environment variable. The teardown job's `if:` is evaluated before the job enters its environment, so an environment variable would never be seen there. `lab-apply.yml` reads the variable in a `gate` job that has no environment, so an environment-level `LAB_ENABLED` on `lab-apply` can't let apply through while teardown skips.
- **Who sets it, and when:** Arlo, once FT-3 has built the AWS side (bootstrap, roles, `infra/lab`). Until then, the scheduled teardown has nothing to act on: it **protects nothing** yet.
- **Case:** GitHub compares strings in `if:` case-insensitively, so `TRUE` enables teardown, but the preflight accepts only lowercase `true`. Use `true`.

### `LAB_PREFLIGHT_TOKEN` (secret)

- **What it does:** lets the preflight read the `lab-teardown` environment's protection rules and its deployment-branch policies (FT-8). The preflight fails if it is unset; it never falls back to another token.
- **What it is:** a fine-grained personal access token that Arlo creates, limited to `firegiant9000/FairTix`, with a short expiry.
  - **Permissions (FT-8):** read access to administration/environments, and write access to actions.
  - **Narrower may be enough:** GitHub's REST docs say anyone with read access to the repository can call both environment endpoints. The workflow re-enable and run listing use the job's own `GITHUB_TOKEN` (`actions: write`), not this token. T3 run 1 proves whichever scopes Arlo picks.
- **Where:** an environment secret on **`lab-apply`**, so only the apply job can read it.
- **Never** paste its value into an issue, PR, log or doc. The preflight passes it to `gh` as `GH_TOKEN` in the child process's environment, never on the command line.

### Created in FT-3 (named here so the workflows can reference them)

| Name | Kind | Holds |
|---|---|---|
| `LAB_TEARDOWN_ROLE_ARN` | Secret on `lab-teardown` | The `lab-teardown` IAM role ARN that OIDC assumes |
| `LAB_APPLY_ROLE_ARN` | Secret on `lab-apply` | The `lab-apply` IAM role ARN |
| `lab-teardown` environment | Environment | No required reviewers, no wait timer, deployment branches: custom policy `main` only |
| `lab-apply` environment | Environment | Optional owner approval; deployment branches: `main` only |

The role ARNs are secrets rather than variables because they contain the account id and the repo is public. Secrets are masked in logs. `configure-aws-credentials` also runs with `mask-aws-account-id: true`.

## Known limitation: turning `LAB_ENABLED` off while a lab exists

If `LAB_ENABLED` is set to anything other than `true` while lab resources are still up, **layer 2 stops**: every hourly run skips, and nothing tears the lab down from GitHub.

- **The backstop is the layer-3 reaper** (FT-3, AWS-side). It doesn't read `LAB_ENABLED` and deletes the billable resources 2 hours past expiry.
- **Until FT-3 builds the reaper, there is no backstop.** So: destroy first (`lab-teardown.yml` with `force: true`, then confirm that `teardown.verify` is empty), **then** turn `LAB_ENABLED` off.
