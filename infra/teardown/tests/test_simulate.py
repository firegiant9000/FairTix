"""teardown.simulate (T5b) and the action lists it checks.

The real simulation runs against the deployed roles (docs/infra/teardown-tests.md, T5b).
These tests stub IAM and check that the lists cover every call the code makes, and that
they match the Terraform policy's census list.
"""
import re
from pathlib import Path

import pytest

from teardown import census, simulate, verify
from teardown.tests.conftest import ACCOUNT_ID, EMPTY_BILLABLE, EMPTY_FREE, stub_client
from teardown.tests.test_reaper import Recorder

ROLE = f"arn:aws:iam::{ACCOUNT_ID}:role/fairtix-lab-bootstrap-lab-apply"
LOCALS_TF = Path(__file__).resolve().parents[2] / "bootstrap" / "locals.tf"


def test_census_read_actions_match_the_terraform_policy():
    text = LOCALS_TF.read_text(encoding="utf-8")
    block = re.search(r"census_read_actions = \[(.*?)\]", text, re.DOTALL).group(1)
    assert set(re.findall(r'"([\w-]+:\w+)"', block)) == set(simulate.CENSUS_READ_ACTIONS)


def test_census_and_verify_make_only_listed_calls(stubs):
    recorder = Recorder()
    for client in stubs.clients.values():
        recorder.attach(client)
    for service, operation, response in EMPTY_BILLABLE + EMPTY_FREE:
        stubs.add(service, operation, response)
    stubs.add("ec2", "describe_instances", {"Reservations": []})
    stubs.add("ec2", "describe_regions", {"Regions": [{"RegionName": "us-east-1"}, {"RegionName": "eu-west-1"}]})

    other_ec2, other_ec2_stub = stub_client("ec2", "eu-west-1")
    other_elb, other_elb_stub = stub_client("elbv2", "eu-west-1")
    recorder.attach(other_ec2)
    recorder.attach(other_elb)
    other_ec2_stub.add_response("describe_network_interfaces", {"NetworkInterfaces": []})
    other_ec2_stub.add_response("describe_instances", {"Reservations": []})
    other_elb_stub.add_response("describe_load_balancers", {"LoadBalancers": []})

    found = verify.find_leftovers(stubs.as_clients(),
                                  lambda service, _region: other_ec2 if service == "ec2" else other_elb)

    assert found == []
    assert set(recorder.actions) <= set(simulate.CENSUS_READ_ACTIONS), \
        set(recorder.actions) - set(simulate.CENSUS_READ_ACTIONS)
    assert "kms:ListKeys" in recorder.actions


def test_reaper_cases_cover_every_declared_action():
    from teardown import reaper

    actions = {c.action for c in simulate.reaper_cases(test_mode=True)}
    assert set(reaper.READ_ACTIONS) | set(reaper.MUTATING_ACTIONS) | set(reaper.TEST_MODE_ACTIONS) <= actions


def test_every_name_scoped_reaper_action_has_a_denied_twin():
    from teardown import reaper

    cases = simulate.reaper_cases(test_mode=False)
    for action, resource in reaper.MUTATING_ACTIONS.items():
        if reaper.LAB_PREFIX in resource or action in reaper.TAG_SCOPED_ACTIONS:
            assert any(c.action == action and not c.allowed for c in cases), action


def test_test_mode_actions_are_expected_denied_outside_test_mode():
    from teardown import reaper

    cases = [c for c in simulate.reaper_cases(test_mode=False) if c.action in reaper.TEST_MODE_ACTIONS]
    assert cases and not any(c.allowed for c in cases)


@pytest.mark.parametrize("build", [simulate.teardown_cases, simulate.apply_cases], ids=["teardown", "apply"])
def test_state_grants_are_checked_in_this_account_and_denied_in_another(build):
    s3 = [c for c in build() if c.action.startswith("s3:")]
    assert s3 and all(dict(c.context).get("s3:ResourceAccount") for c in s3)
    assert any(not c.allowed and dict(c.context)["s3:ResourceAccount"] == "111122223333" for c in s3)
    assert all(c.allowed for c in s3 if dict(c.context)["s3:ResourceAccount"] == "{account}"
               and "/lab/" in c.resource)


def test_the_account_is_filled_into_context_values():
    iam, iam_stub = stub_client("iam")
    case = simulate.Case("s3:GetObject", f"arn:aws:s3:::{simulate.STATE_BUCKET}/lab/terraform.tfstate", True,
                         (("s3:ResourceAccount", "{account}"),))
    iam_stub.add_response("simulate_principal_policy", {"EvaluationResults": [
        {"EvalActionName": "s3:GetObject", "EvalDecision": "allowed"}], "IsTruncated": False},
        {"PolicySourceArn": ROLE, "ActionNames": ["s3:GetObject"], "ResourceArns": [case.resource],
         "ContextEntries": [{"ContextKeyName": "s3:ResourceAccount", "ContextKeyValues": [ACCOUNT_ID],
                             "ContextKeyType": "string"}]})

    assert simulate.simulate(iam, ROLE, [case], ACCOUNT_ID) == {case: "allowed"}
    assert ACCOUNT_ID not in case.describe()


def test_no_sampled_resource_is_a_bare_word():
    for build in (lambda: simulate.reaper_cases(True), simulate.teardown_cases, simulate.apply_cases):
        for case in build():
            assert case.resource == "*" or case.resource.startswith("arn:aws:"), case


def _decisions(iam_stub, results):
    """Queue one simulate response per call, in order. results: list of {action: decision}."""
    for mapping in results:
        iam_stub.add_response("simulate_principal_policy", {"EvaluationResults": [
            {"EvalActionName": action, "EvalDecision": decision} for action, decision in mapping.items()],
            "IsTruncated": False})


def test_simulate_groups_cases_and_fills_in_the_account():
    iam, iam_stub = stub_client("iam")
    cases = [simulate.Case("ecs:DeleteCluster", "arn:aws:ecs:{region}:{account}:cluster/fairtix-lab-sim", True),
             simulate.Case("ec2:ReleaseAddress", "arn:aws:ec2:{region}:{account}:elastic-ip/sim", True,
                           (("aws:ResourceTag/project", "fairtix-lab"),))]
    role = f"arn:aws:iam::{ACCOUNT_ID}:role/fairtix-lab-bootstrap-reaper"
    iam_stub.add_response("simulate_principal_policy", {"EvaluationResults": [
        {"EvalActionName": "ecs:DeleteCluster", "EvalDecision": "allowed"}], "IsTruncated": False},
        {"PolicySourceArn": role, "ActionNames": ["ecs:DeleteCluster"],
         "ResourceArns": [f"arn:aws:ecs:us-east-1:{ACCOUNT_ID}:cluster/fairtix-lab-sim"]})
    iam_stub.add_response("simulate_principal_policy", {"EvaluationResults": [
        {"EvalActionName": "ec2:ReleaseAddress", "EvalDecision": "allowed"}], "IsTruncated": False},
        {"PolicySourceArn": role, "ActionNames": ["ec2:ReleaseAddress"],
         "ResourceArns": [f"arn:aws:ec2:us-east-1:{ACCOUNT_ID}:elastic-ip/sim"],
         "ContextEntries": [{"ContextKeyName": "aws:ResourceTag/project", "ContextKeyValues": ["fairtix-lab"],
                             "ContextKeyType": "string"}]})

    decisions = simulate.simulate(iam, role, cases, ACCOUNT_ID)

    assert set(decisions.values()) == {"allowed"}
    iam_stub.assert_no_pending_responses()


@pytest.mark.parametrize("decision,want_allowed,mismatches", [
    ("allowed", True, 0), ("implicitDeny", False, 0), ("explicitDeny", False, 0),
    ("implicitDeny", True, 1), ("allowed", False, 1), ("missing", True, 1)])
def test_check_counts_mismatches(decision, want_allowed, mismatches, capsys):
    case = simulate.Case("ecs:DeleteCluster", "arn:aws:ecs:{region}:{account}:cluster/fairtix-lab-sim", want_allowed)
    assert simulate.check({case: decision}) == mismatches
    assert ("FAIL" in capsys.readouterr().out) == bool(mismatches)


def test_main_never_prints_the_account_id(capsys):
    iam, iam_stub = stub_client("iam")
    sts, sts_stub = stub_client("sts")
    sts_stub.add_response("get_caller_identity", {"Account": ACCOUNT_ID, "Arn": f"arn:aws:iam::{ACCOUNT_ID}:user/x",
                                                  "UserId": "AIDAEXAMPLE"})
    cases = simulate.apply_cases()
    groups = {}
    for case in cases:
        groups.setdefault((case.resource, case.context), []).append(case)
    for group in groups.values():
        iam_stub.add_response("simulate_principal_policy", {"EvaluationResults": [
            {"EvalActionName": c.action, "EvalDecision": "allowed" if c.allowed else "implicitDeny"} for c in group],
            "IsTruncated": False})

    code = simulate.main(["--role", "lab-apply"], iam=iam, sts=sts)

    printed = capsys.readouterr()
    assert code == 0
    assert "PASS" in printed.out
    assert ACCOUNT_ID not in printed.out + printed.err
    assert "<account>" in printed.out


def test_main_reports_a_mismatch_with_exit_1(capsys):
    iam, iam_stub = stub_client("iam")
    sts, sts_stub = stub_client("sts")
    sts_stub.add_response("get_caller_identity", {"Account": ACCOUNT_ID, "Arn": f"arn:aws:iam::{ACCOUNT_ID}:user/x",
                                                  "UserId": "AIDAEXAMPLE"})
    groups = {}
    for case in simulate.apply_cases():
        groups.setdefault((case.resource, case.context), []).append(case)
    for group in groups.values():
        iam_stub.add_response("simulate_principal_policy", {"EvaluationResults": [
            {"EvalActionName": c.action, "EvalDecision": "allowed"} for c in group], "IsTruncated": False})

    assert simulate.main(["--role", "lab-apply"], iam=iam, sts=sts) == 1
    assert "FAIL" in capsys.readouterr().out


def test_main_error_exits_2(capsys):
    iam, _ = stub_client("iam")
    sts, sts_stub = stub_client("sts")
    sts_stub.add_client_error("get_caller_identity", service_error_code="ExpiredToken", http_status_code=403)

    assert simulate.main([], iam=iam, sts=sts) == 2
    assert "ExpiredToken" in capsys.readouterr().err


def test_census_module_name_contract():
    assert simulate.ROLE_PREFIX == census.BOOTSTRAP_PREFIX
