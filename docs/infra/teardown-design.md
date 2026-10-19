# FairTix L3 lab: teardown system design (2026-10-19)

**Status: design, 2026-10-19. Nothing is built yet.** This is the cost control for L3 in [`STRATEGIC_ROADMAP.md`](../../STRATEGIC_ROADMAP.md), and it replaces L3b's original "experiment guard".

**Why it carries the weight:** the AWS Free plan is for new AWS customers only, so the lab account will probably be on the Paid plan. That leaves no hard spending cap, and budget data arrives 8–24 hours late. Teardown that is reliable and tested is the cap.

Facts used here (GitHub and AWS docs, checked 2026-10-19):
- FairTix is a **public** repo. Environments are available at no cost, and scheduled workflows are **disabled after 60 days without repository activity**.
- A job that references an environment with **required reviewers** waits for approval, **up to 30 days**. Nothing in the docs exempts scheduled runs. An environment **wait timer** also delays the job (1 minute to 30 days).
- Scheduled runs only run on the default branch, and they "can be delayed" under load. Some queued jobs "may be dropped".
- When a job references an environment, the OIDC `sub` is `repo:OWNER/REPO:environment:NAME`, with no `ref` part.
- A concurrency group allows at most one running and one pending run. A newer pending run cancels the older pending one.
- Budgets updates up to 3 times a day, 8–12 hours apart. Cost Explorer refreshes at least every 24 hours, sometimes later. Budget actions can stop EC2 and RDS instances only; they can't touch ECS, ALB or ElastiCache.

## 1. Answer first

- **Three independent layers, so no single failure leaves the lab running:**
  1. A manual `destroy` at the end of each session.
  2. An hourly GitHub teardown workflow.
  3. An AWS-side reaper (EventBridge Scheduler → Lambda) that doesn't depend on GitHub at all.
- **Environment approvals don't block teardown,** because:
  - the teardown job uses its own environment, `lab-teardown`, with **no required reviewers and no wait timer**, deployable only from `main`;
  - a preflight in the apply workflow **refuses to create resources** if that environment ever gains reviewers or a timer;
  - the reaper (layer 3) doesn't use GitHub, so it isn't affected even if the preflight is bypassed.
- **Fail-safe on missing state:** the expiry lives in AWS (an SSM parameter) and is written **before** `terraform apply`. If it's missing or unreadable while lab resources exist, both layers treat the lab as **expired**.
- **Verification doesn't trust Terraform state or tags.** A per-service listing script is the source of truth, and the next-day Cost Explorer check confirms it.
- **The layer-3 reaper is the L3b redesign.** It gives the Lambda/EventBridge evidence L3b was for, and it actually removes the residual charges (ALB, IPv4, ElastiCache) that the old guard left running.
- **Test cost:** the end-to-end test runs on a canary stack built only from free resources: about $0.

## 2. Components

| Component | Lives in | Destroyed by lab teardown? |
|---|---|---|
| S3 state bucket (versioned; `use_lockfile = true` for locking) | `bootstrap` stack (applied once by hand) | **No** |
| GitHub OIDC provider; roles `lab-apply` and `lab-teardown` | bootstrap | No |
| SSM parameter `/fairtix-lab/expires_at` (Standard tier: free) | Written by the apply workflow; the bootstrap stack owns the name | No (reset to `none` after a clean teardown) |
| Reaper Lambda + EventBridge Scheduler (every 30 min) + SNS email topic + CloudWatch alarm on Lambda errors | bootstrap | No |
| Lab stack: VPC, ALB, ECS/Fargate, RDS, ElastiCache, ECR, Secrets, log groups | `lab` stack, its own state key | **Yes** |
| `teardown_policy.py`: the pure decision function, shared by the workflow and the Lambda | `infra/teardown/` in the repo | — |

**Cost of the always-on parts** (bootstrap):
- Lambda, Scheduler, SNS, SSM Standard and IAM fall within free allowances at this volume: 1,440 invocations a month.
- The S3 state bucket costs pennies.
- 1 CloudWatch alarm is within the free 10 *(verify on the pricing page at build time)*.

## 3. The three layers

**Layer 1: the manual session end.**
- Run `workflow_dispatch` on `lab-destroy` (the same job as layer 2, with `force: true`).
- Then run the verification script (§6).
- Habit: never close the laptop with the lab up.

**Layer 2: the GitHub scheduled teardown (`.github/workflows/lab-teardown.yml`).**
```yaml
on:
  schedule: [{ cron: "17 * * * *" }]   # off the top of the hour, when load (and dropping) peaks
  workflow_dispatch: { inputs: { force: { type: boolean, default: false } } }
concurrency: { group: fairtix-lab-state, cancel-in-progress: false }   # same group as lab-apply
permissions: { id-token: write, contents: read, issues: write }
jobs:
  teardown:
    environment: lab-teardown          # no reviewers, no wait timer, branch policy: main
    timeout-minutes: 45
    steps:
      # 1. OIDC → role lab-teardown (trust: sub = repo:firegiant9000/FairTix:environment:lab-teardown)
      # 2. python -m teardown.decide → "noop" | "destroy" (reads SSM + a resource census, not tf state)
      # 3. if destroy: terraform destroy -auto-approve; on DependencyViolation wait 90 s and retry once
      # 4. python -m teardown.verify (§6) → fails the job if anything is left
      # 5. on success: set SSM expires_at = none
      # 6. on failure: open or update the issue "lab teardown failed" (label lab-teardown) with the run URL
```

**Layer 3: the AWS reaper (Lambda, every 30 minutes).**
- It calls the same `decide()`. If the lab is past expiry **plus a 2-hour grace period**, which gives layer 2 time to act first, it deletes the expensive resources directly, by the `fairtix-lab-` name prefix:
  - set ECS services to 0, then delete the services and the cluster;
  - delete the ALB and its target groups;
  - delete the ElastiCache clusters;
  - delete RDS with `--skip-final-snapshot --delete-automated-backups`;
  - release any Elastic IPs.
- Then it emails SNS: "reaper acted; run lab-destroy to clean state".
- **It doesn't touch the VPC, ECR, secrets or log groups:** they cost nothing or pennies, and layers 1–2 clean them up.
- **Its IAM role** may only delete, and only on resources whose names start with the prefix, wherever the service supports a resource-level ARN.
- **State drift:** after the reaper acts, the next `terraform destroy` refreshes, sees the resources are gone, and removes the rest. No `state rm` is needed.

**Apply workflow preflight (`lab-apply.yml`; its environment `lab-apply` may require the owner's approval):**
1. `gh api repos/{repo}/environments/lab-teardown` must show **no** `required_reviewers` rule, **no** `wait_timer`, and a `main` branch policy. Otherwise, fail before touching AWS.
2. `gh workflow enable lab-teardown.yml`, in case the 60-day rule disabled it.
3. The reaper schedule must be `ENABLED` (`aws scheduler get-schedule`).
4. Write SSM `expires_at = now + TTL` (default 4 h, maximum 12 h). **Then** run `terraform apply`.

> `lab-apply` with required reviewers is allowed. If you're the only reviewer, turn off "prevent self-review" on that environment, or you can't approve your own run. The reviewer rule exists only to make you confirm a cost-incurring action; teardown never waits on it.

## 4. The decision function (unit-tested)

`decide(now, expires_at_raw, census, max_ttl=12h, grace=0h) -> "noop" | "destroy"`. `census` counts lab resources by **listing AWS APIs**, not from Terraform state.

| Case | Result |
|---|---|
| Census empty | `noop` (nothing to pay for) |
| Census non-empty; `expires_at` missing, `none`, malformed, or has no timezone | **`destroy`** (fail closed) |
| `now ≥ expires_at + grace` | `destroy` |
| `expires_at > census.oldest_created + max_ttl` (someone set a far-future expiry) | Clamp to `oldest_created + max_ttl`, then decide |
| Otherwise | `noop` |

**Tests (pytest, in `infra/teardown/tests/`, run in CI on every PR):**
- every row above;
- a boundary second either side of expiry;
- DST and UTC (the parameter is ISO-8601 with `Z`);
- a census holding only always-free items (SSM, log groups) → `noop`, so an empty lab doesn't loop forever.

## 5. How to test it (in order; each step has to pass before the next)

| # | Test | Cost | Pass condition |
|---|---|---|---|
| T1 | Unit tests for `decide()` (§4) | $0 | All green in CI |
| T2 | Static checks: `actionlint`; a test that parses both workflow files | $0 | The teardown job's `environment == lab-teardown`; both use `concurrency.group == fairtix-lab-state` with `cancel-in-progress: false`; the schedule exists; `id-token: write` |
| T3 | Environment check: run the apply preflight on its own, then **temporarily add yourself as a required reviewer** to `lab-teardown` and run it again | $0 | The first run passes; the second **fails** before any AWS call. Then remove the reviewer |
| T4 | **Canary end-to-end** (after the AWS account exists): apply a canary stack with free items only (a VPC with no NAT and no public IPv4, a security group, a 1-day log group, a tagged SSM parameter) and `expires_at = now + 10 min` | about $0 | Within 75 minutes, a **scheduled** (not dispatched) run starts, never shows "Waiting", destroys the canary, and `verify` comes back empty. Record the run URL and the delay between expiry and start |
| T5 | **Layer-3 test:** disable `lab-teardown.yml`, apply the canary again with `grace` set to 5 minutes for the test | about $0 | The reaper (adapted to delete the canary's VPC in test mode) acts within 40 minutes; an SNS email arrives; then re-enable the workflow |
| T6 | **Fault injection:** create an untagged log group and a stray security group by hand, outside Terraform | about $0 | `verify` flags both; you delete them by hand |
| T7 | **First real session:** the full stack for 1 hour, then the layer-1 destroy | about $0.12 | `verify` is empty; Cost Explorer at T + 24 h and T + 48 h shows ≤ $0.25 in total, with nothing accruing on day 2 |

**Re-test:**
- T1–T2 on every PR;
- T3 as part of every apply (it *is* the preflight);
- T4 again whenever the workflow or the roles change;
- an F3 acceptance item: T4, T5 and T7 recorded in `docs/infra/teardown-tests.md`.

## 6. Verification script (`python -m teardown.verify`)

- Lists every billable resource type **directly from AWS APIs**, in `us-east-1`, plus a check that no other region has any ENI, instance or load balancer.
- It never relies on tags or Terraform state, and it exits non-zero if anything is found.
- Covers what `terraform destroy` commonly leaves behind (see L3's lab settings in the roadmap):
  - ECS clusters and services;
  - ALBs and target groups;
  - RDS instances, **manual snapshots** and **automated-backup retention**;
  - ElastiCache clusters and **snapshots**;
  - Elastic IPs; NAT gateways; ENIs; non-default VPCs;
  - Secrets (including those **pending deletion**);
  - ECR repositories; log groups;
  - KMS customer-managed keys.
- Allow-list: bootstrap resources (the state bucket, OIDC provider, roles, reaper, SSM parameter, SNS topic, alarm).

## 7. Failure scenarios

| # | Failure | Detected by | What still protects you | Recovery |
|---|---|---|---|---|
| 1 | Scheduled run delayed or dropped under GitHub load | Run history; the T4 delay record | The next hourly tick; then layer 3 at +2 h | None needed |
| 2 | Schedule auto-disabled after 60 days without activity (public repo) | Preflight step 2 re-enables it on every apply; the workflow page shows "disabled" | Layer 3 | `gh workflow enable lab-teardown.yml` |
| 3 | Someone adds required reviewers or a wait timer to `lab-teardown` | Preflight (T3) blocks any new apply | Layer 3 for an already-running lab | Remove the rule; approve or cancel the waiting run |
| 4 | OIDC failure (trust `sub` mismatch after an environment rename, role deleted, provider thumbprint) | The job fails at `configure-aws-credentials` → issue + email | Layer 3 | Fix the trust policy in bootstrap; re-run |
| 5 | Terraform state lock left behind by a cancelled or timed-out run | `destroy` fails with a lock error → issue | Layer 3 | Confirm no run is active, then `terraform force-unlock <id>` |
| 6 | `DependencyViolation` (Fargate ENIs still releasing) | The step retries once | The next hourly tick | Usually self-heals; else delete the ENIs by hand (§9) |
| 7 | Resources outside state (restore-drill instance, console clicks) | `verify` (it doesn't use state) | Layer 3 covers the expensive types by prefix; others need a person | §9 by type |
| 8 | State file lost or corrupted: `destroy` does nothing while the resources still exist | `verify` non-empty → job fails | Layer 3 | Restore the previous state version from the versioned bucket, or delete by hand (§9) |
| 9 | Apply fails partway, after some resources were created | SSM expiry was written **before** apply, so teardown still fires on time | Layers 2–3 | Normal teardown |
| 10 | Pending-run cancellation: a teardown waits in the concurrency group and a new apply cancels it | Expected behaviour | The apply writes a new expiry; the next tick decides again | None needed |
| 11 | GitHub Actions outage | Status page | Layer 3 | None needed |
| 12 | The reaper itself fails (an IAM gap, a throttle) | CloudWatch alarm on Lambda `Errors` → SNS email | Layer 2 | Read the Lambda logs; fix the role in bootstrap |
| 13 | Bootstrap stack destroyed by mistake | Preflight step 3 fails | Nothing on the AWS side. **Don't apply the lab** until bootstrap is restored | Re-apply bootstrap |
| 14 | Provider or Terraform version drift breaks `destroy` | The job fails → issue | Layer 3 | Pinned versions plus a committed `.terraform.lock.hcl`; roll back the bump |
| 15 | Resources in the wrong region | `verify`'s cross-region check | None automatic (the reaper is single-region) | Delete by hand |

## 8. Billing-data delays: what each signal can and can't tell you

| Signal | Latency | Use it for | Don't use it for |
|---|---|---|---|
| `verify` script | Seconds | **Whether anything is still running.** The primary check | — |
| Cost Explorer | ≤ 24 h, sometimes more | A next-day and day-after confirmation that nothing accrued, by service | Same-day decisions |
| Budgets alerts ($5 actual, $15 actual, $20 forecast) | Updates up to 3 times a day, 8–12 h apart | Catching a slow leak over days | Stopping anything. It's a notification, and actions can't touch ECS, ALB or ElastiCache |
| The monthly invoice | Month close | Recording **actual** cost per session in `docs/infra/cost.md` (F3 acceptance) | — |

Rule: a session isn't closed until `verify` is empty. It isn't *confirmed* until Cost Explorer at T + 24 h and T + 48 h shows no service still accruing.

## 9. Manual recovery runbook (when layers 2 and 3 both failed, or `verify` isn't empty)

Use **AWS CloudShell** in the console. It's free and keeps long-lived access keys off your laptop. Sign in as your MFA-protected admin identity.

1. **Stop the meter first** (the most expensive items, in dependency order):
   - ECS: `update-service --desired-count 0`, then `delete-service --force`, then `delete-cluster`.
   - ALB: `elbv2 delete-load-balancer`, then `delete-target-group`.
   - ElastiCache: `delete-cache-cluster`. Delete its snapshots too.
   - RDS: `delete-db-instance --skip-final-snapshot --delete-automated-backups`. Then delete any manual snapshots (`describe-db-snapshots --snapshot-type manual`).
   - `ec2 release-address` for any Elastic IP; `delete-nat-gateway` if one exists.
2. **Then the slow or cheap items:**
   - `secretsmanager delete-secret --force-delete-without-recovery`;
   - `ecr delete-repository --force`;
   - `logs delete-log-group`.
3. **VPC last.** Delete in this order: leftover ENIs, non-default security groups, subnets, route tables, then detach and delete the internet gateway, then the VPC.
4. Run `verify` until it's empty, and take a screenshot.
5. **State:** run `terraform destroy` once. It refreshes, finds the resources gone, and clears state. If the state is corrupt, restore the previous S3 object version first.
6. Close the GitHub issue, linking the cause. Add a row to §7 if it was a new failure mode.
7. Check Cost Explorer at T + 24 h and T + 48 h.
8. **If the cost is unexpectedly high,** open a free AWS **billing** support case. First-time accidental charges are sometimes credited, but that's not guaranteed: don't plan on it.

## 10. Roadmap effect

| Where in `STRATEGIC_ROADMAP.md` | Change |
|---|---|
| L3 acceptance | Adds: T4, T5 and T7 recorded in `docs/infra/teardown-tests.md`; `verify` empty after every session; Cost Explorer checked at T + 24 h and T + 48 h. Replaces the tag query |
| L3b | **Becomes** the layer-3 reaper, built inside L3: it deletes, rather than scaling to 0 and stopping. Required on the Paid plan. It costs about $0 and leaves only VPC, ECR, secrets and logs (pennies) |
| L3 effort | Bootstrap, reaper, `decide`/`verify` and their tests add about 5–7 h |
