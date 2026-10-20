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

## Bootstrap naming contract (FT-3 must follow it)

- Every bootstrap resource is named `fairtix-lab-bootstrap-*`. The reaper Lambda is exactly `fairtix-lab-bootstrap-reaper`. The expiry parameter is exactly `/fairtix-lab/expires_at`.
- No lab resource name may start with `fairtix-lab-bootstrap`.
- Bootstrap owns no billable resource and no customer-managed KMS key.
- The census lists only one bootstrap resource: the reaper's log group, `/aws/lambda/fairtix-lab-bootstrap-reaper`. It's excluded by **exact name**, so a look-alike is still counted. Adding a bootstrap log group means adding it to `BOOTSTRAP_LOG_GROUPS` in `census.py`.
- The lab should use AWS-managed KMS keys. `teardown.verify` counts customer-managed keys in every state, including `PendingDeletion`, and AWS keeps those for at least 7 days.
