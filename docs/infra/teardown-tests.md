# Teardown tests: the record

The tests come from [`teardown-design.md`](teardown-design.md) §5. This file records each run with its date, who ran it, the run URL or command, and the result. FT-3 adds the rows for T4 onward.

| # | Test | Status |
|---|---|---|
| T1 | Unit tests for `decide()` | Automated: `teardown-tests` in `infra.yml`, on every PR |
| T2 | Static checks: `actionlint` and `teardown/tests/test_workflows_static.py` | Automated: the `actionlint` and `teardown-tests` jobs in `infra.yml`, on every PR |
| T3 | Environment check (the preflight) | **Arlo's, pending.** Runbook below |
| T4–T7 | Canary, reaper, IAM simulation, fault injection, first session | FT-3. These need the AWS account |

## T3: the environment check (Arlo's, pending)

**Pass condition (TD §5):** the first run passes. The second, with a required reviewer on `lab-teardown`, **fails before any AWS call**. Then remove the reviewer.

`--github-only` runs preflight steps 1–4 (`LAB_ENABLED`, the environment, re-enabling `lab-teardown.yml`, no active teardown run). It never creates an AWS client, so a failure is by construction before any AWS call. It costs $0 and needs no AWS account.

Run it locally, from `infra/`, in the teardown venv (see `infra/teardown/README.md`), logged in to `gh` as `firegiant9000`. `LAB_ENABLED` is passed on the command line, so the repository variable stays off and the hourly teardown keeps skipping.

1. **Create the `lab-teardown` environment** (Settings → Environments → New environment):
   - no required reviewers;
   - no wait timer;
   - deployment branches and tags: **Selected branches and tags**, with exactly one rule, the branch `main`.
2. **Create `LAB_PREFLIGHT_TOKEN`:** a fine-grained PAT for `firegiant9000/FairTix` only, with a short expiry (scopes: [`README.md`](README.md#lab_preflight_token-secret)). Store it as an environment secret on `lab-apply`, and keep a copy only for this local run.
3. **Run 1, which should pass:**
   ```sh
   LAB_ENABLED=true LAB_PREFLIGHT_TOKEN=<the token> python -m teardown.preflight --github-only
   ```
   It should end with `preflight: GitHub checks passed (--github-only: no AWS call made)` and exit 0. To keep the token out of shell history, read it into the variable from a prompt (`read -s`) rather than typing it inline.
4. **Add yourself as a required reviewer** on `lab-teardown`, then **run 2, which should fail:** run the same command again. It should print `preflight FAILED at lab-teardown environment: has required reviewers; ...` and exit 1.
5. **Remove the reviewer straight away.** While it's set, every scheduled teardown run would wait for approval, though they skip anyway while `LAB_ENABLED` is off. Then run 1 again to confirm it passes.
6. **Record the results** in the table below.

| Date | Run | Command | Exit | Output (last line) | By |
|---|---|---|---|---|---|
| | 1 (no reviewer) | `--github-only` | | | |
| | 2 (reviewer added) | `--github-only` | | | |
| | 3 (reviewer removed) | `--github-only` | | | |

**Afterwards:** T3 also runs on every apply, because it *is* the preflight (TD §5, "Re-test"). The workflow runs `--github-only` before OIDC, then the full preflight after it.
