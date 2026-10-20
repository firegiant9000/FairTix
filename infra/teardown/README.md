# infra/teardown

The lab's cost control: the `decide()` policy, the AWS census and `teardown.verify`. The design is [`docs/infra/teardown-design.md`](../../docs/infra/teardown-design.md) (TD).

**Status:** L3 phase 0. The code and its stubbed tests exist. Nothing has run against AWS yet; the real runs (T4–T7) are FT-3.

## Run the tests

From `infra/`, in a venv on Python ≥ 3.11 (CI uses 3.12):

```sh
python -m pip install -r teardown/requirements-dev.txt
python -m pytest teardown/tests -q
```

The tests never reach the network. Every AWS call goes through an injected client wrapped in `botocore.stub.Stubber`, and `tests/conftest.py` blocks sockets.

## Modules

| Module | What it does |
|---|---|
| `teardown_policy.py` | The pure `decide(now, expires_at_raw, census, max_ttl=12h, grace=0h)` → `noop` / `destroy` (TD §4), plus the layer-2 and reaper callers |
| `census.py` | Lists lab resources from AWS APIs (no tags, no Terraform state). Any error raises `CensusError` |
| `decide.py` | `python -m teardown.decide`: prints `noop` or `destroy` for layer 2. A census error prints `destroy` |
| `verify.py` | `python -m teardown.verify`: exits 0 if nothing is found, 1 on leftovers, 2 on any error |
| `preflight.py` | `python -m teardown.preflight [--github-only] [--ttl-hours N]`: `lab-apply`'s checks. Exits 0 only after writing the expiry; 1 on the first failed check. `--github-only` (T3) makes no AWS call. See [`docs/infra/README.md`](../../docs/infra/README.md) for `LAB_ENABLED` and `LAB_PREFLIGHT_TOKEN` |

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
- The lab uses no NAT gateway. Interface VPC endpoints are billable; the census counts them, and the reaper (FT-3) should delete them.
