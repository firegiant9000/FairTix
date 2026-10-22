# Teardown tests: the record

The tests come from [`teardown-design.md`](teardown-design.md) §5. This file records each run with its date, who ran it, the run URL or command, and the result. FT-3 adds the rows for T4 onward.

| # | Test | Status |
|---|---|---|
| T1 | Unit tests for `decide()` | Automated: `teardown-tests` in `infra.yml`, on every PR |
| T2 | Static checks: `actionlint` and `teardown/tests/test_workflows_static.py` | Automated: the `actionlint` and `teardown-tests` jobs in `infra.yml`, on every PR |
| T3 | Environment check (the preflight) | **Passed 2026-10-20** (Arlo). Record below |
| T4 | Canary end to end, destroyed by a **scheduled** run | FT-3 stage A. Runbook below; pending |
| T5 | The reaper acts with `lab-teardown.yml` disabled | FT-3 stage A. Runbook below; pending |
| T5b | Reaper (and lab role) IAM simulation | FT-3 stage A. Runbook below; pending |
| T6 | Fault injection: `teardown.verify` flags resources made outside Terraform | FT-3 stage A. Runbook below; pending |
| T7 | First real session | FT-3 stage B |

## T3: the environment check (passed 2026-10-20)

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
| 2026-10-20 | 1 (no reviewer) | `--github-only` | 0 | `preflight: GitHub checks passed (--github-only: no AWS call made)` | Arlo |
| 2026-10-20 | 2 (reviewer added) | `--github-only` | 1 | `preflight FAILED at lab-teardown environment: has required reviewers; teardown would wait for approval` | Arlo |
| 2026-10-20 | 3 (reviewer removed) | `--github-only` | 0 | `preflight: GitHub checks passed (--github-only: no AWS call made)` | Arlo |

**Result: T3 passes.**
- Run 1 passed all four checks.
- Run 2 stopped at check 2, the `lab-teardown` environment. `--github-only` never creates an AWS client, so it stopped before any AWS call.
- Run 3 passed once the reviewer was removed.
- The runs were local, from `caf48aa` (its `infra/` and `.github/` are identical to `main` at `8f22b8e`), with a fine-grained `LAB_PREFLIGHT_TOKEN` limited to this repository. The repository variable `LAB_ENABLED` stayed off throughout.
- The workflow path, where the job's `GITHUB_TOKEN` does the re-enable and the run checks, is first exercised by the first real apply in FT-3.

**Afterwards:** T3 also runs on every apply, because it *is* the preflight (TD §5, "Re-test"). The workflow runs `--github-only` before OIDC, then the full preflight after it.

## T4–T6: the AWS-side tests (FT-3 stage A)

**Order:** T5b, then T6, can run as soon as bootstrap is applied. **T4 and T5 need the FT-3 stage A PR merged:** scheduled runs only run from `main`, and the teardown role's trust pins `lab-teardown.yml@refs/heads/main`, so the canary destroy must be on `main`. TD §5: each test passes before the next, and T1–T6 all pass before the first full `apply` (stage B).

**Common setup.** Arlo runs these locally, from `infra/`, in the teardown venv, with `$env:AWS_PROFILE = "fairtix-lab-admin"` after `aws login --profile fairtix-lab-admin`. For anything that runs `terraform` against `infra/canary` (an S3 backend), export short-lived credentials first (`infra/bootstrap/README.md`, "Before the first apply"). Before each test, `python -m teardown.verify` must report `no lab resources found`. After each test, it must report that again.

**Never record** account ids, ARNs, the alert email or Terraform output here. Run URLs, times, exit codes and the last line of output are enough.

### Prerequisites (Arlo)

1. Bootstrap applied (`infra/bootstrap/README.md`), and the SNS subscription confirmed.
2. The GitHub settings in [`README.md`](README.md#github-settings-the-lab-depends-on): `lab-teardown` with `LAB_TEARDOWN_ROLE_ARN`; `lab-apply` with `LAB_APPLY_ROLE_ARN` and `LAB_PREFLIGHT_TOKEN`.
3. For T4 and T5: the stage A PR merged, and the repository variable `LAB_ENABLED` = `true` (FT-7: from now on, while any lab or canary can exist).

### T5b: IAM simulation ($0)

```powershell
python -m teardown.simulate
```

**Pass:** exit 0 and `teardown.simulate: PASS`. It checks every action each role calls (allowed) and the same actions outside the lab: another name, a missing tag, the bootstrap state key (denied). A `FAIL` on an allowed case means a grant is missing: **fix it narrowly in `infra/bootstrap`, never with `*`**, re-apply, re-run. Run it again with `--test-mode` during T5, and once more after T5 without it.

### T6: fault injection (about $0)

1. Create two resources outside Terraform, in the default VPC:
   ```powershell
   aws logs create-log-group --log-group-name fairtix-lab-t6-stray
   $vpc = aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query "Vpcs[0].VpcId" --output text
   aws ec2 create-security-group --group-name fairtix-lab-t6-stray --description "T6 stray" --vpc-id $vpc --query GroupId --output text
   ```
2. `python -m teardown.verify` → **pass:** exit 1, and the output lists `log_group fairtix-lab-t6-stray` and `security_group sg-... (fairtix-lab-t6-stray)`.
3. Delete both by hand (`aws logs delete-log-group ...`, `aws ec2 delete-security-group --group-id ...`), then `teardown.verify` is empty again.

### T4: the canary, destroyed by a scheduled run (about $0)

1. Write the expiry, 10 minutes ahead (the preflight's job in a real apply):
   ```powershell
   $expires = (Get-Date).ToUniversalTime().AddMinutes(10).ToString("yyyy-MM-ddTHH:mm:ssZ")
   aws ssm put-parameter --name /fairtix-lab/expires_at --type String --overwrite --value $expires
   ```
2. Apply the canary: `terraform -chdir=canary init -lockfile=readonly`, then `terraform -chdir=canary apply`.
3. `python -m teardown.verify` → exit 1, listing the canary's VPC, security group and log group. This proves the census sees it.
4. **Don't dispatch anything.** Wait for the next scheduled `lab-teardown` run (hourly at :17) after the expiry. Watch it in Actions: it must never show "Waiting".
5. **Pass:** within 75 minutes of the expiry, a **scheduled** run (event `schedule`) decides `destroy`, destroys the canary, `teardown.verify` in the job passes, and the expiry is reset to `none`. Locally, `teardown.verify` is empty.
6. Record the run URL, the expiry, the run's start time and the delay between them.

### T5: the reaper, with the workflow disabled (about $0)

1. Put the reaper in test mode, then simulate it:
   ```powershell
   terraform -chdir=bootstrap apply -var reaper_test_mode=true -var reaper_grace_minutes=5
   python -m teardown.simulate --test-mode
   ```
2. Write the expiry 10 minutes ahead (T4 step 1), and apply the canary (T4 step 2).
3. **Then** disable layer 2: `gh workflow disable lab-teardown.yml --repo firegiant9000/FairTix`.
4. Wait. **Pass:** within 40 minutes of the expiry, the reaper's run logs (`/aws/lambda/fairtix-lab-bootstrap-reaper`) show `decision destroy`, then `called ec2.delete_security_group ...` and `called ec2.delete_vpc ...`. The SNS email "fairtix-lab reaper acted" arrives.
5. **Re-enable layer 2 straight away:** `gh workflow enable lab-teardown.yml --repo firegiant9000/FairTix`, and check it reports `active`.
6. Clean up what the reaper leaves by design (the log group and the SSM parameter): dispatch `lab-teardown` with `force: true` (layer 1), then `teardown.verify` is empty.
7. Take the reaper out of test mode: `terraform -chdir=bootstrap apply` (no `-var`), then `python -m teardown.simulate` (its canary actions must now be denied).
8. Record the reaper run's time, the delay from the expiry, the email's arrival time and the re-enable time.

### Results

| Test | Date | By | Evidence (run URL, times) | Result |
|---|---|---|---|---|
| T5b | | | | pending |
| T6 | | | | pending |
| T4 | | | | pending |
| T5 | | | | pending |
| T5b (test mode, then after T5) | | | | pending |

**The next day:** Arlo checks Cost Explorer for nothing accruing (TD §8). The L3 acceptance check is at T + 24 h and T + 48 h.
