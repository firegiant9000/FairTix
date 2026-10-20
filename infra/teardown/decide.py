"""teardown.decide: layer 2's decision (docs/infra/teardown-design.md §3-§4).

    python -m teardown.decide        (run from infra/)

Prints exactly one line to stdout, "noop" or "destroy"; warnings go to stderr. It reads
SSM /fairtix-lab/expires_at and builds the full census (not Terraform state). It fails
closed: an unreadable expiry counts as missing, and a census error, or any failure
before the census, prints "destroy" with a warning.
"""
from __future__ import annotations

import sys
from datetime import datetime, timezone

from botocore.exceptions import ClientError

from . import census
from .census import EXPIRES_AT_PARAMETER, CensusError
from .teardown_policy import DESTROY, decide_for_layer2


def _describe(exc: Exception) -> str:
    """An error summary without AWS's message text (it can carry ARNs and account ids)."""
    if isinstance(exc, CensusError):
        return str(exc)
    if isinstance(exc, ClientError):
        return exc.response.get("Error", {}).get("Code", "ClientError")
    return type(exc).__name__


def read_expiry(ssm, err=sys.stderr) -> str | None:
    try:
        return ssm.get_parameter(Name=EXPIRES_AT_PARAMETER)["Parameter"]["Value"]
    except Exception as exc:  # noqa: BLE001 - unreadable counts as missing (fail closed)
        print(f"WARNING: {EXPIRES_AT_PARAMETER} unreadable ({_describe(exc)}); treating it as missing", file=err)
        return None


def run(now: datetime, ssm, census_fn, out=sys.stdout, err=sys.stderr) -> str:
    expires_at_raw = read_expiry(ssm, err)

    def census_with_warning():
        try:
            return census_fn()
        except Exception as exc:
            print(f"WARNING: census incomplete ({_describe(exc)}); resources may exist, deciding destroy", file=err)
            raise

    decision = decide_for_layer2(now, expires_at_raw, census_with_warning)
    print(decision, file=out)
    return decision


def main(argv=None, *, ssm=None, clients=None, census_fn=None, make_clients=None, now=None,
         out=sys.stdout, err=sys.stderr) -> int:
    now = now or datetime.now(timezone.utc)
    try:
        if census_fn is None:
            clients = clients or (make_clients or census.make_clients)()
            census_fn = lambda: census.build_census(clients)  # noqa: E731
        if ssm is None:
            import boto3

            ssm = boto3.session.Session().client("ssm", region_name=census.HOME_REGION)
    except Exception as exc:  # noqa: BLE001 - can't even look: resources may exist
        print(f"WARNING: could not start the census ({_describe(exc)}); deciding destroy", file=err)
        print(DESTROY, file=out)
        return 0
    run(now, ssm, census_fn, out=out, err=err)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
