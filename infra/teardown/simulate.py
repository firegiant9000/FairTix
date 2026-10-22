"""teardown.simulate: T5b, the IAM simulation (docs/infra/teardown-design.md §5).

    python -m teardown.simulate [--role reaper|lab-teardown|lab-apply|all] [--test-mode]
                                                                     (run from infra/, as the admin)

For every action a role calls, `iam simulate-principal-policy` against the deployed role
must say "allowed". It also checks the scoping: the same actions on resources outside the
lab (another name, a missing tag, the bootstrap state key) must be denied. The action lists
come from the code (teardown.reaper, CENSUS_READ_ACTIONS below), so a new call the code makes
without a matching grant shows up here.

--test-mode: the reaper was applied with reaper_test_mode = true (T5), so its canary
actions must be allowed. Without it they must be denied.

Exit codes: 0 every expectation met; 1 a mismatch (fix the policy narrowly, never with "*");
2 the simulation couldn't run. Output carries action names and resource templates with the
account id replaced by <account>, never real ARNs.
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass

from . import reaper
from .census import EXPIRES_AT_PARAMETER, HOME_REGION
from .decide import _describe

ROLE_PREFIX = "fairtix-lab-bootstrap-"
STATE_BUCKET = "fairtix-lab-bootstrap-tfstate-c2d02e4f"  # infra/bootstrap/locals.tf
EXPIRES_AT = f"arn:aws:ssm:{{region}}:{{account}}:parameter{EXPIRES_AT_PARAMETER}"
REAPER_SCHEDULE = "arn:aws:scheduler:{region}:{account}:schedule/default/fairtix-lab-bootstrap-reaper"

# Every list and describe the census (teardown.decide) and teardown.verify make. Must match
# census_read_actions in infra/bootstrap/locals.tf (tests/test_simulate.py checks both).
CENSUS_READ_ACTIONS = (
    "ec2:DescribeAddresses", "ec2:DescribeInstances", "ec2:DescribeNatGateways",
    "ec2:DescribeNetworkInterfaces", "ec2:DescribeRegions", "ec2:DescribeSecurityGroups",
    "ec2:DescribeVpcEndpoints", "ec2:DescribeVpcs",
    "ecr:DescribeRepositories",
    "ecs:DescribeServices", "ecs:ListClusters", "ecs:ListServices",
    "elasticache:DescribeCacheClusters", "elasticache:DescribeReplicationGroups",
    "elasticache:DescribeServerlessCacheSnapshots", "elasticache:DescribeServerlessCaches",
    "elasticache:DescribeSnapshots",
    "elasticloadbalancing:DescribeLoadBalancers", "elasticloadbalancing:DescribeTargetGroups",
    "kms:DescribeKey", "kms:ListKeys",
    "logs:DescribeLogGroups",
    "rds:DescribeDBInstanceAutomatedBackups", "rds:DescribeDBInstances", "rds:DescribeDBSnapshots",
    "secretsmanager:ListSecrets",
)

PROJECT_TAG = {"aws:ResourceTag/project": "fairtix-lab"}
CANARY_TAGS = {**PROJECT_TAG, "aws:ResourceTag/role": "canary"}
# S3 ARNs carry no account id, so the state grants require the bucket to be in this account.
OWN_BUCKET = {"s3:ResourceAccount": "{account}"}
FOREIGN_BUCKET = {"s3:ResourceAccount": "111122223333"}  # an AWS documentation account
REAPER_FUNCTION = "arn:aws:lambda:{region}:{account}:function:fairtix-lab-bootstrap-reaper"
ALERTS_TOPIC = "arn:aws:sns:{region}:{account}:fairtix-lab-bootstrap-alerts"


@dataclass(frozen=True)
class Case:
    action: str
    resource: str  # a template with {region} and {account}
    allowed: bool
    context: tuple[tuple[str, str], ...] = ()

    def describe(self) -> str:
        tags = "".join(f" [{k.split('/')[-1]}={v.replace('{account}', '<account>')}]" for k, v in self.context)
        return f"{self.action} on {self.resource.replace('{region}', HOME_REGION).replace('{account}', '<account>')}{tags}"


def _sample(template: str, name: str = "sim") -> str:
    """A concrete resource for a policy pattern: 'db:fairtix-lab-*' -> 'db:fairtix-lab-sim'. "*" stays "*"."""
    return template if template == "*" else template.replace("*", name)


def _outside(template: str) -> str:
    """The same resource type, outside the lab prefix."""
    return _sample(template.replace(reaper.LAB_PREFIX, "someone-else-"))


def _ctx(tags: dict) -> tuple[tuple[str, str], ...]:
    return tuple(sorted(tags.items()))


def reaper_cases(test_mode: bool) -> list[Case]:
    cases = [Case(a, _sample(r), True) for a, r in reaper.READ_ACTIONS.items()]
    for action, resource in reaper.MUTATING_ACTIONS.items():
        if action in reaper.TAG_SCOPED_ACTIONS:
            cases.append(Case(action, _sample(resource), True, _ctx(PROJECT_TAG)))
            cases.append(Case(action, _sample(resource), False))  # untagged
        else:
            cases.append(Case(action, _sample(resource), True))
            if reaper.LAB_PREFIX in resource:
                cases.append(Case(action, _outside(resource), False))
    cases.append(Case("ssm:PutParameter", EXPIRES_AT, False))  # the reaper only reads the expiry
    cases += [Case(a, r, test_mode) for a, r in reaper.TEST_MODE_READ_ACTIONS.items()]
    for action, resource in reaper.TEST_MODE_ACTIONS.items():
        cases.append(Case(action, _sample(resource), test_mode, _ctx(CANARY_TAGS)))
        cases.append(Case(action, _sample(resource), False, _ctx(PROJECT_TAG)))  # not the canary
    return cases


def _state(key: str) -> str:
    return f"arn:aws:s3:::{STATE_BUCKET}/{key}"


def _state_cases(prefixes: tuple[str, ...]) -> list[Case]:
    """Read/write on each prefix's state and lock file in this account; the same bucket name in
    another account denied; the bootstrap state denied."""
    own, foreign = _ctx(OWN_BUCKET), _ctx(FOREIGN_BUCKET)
    cases = [Case("s3:ListBucket", f"arn:aws:s3:::{STATE_BUCKET}", True, own),
             Case("s3:ListBucket", f"arn:aws:s3:::{STATE_BUCKET}", False, foreign)]
    for prefix in prefixes:
        for key in (f"{prefix}/terraform.tfstate", f"{prefix}/terraform.tfstate.tflock"):
            cases += [Case(a, _state(key), True, own) for a in ("s3:GetObject", "s3:PutObject", "s3:DeleteObject")]
        cases += [Case(a, _state(f"{prefix}/terraform.tfstate"), False, foreign)
                  for a in ("s3:GetObject", "s3:PutObject")]
    cases += [Case(a, _state("bootstrap/terraform.tfstate"), False, own) for a in ("s3:GetObject", "s3:PutObject")]
    return cases


def teardown_cases() -> list[Case]:
    vpc = "arn:aws:ec2:{region}:{account}:vpc/vpc-sim"
    group = "arn:aws:ec2:{region}:{account}:security-group/sg-sim"
    log_group = "arn:aws:logs:{region}:{account}:log-group:fairtix-lab-canary"
    parameter = "arn:aws:ssm:{region}:{account}:parameter/fairtix-lab/canary"
    cases = [Case(a, "*", True) for a in CENSUS_READ_ACTIONS]
    cases += [Case("ssm:GetParameter", EXPIRES_AT, True), Case("ssm:PutParameter", EXPIRES_AT, True)]
    cases += _state_cases(("lab", "canary"))
    for action, resource in (("ec2:DeleteVpc", vpc), ("ec2:DeleteSecurityGroup", group)):
        cases.append(Case(action, resource, True, _ctx(CANARY_TAGS)))
        cases.append(Case(action, resource, False, _ctx(PROJECT_TAG)))
    cases += [Case("logs:DeleteLogGroup", log_group, True), Case("ssm:DeleteParameter", parameter, True)]
    cases += [Case("logs:DeleteLogGroup", "arn:aws:logs:{region}:{account}:log-group:/aws/lambda/fairtix-lab-bootstrap-reaper", False),
              Case("ssm:DeleteParameter", EXPIRES_AT, False),
              Case("iam:PutRolePolicy", "arn:aws:iam::{account}:role/fairtix-lab-bootstrap-lab-teardown", False)]
    return cases


def apply_cases() -> list[Case]:
    cases = [Case(a, "*", True) for a in CENSUS_READ_ACTIONS]
    cases += [Case("scheduler:GetSchedule", REAPER_SCHEDULE, True),
              Case("lambda:GetFunctionConfiguration", REAPER_FUNCTION, True),
              Case("sns:ListSubscriptionsByTopic", ALERTS_TOPIC, True),
              Case("lambda:UpdateFunctionConfiguration", REAPER_FUNCTION, False),
              Case("sns:Unsubscribe", ALERTS_TOPIC, False),
              Case("ssm:GetParameter", EXPIRES_AT, True), Case("ssm:PutParameter", EXPIRES_AT, True)]
    cases += _state_cases(("lab",))
    cases += [Case("s3:PutObject", _state("canary/terraform.tfstate"), False, _ctx(OWN_BUCKET)),
              Case("ssm:PutParameter", "arn:aws:ssm:{region}:{account}:parameter/fairtix-lab/other", False),
              Case("scheduler:UpdateSchedule", REAPER_SCHEDULE, False),
              Case("iam:PutRolePolicy", "arn:aws:iam::{account}:role/fairtix-lab-bootstrap-lab-apply", False)]
    return cases


ROLES = {
    "reaper": ("reaper", reaper_cases),
    "lab-teardown": ("lab-teardown", lambda _test_mode: teardown_cases()),
    "lab-apply": ("lab-apply", lambda _test_mode: apply_cases()),
}


def simulate(iam, role_arn: str, cases: list[Case], account: str) -> dict[Case, str]:
    """Each case's EvalDecision. Cases sharing a resource and context go in one call."""
    groups: dict[tuple[str, tuple], list[Case]] = {}
    for case in cases:
        groups.setdefault((case.resource, case.context), []).append(case)
    decisions: dict[Case, str] = {}
    for (template, context), group in groups.items():
        resource = template.format(region=HOME_REGION, account=account)
        kwargs = {"PolicySourceArn": role_arn, "ActionNames": sorted({c.action for c in group}),
                  "ResourceArns": [resource]}
        if context:
            kwargs["ContextEntries"] = [{"ContextKeyName": k, "ContextKeyValues": [v.format(account=account)],
                                         "ContextKeyType": "string"} for k, v in context]
        results = {}
        for page in iam.get_paginator("simulate_principal_policy").paginate(**kwargs):
            for result in page["EvaluationResults"]:
                results[result["EvalActionName"]] = result["EvalDecision"]
        for case in group:
            decisions[case] = results.get(case.action, "missing")
    return decisions


def check(decisions: dict[Case, str], out=None) -> int:
    mismatches = 0
    for case, decision in decisions.items():
        ok = (decision == "allowed") == case.allowed
        mismatches += not ok
        expected = "allowed" if case.allowed else "denied"
        print(f"{'ok  ' if ok else 'FAIL'} {decision:<12} (want {expected}) {case.describe()}", file=out)
    return mismatches


def main(argv=None, *, iam=None, sts=None, out=None, err=None) -> int:
    err = err or sys.stderr
    parser = argparse.ArgumentParser(prog="python -m teardown.simulate")
    parser.add_argument("--role", choices=[*ROLES, "all"], default="all")
    parser.add_argument("--test-mode", action="store_true", help="the reaper is in test mode (T5)")
    args = parser.parse_args(argv)
    try:
        if iam is None or sts is None:
            import boto3

            session = boto3.session.Session()
            iam = iam or session.client("iam")
            sts = sts or session.client("sts", region_name=HOME_REGION)
        account = sts.get_caller_identity()["Account"]
        mismatches = 0
        for name in (ROLES if args.role == "all" else [args.role]):
            suffix, build = ROLES[name]
            cases = build(args.test_mode)
            print(f"== {ROLE_PREFIX}{suffix}: {len(cases)} case(s)", file=out)
            decisions = simulate(iam, f"arn:aws:iam::{account}:role/{ROLE_PREFIX}{suffix}", cases, account)
            mismatches += check(decisions, out)
    except Exception as exc:  # noqa: BLE001
        print(f"teardown.simulate: ERROR {_describe(exc)}", file=err)
        return 2
    print(f"teardown.simulate: {'PASS' if not mismatches else f'{mismatches} mismatch(es)'}", file=out)
    return 0 if not mismatches else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
