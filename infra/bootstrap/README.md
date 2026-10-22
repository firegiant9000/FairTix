# infra/bootstrap

The always-on part of the L3 lab ([`docs/infra/teardown-design.md`](../../docs/infra/teardown-design.md) §2). **Arlo applies it once, locally,** with the `fairtix-lab-admin` profile (AWS authorization, 2026-10-22). No workflow applies it, and the lab roles can't read or write its state.

| File | What it creates |
|---|---|
| `state.tf` | The state bucket `fairtix-lab-bootstrap-tfstate-c2d02e4f`: versioned, SSE-S3, public access blocked, TLS only, and a policy that denies everyone except the two lab roles and the admin |
| `github_oidc.tf` | The GitHub OIDC provider and the roles `fairtix-lab-bootstrap-lab-apply` and `-lab-teardown`. Each trust pins `aud`, `sub` (the environment), `job_workflow_ref` (the workflow file on `main`) and `repository_id`, all with `StringEquals` |
| `lab_role_policies.tf` | What those roles may do in stage A: state (`lab/`, plus `canary/` for teardown), the expiry parameter, the census, and destroying the canary. Stage B adds what `infra/lab` needs |
| `reaper.tf` | The reaper Lambda `fairtix-lab-bootstrap-reaper` (Python 3.12, `teardown.reaper.handler`), its role (TD:90-95), its log group (14 days), and the EventBridge Scheduler schedule `fairtix-lab-bootstrap-reaper` (every 30 minutes, `ENABLED`, `default` group) |
| `alerts.tf` | The SNS topic `fairtix-lab-bootstrap-alerts` with an email subscription, two alarms (the reaper failed: `Errors ≥ 1`; the reaper hasn't run for an hour: `Invocations < 1`, missing data counts), and the monthly budget with alerts at $5, $15 and $20 actual and $20 forecast |
| `expiry.tf` | The SSM parameter `/fairtix-lab/expires_at` (Standard tier), created as `none`. Terraform then ignores its value: `teardown.preflight` writes it before each apply, and `lab-teardown` resets it |
| `variables.tf` | `alert_email` (sensitive), `reaper_test_mode` and `reaper_grace_minutes` (T5 only) |

**The budget alerts are notifications, not a cap.** Budgets data updates up to three times a day, and budget actions can't stop Fargate, an ALB or ElastiCache. The teardown layers are the cost control (TD §8). The $20 matches the L3 spend ceiling, but the budget resets monthly; the ceiling is for all of L3.

## Before the first apply

1. **Don't push the branch until bootstrap has created the bucket.** Its name is committed in `infra/canary` (and later `infra/lab`) because the workflows' `init` takes no `-backend-config`. S3 names are global, so a name that is public before it exists could be claimed by someone else, and lab state would then be written to their bucket.
2. **Credentials:** the AWS provider (≥ 6.23) reads `aws login` sessions directly. Terraform's **S3 backend** doesn't yet (hashicorp/terraform#37976), so any stack with an S3 backend (`infra/canary`, and bootstrap after the state move) needs exported short-lived credentials:
   ```powershell
   aws login --profile fairtix-lab-admin
   aws configure export-credentials --profile fairtix-lab-admin --format powershell | Invoke-Expression
   ```
   They last about 15 minutes; export again if a command reports expired credentials. Never use access keys.
3. **The alert email:** put it in `infra/bootstrap/terraform.tfvars` (gitignored) as `alert_email = "..."`, or set `$env:TF_VAR_alert_email`.

## Apply (Arlo)

From the repo root, with `$env:AWS_PROFILE = "fairtix-lab-admin"`:

```powershell
terraform -chdir=infra/bootstrap init -lockfile=readonly
terraform -chdir=infra/bootstrap plan -out=bootstrap.tfplan
terraform -chdir=infra/bootstrap apply bootstrap.tfplan
```

Then:
1. Confirm the SNS subscription from the email AWS sends (the link expires after 3 days). `teardown.preflight` refuses any lab apply until a confirmed subscription exists.
2. Check the budget exists: `aws budgets describe-budgets --account-id <id> --query "Budgets[].BudgetName"` (don't paste the id anywhere).
3. Run T5b: `python -m teardown.simulate` from `infra/` (see `docs/infra/teardown-tests.md`).
4. Set the GitHub settings in [`docs/infra/README.md`](../../docs/infra/README.md#github-settings-the-lab-depends-on). Copy the role ARNs with `terraform -chdir=infra/bootstrap output -raw lab_teardown_role_arn | Set-Clipboard` (and `lab_apply_role_arn`) straight into the environment secrets.

`plan` and `apply` output carry the account id, the ARNs and the alert email. Keep them local: never paste them into an issue, PR, commit or log. `*.tfplan` and `*.tfstate*` are gitignored.

## State: the chicken-and-egg choice

The bucket can't hold the state of the stack that creates it until it exists. So:

1. **First apply: local state** (`infra/bootstrap/terraform.tfstate`, gitignored, on Arlo's machine only).
2. **Then move it into the bucket, under its own key** `bootstrap/terraform.tfstate`. Add this block to `versions.tf`:
   ```hcl
   backend "s3" {
     bucket       = "fairtix-lab-bootstrap-tfstate-c2d02e4f"
     key          = "bootstrap/terraform.tfstate"
     region       = "us-east-1"
     encrypt      = true
     use_lockfile = true
   }
   ```
   With exported credentials (above), run `terraform -chdir=infra/bootstrap init -migrate-state`. Then delete the local `terraform.tfstate*` files.

The lab roles' policies cover only `lab/*` (both) and `canary/*` (teardown). Neither can read or write `bootstrap/`, so a compromised lab workflow can't rewrite the roles that trust it. The bucket's versioning keeps 90 days of old state versions for recovery.

## Lock-out

The bucket policy denies every principal except the two lab roles and **the identity that applied it** (`aws:PrincipalArn`, resolved through `aws_iam_session_context`). If the admin identity changes, the new one is denied too. The account root can always delete a bucket policy: sign in as root, delete it, re-apply from the new identity, sign out.

## Test mode (T5 only)

```powershell
terraform -chdir=infra/bootstrap apply -var reaper_test_mode=true -var reaper_grace_minutes=5
```

The reaper then also counts the canary VPC and may delete it and its security groups (both tags required). **Re-apply without the variables straight after T5.** The variables' validation refuses a grace below 120 minutes outside test mode, and `teardown.preflight` refuses every lab apply while the reaper is in test mode or its grace is under 120 minutes.

## Changing it

- The reaper's code is `infra/teardown/*.py`. A change there changes the zip, so `plan` shows the Lambda updating; re-apply after merging.
- Provider bumps: update `.terraform.lock.hcl` for both `linux_amd64` (CI) and `windows_amd64`: `terraform -chdir=infra/bootstrap providers lock -platform=linux_amd64 -platform=windows_amd64`.
- After any change to the roles, re-run T5b, and T4 if the teardown role changed (TD §5, "Re-test").
