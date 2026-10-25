# infra/teardown

The lab's cost control: the `decide()` policy, the AWS census and `teardown.verify`. The design is [`docs/infra/teardown-design.md`](../../docs/infra/teardown-design.md) (TD).

**Status:** L3 phase 1 (FT-3 stage A). The reaper and T5b's simulation exist with stubbed tests; `infra/bootstrap` deploys the reaper. The real runs (T4–T7) are recorded in [`docs/infra/teardown-tests.md`](../../docs/infra/teardown-tests.md).

## Run the tests

From `infra/`, in a venv on Python ≥ 3.11 (CI and the Lambda use 3.12):

```sh
python -m pip install -r teardown/requirements-dev.txt
python -m pytest teardown/tests -q
```

## Dependencies

- `requirements.txt` holds the top-level pins (boto3). `requirements-dev.txt` adds the test-only ones.
- **`requirements.lock`** pins every package `requirements.txt` pulls in, with sha256 hashes. The lab workflows install it with `pip install --require-hashes`, because their jobs hold `id-token: write`. After changing `requirements.txt`, run `python tools/lock_requirements.py` from `infra/` and commit both files; T2 fails if they disagree.
- The reaper Lambda bundles no dependencies. It uses the boto3 in the Python 3.12 runtime, and its zip holds only this package's top-level modules.

The tests never reach the network. Every AWS call goes through an injected client wrapped in `botocore.stub.Stubber`, and `tests/conftest.py` blocks sockets.

## Modules

| Module | What it does |
|---|---|
| `teardown_policy.py` | The pure `decide(now, expires_at_raw, census, max_ttl=12h, grace=0h)` → `noop` / `destroy` (TD §4), plus the layer-2 and reaper callers |
| `census.py` | Lists lab resources from AWS APIs (no tags, no Terraform state). Any error raises `CensusError` |
| `decide.py` | `python -m teardown.decide`: prints `noop` or `destroy` for layer 2. A census error prints `destroy` |
| `verify.py` | `python -m teardown.verify`: exits 0 if nothing is found, 1 on leftovers, 2 on any error |
| `preflight.py` | `python -m teardown.preflight [--github-only] [--ttl-hours N]`: `lab-apply`'s checks, including (FT-3) that the reaper is out of test mode with a grace of at least 120 minutes and that its alert topic has a confirmed subscription. Exits 0 only after writing the expiry; 1 on the first failed check. `--github-only` (T3) makes no AWS call. See [`docs/infra/README.md`](../../docs/infra/README.md) for `LAB_ENABLED` and `LAB_PREFLIGHT_TOKEN` |

| `reaper.py` | Layer 3. The Lambda handler `teardown.reaper.handler`, and locally `python -m teardown.reaper --dry-run [--test-mode] [--grace-minutes N]` (the CLI only does dry runs). Billable census, 2 h grace; deletes in the TD:80-88 order, then emails SNS. A census error raises; a busy resource is deferred to the next run; any other failure raises after the email. It also lists the IAM actions it calls, for T5b |
| `simulate.py` | T5b: `python -m teardown.simulate [--role ...] [--test-mode]`. Runs `iam simulate-principal-policy` for every action each bootstrap role calls (must be allowed) and the same actions outside the lab (must be denied). Exits 0 on a full match, 1 on a mismatch, 2 on an error |

`tests/test_workflows_static.py` is T2: it parses `.github/workflows/lab-teardown.yml` and `lab-apply.yml` (PyYAML, test-only).

## Bootstrap naming contract (FT-3 must follow it)

- Every bootstrap resource is named `fairtix-lab-bootstrap-*`. The reaper Lambda is exactly `fairtix-lab-bootstrap-reaper`. The expiry parameter is exactly `/fairtix-lab/expires_at`.
- The reaper's EventBridge Scheduler schedule is exactly `fairtix-lab-bootstrap-reaper`, in the `default` group. `teardown.preflight` refuses to apply unless it's `ENABLED`.
- No lab resource name may start with `fairtix-lab-bootstrap`.
- Bootstrap owns no billable resource and no customer-managed KMS key.
- The census lists only one bootstrap resource: the reaper's log group, `/aws/lambda/fairtix-lab-bootstrap-reaper`. It's excluded by **exact name**, so a look-alike is still counted. Adding a bootstrap log group means adding it to `BOOTSTRAP_LOG_GROUPS` in `census.py`.
- The lab uses **AWS-managed KMS keys only**, and creates its Secrets Manager secrets with **`recovery_window_in_days = 0`**.
  - The census counts customer-managed keys in every state, including `PendingDeletion`, and secrets pending deletion. That's the fail-closed choice.
  - A key pending deletion stays for at least 7 days; a secret with the default recovery window stays for 30.
  - During that time, `teardown.decide` keeps answering `destroy` and `teardown.verify` keeps failing, so the "empty lab" never goes quiet.
- The ALB ingress variable (a single `/32`, the operator's IP) is `sensitive = true` and comes from a secret. The workflows keep Terraform's plan out of the public Actions log and print only the summary and errors (4 lines of context each). Two trade-offs:
  - an error message can still echo a value, so keep secrets out of resource names and error-prone attributes;
  - there's no live progress during a long destroy, and the full log stays on the runner and is lost with it.
- The lab uses no NAT gateway. Interface VPC endpoints are billable; the census counts them, and the reaper deletes the ones tagged `project=fairtix-lab`.
- **What the reaper deletes:** by name prefix `fairtix-lab-` (not `fairtix-lab-bootstrap`): ECS clusters and their services, ALBs, target groups, ElastiCache replication groups, standalone clusters and serverless caches, RDS instances. By the tag `project=fairtix-lab`: NAT gateways, interface VPC endpoints, Elastic IPs. So **every lab resource of those types must carry the prefix or the tag.** Anything billable it can't match keeps `decide` at `destroy`, so it emails every 30 minutes until a person deletes it. That includes manual RDS or ElastiCache snapshots, which it never deletes.
- **Test mode only (T5):** the canary VPC and its security groups, which need both tags `project=fairtix-lab` and `role=canary`.
