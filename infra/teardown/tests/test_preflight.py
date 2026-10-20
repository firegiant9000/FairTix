"""teardown.preflight: lab-apply's checks (TD §3 "Apply workflow preflight", FT-2 stage B).

Every failure must happen before any AWS call. The AWS clients are Stubbers with a
recorder on every call, so "untouched" means no API call was even attempted (a Stubber
alone would raise, and the preflight would turn that into an ordinary failure).
"""
import io
import json
import subprocess
from datetime import datetime, timezone

import pytest

from teardown import preflight
from teardown.preflight import GhClient, GhError

from .conftest import stub_client

REPO = "firegiant9000/FairTix"
ENV = f"repos/{REPO}/environments/lab-teardown"
POLICIES = f"{ENV}/deployment-branch-policies"
WORKFLOW = f"repos/{REPO}/actions/workflows/lab-teardown.yml"
NOW = datetime(2026, 10, 20, 12, 0, tzinfo=timezone.utc)
SENTINEL = "sentinel-response-body-7f3a"  # must never be printed


def env_response(rules=None, policy="custom"):
    if rules is None:
        rules = [{"id": 1, "node_id": SENTINEL, "type": "branch_policy"}]
    branch_policy = {
        "custom": {"protected_branches": False, "custom_branch_policies": True},
        "protected": {"protected_branches": True, "custom_branch_policies": False},
        "none": None,
    }[policy]
    return {"name": "lab-teardown", "url": SENTINEL, "protection_rules": rules,
            "deployment_branch_policy": branch_policy}


def policies_response(*names, type_="branch"):
    return {"total_count": len(names),
            "branch_policies": [{"id": i, "node_id": SENTINEL, "name": n, "type": type_} for i, n in enumerate(names)]}


def runs_response(count):
    return {"total_count": count, "workflow_runs": [{"id": i, "html_url": SENTINEL} for i in range(count)]}


def good_github():
    return {
        ("GET", ENV): env_response(),
        ("GET", POLICIES): policies_response("main"),
        ("PUT", f"{WORKFLOW}/enable"): {},
        ("GET", WORKFLOW): {"id": 7, "path": SENTINEL, "state": "active"},
        ("GET", f"{WORKFLOW}/runs?status=queued&per_page=1"): runs_response(0),
        ("GET", f"{WORKFLOW}/runs?status=in_progress&per_page=1"): runs_response(0),
    }


class FakeGh:
    """Stands in for GhClient: canned responses keyed by (method, path)."""

    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    def api(self, path, method="GET"):
        self.calls.append((method, path))
        value = self.responses[(method, path)]
        if isinstance(value, Exception):
            raise value
        return value


class Aws:
    """Stubbed scheduler + ssm clients that record every attempted call."""

    def __init__(self):
        self.scheduler, self.scheduler_stub = stub_client("scheduler")
        self.ssm, self.ssm_stub = stub_client("ssm")
        self.calls = []
        self.factory_calls = 0
        for client in (self.scheduler, self.ssm):
            client.meta.events.register("before-parameter-build", self._record)

    def _record(self, model, **_kwargs):
        self.calls.append(model.name)

    def factory(self):
        self.factory_calls += 1
        return preflight.AwsClients(scheduler=self.scheduler, ssm=self.ssm)

    def expect_reaper(self, state="ENABLED"):
        self.scheduler_stub.add_response(
            "get_schedule",
            {"Name": "fairtix-lab-bootstrap-reaper", "State": state, "ScheduleExpression": "rate(30 minutes)",
             "Target": {"Arn": "arn:aws:lambda:us-east-1:123456789012:function:fairtix-lab-bootstrap-reaper",
                        "RoleArn": "arn:aws:iam::123456789012:role/fairtix-lab-bootstrap-scheduler"},
             "FlexibleTimeWindow": {"Mode": "OFF"}},
            {"Name": "fairtix-lab-bootstrap-reaper", "GroupName": "default"},
        )

    def expect_expiry_write(self, value="2026-10-20T16:00:00Z"):
        self.ssm_stub.add_response(
            "put_parameter", {"Version": 3, "Tier": "Standard"},
            {"Name": "/fairtix-lab/expires_at", "Value": value, "Type": "String", "Overwrite": True},
        )

    def assert_untouched(self):
        assert self.calls == []
        assert self.factory_calls == 0

    def close(self):
        self.scheduler_stub.deactivate()
        self.ssm_stub.deactivate()


@pytest.fixture
def aws():
    a = Aws()
    yield a
    a.close()


def run(aws, gh_actions=None, gh_env=None, argv=(), environ=None):
    environ = {"LAB_ENABLED": "true", "LAB_PREFLIGHT_TOKEN": "dummy", "GITHUB_REPOSITORY": REPO} \
        if environ is None else environ
    responses = good_github()
    gh_actions = gh_actions or FakeGh(responses)
    gh_env = gh_env or FakeGh(responses)
    out, err = io.StringIO(), io.StringIO()
    code = preflight.main(list(argv), environ=environ, gh_env=gh_env, gh_actions=gh_actions,
                          aws_clients=aws.factory, now=NOW, out=out, err=err)
    return code, out.getvalue(), err.getvalue()


def assert_failed(result, check):
    code, out, err = result
    assert code == 1
    assert f"preflight FAILED at {check}" in err
    assert "passed" not in out
    assert SENTINEL not in out + err


# --- the happy path -------------------------------------------------------------------

def test_happy_path_writes_expiry_then_passes(aws):
    aws.expect_reaper()
    aws.expect_expiry_write()
    code, out, err = run(aws)
    assert code == 0, err
    assert aws.calls == ["GetSchedule", "PutParameter"]
    aws.scheduler_stub.assert_no_pending_responses()
    aws.ssm_stub.assert_no_pending_responses()
    assert out.strip().endswith("preflight passed: expires_at = 2026-10-20T16:00:00Z")
    assert SENTINEL not in out + err


def test_ttl_flag_sets_the_expiry(aws):
    aws.expect_reaper()
    aws.expect_expiry_write("2026-10-21T00:00:00Z")
    code, _, err = run(aws, argv=["--ttl-hours", "12"])
    assert code == 0, err


def test_github_calls_happen_in_spec_order(aws):
    aws.expect_reaper()
    aws.expect_expiry_write()
    gh = FakeGh(good_github())
    code, _, err = run(aws, gh_actions=gh, gh_env=gh)
    assert code == 0, err
    assert gh.calls == [
        ("GET", ENV),
        ("GET", POLICIES),
        ("PUT", f"{WORKFLOW}/enable"),
        ("GET", WORKFLOW),
        ("GET", f"{WORKFLOW}/runs?status=queued&per_page=1"),
        ("GET", f"{WORKFLOW}/runs?status=in_progress&per_page=1"),
    ]


def test_environment_api_uses_the_preflight_token_client(aws):
    aws.expect_reaper()
    aws.expect_expiry_write()
    gh_env, gh_actions = FakeGh(good_github()), FakeGh(good_github())
    code, _, err = run(aws, gh_actions=gh_actions, gh_env=gh_env)
    assert code == 0, err
    assert all(path.startswith(ENV) for _, path in gh_env.calls)
    assert all(path.startswith(WORKFLOW) for _, path in gh_actions.calls)


def test_github_only_stops_before_any_aws(aws):
    code, out, err = run(aws, argv=["--github-only"])
    assert code == 0, err
    aws.assert_untouched()
    assert "GitHub checks passed" in out


def test_ssm_write_failure_is_not_success(aws):
    aws.expect_reaper()
    aws.ssm_stub.add_client_error("put_parameter", service_error_code="AccessDeniedException",
                                  service_message=SENTINEL, http_status_code=400)
    result = run(aws)
    assert_failed(result, "expiry")
    assert "AccessDeniedException" in result[2]


# --- step 1: LAB_ENABLED (FT-7) -------------------------------------------------------

@pytest.mark.parametrize("value", [None, "", "false", "1", "yes", " true"])
def test_lab_enabled_not_true_fails_before_anything(aws, value):
    environ = {"LAB_PREFLIGHT_TOKEN": "dummy", "GITHUB_REPOSITORY": REPO}
    if value is not None:
        environ["LAB_ENABLED"] = value
    gh = FakeGh(good_github())
    assert_failed(run(aws, gh_actions=gh, gh_env=gh, environ=environ), "LAB_ENABLED")
    assert gh.calls == []
    aws.assert_untouched()


# --- step 2: the lab-teardown environment ---------------------------------------------

def env_failure(aws, responses, check="lab-teardown environment"):
    gh = FakeGh(responses)
    assert_failed(run(aws, gh_actions=gh, gh_env=gh), check)
    assert ("PUT", f"{WORKFLOW}/enable") not in gh.calls
    aws.assert_untouched()


def test_missing_preflight_token_fails(aws):
    environ = {"LAB_ENABLED": "true", "GITHUB_REPOSITORY": REPO}
    gh = FakeGh(good_github())
    assert_failed(run(aws, gh_actions=gh, gh_env=gh, environ=environ), "lab-teardown environment")
    assert gh.calls == []
    aws.assert_untouched()


def test_required_reviewer_fails(aws):
    responses = good_github()
    responses[("GET", ENV)] = env_response([
        {"id": 1, "type": "required_reviewers", "prevent_self_review": False,
         "reviewers": [{"type": "User", "reviewer": {"login": SENTINEL}}]},
        {"id": 2, "type": "branch_policy"},
    ])
    env_failure(aws, responses)


def test_wait_timer_fails(aws):
    responses = good_github()
    responses[("GET", ENV)] = env_response([{"id": 1, "type": "wait_timer", "wait_timer": 5},
                                            {"id": 2, "type": "branch_policy"}])
    env_failure(aws, responses)


def test_zero_wait_timer_passes(aws):
    responses = good_github()
    responses[("GET", ENV)] = env_response([{"id": 1, "type": "wait_timer", "wait_timer": 0},
                                            {"id": 2, "type": "branch_policy"}])
    gh = FakeGh(responses)
    code, _, err = run(aws, gh_actions=gh, gh_env=gh, argv=["--github-only"])
    assert code == 0, err


def test_unknown_protection_rule_fails_closed(aws):
    responses = good_github()
    responses[("GET", ENV)] = env_response([{"id": 1, "type": "some_future_gate"}])
    env_failure(aws, responses)


def test_missing_protection_rules_fails_closed(aws):
    responses = good_github()
    body = env_response()
    del body["protection_rules"]
    responses[("GET", ENV)] = body
    env_failure(aws, responses)


@pytest.mark.parametrize("policy", ["none", "protected"])
def test_branch_policy_must_be_custom(aws, policy):
    responses = good_github()
    responses[("GET", ENV)] = env_response(policy=policy)
    env_failure(aws, responses)


def test_two_branch_policies_fail(aws):
    responses = good_github()
    responses[("GET", POLICIES)] = policies_response("main", "develop")
    env_failure(aws, responses)


def test_zero_branch_policies_fail(aws):
    responses = good_github()
    responses[("GET", POLICIES)] = policies_response()
    env_failure(aws, responses)


def test_single_non_main_policy_fails(aws):
    responses = good_github()
    responses[("GET", POLICIES)] = policies_response("main*")
    env_failure(aws, responses)


def test_tag_policy_named_main_fails(aws):
    responses = good_github()
    responses[("GET", POLICIES)] = policies_response("main", type_="tag")
    env_failure(aws, responses)


def test_total_count_must_agree(aws):
    responses = good_github()
    body = policies_response("main")
    body["total_count"] = 2  # a second page exists
    responses[("GET", POLICIES)] = body
    env_failure(aws, responses)


def test_environment_api_error_fails(aws):
    responses = good_github()
    responses[("GET", ENV)] = GhError("gh api GET environments/lab-teardown failed (exit 1)")
    env_failure(aws, responses)


# --- step 3: lab-teardown.yml enabled -------------------------------------------------

@pytest.mark.parametrize("state", ["disabled_manually", "disabled_inactivity", None])
def test_workflow_not_active_after_enable_fails(aws, state):
    responses = good_github()
    responses[("GET", WORKFLOW)] = {"id": 7, "state": state}
    gh = FakeGh(responses)
    assert_failed(run(aws, gh_actions=gh, gh_env=gh), "lab-teardown.yml")
    assert ("PUT", f"{WORKFLOW}/enable") in gh.calls
    aws.assert_untouched()


def test_enable_error_fails(aws):
    responses = good_github()
    responses[("PUT", f"{WORKFLOW}/enable")] = GhError("gh api PUT enable failed (exit 1)")
    gh = FakeGh(responses)
    assert_failed(run(aws, gh_actions=gh, gh_env=gh), "lab-teardown.yml")
    aws.assert_untouched()


# --- step 4: no teardown run queued or in progress ------------------------------------

@pytest.mark.parametrize("status", ["queued", "in_progress"])
def test_active_teardown_run_fails(aws, status):
    responses = good_github()
    responses[("GET", f"{WORKFLOW}/runs?status={status}&per_page=1")] = runs_response(1)
    gh = FakeGh(responses)
    result = run(aws, gh_actions=gh, gh_env=gh)
    assert_failed(result, "lab-teardown runs")
    assert status in result[2]
    aws.assert_untouched()


def test_unreadable_run_count_fails(aws):
    responses = good_github()
    responses[("GET", f"{WORKFLOW}/runs?status=queued&per_page=1")] = {"workflow_runs": []}
    gh = FakeGh(responses)
    assert_failed(run(aws, gh_actions=gh, gh_env=gh), "lab-teardown runs")
    aws.assert_untouched()


# --- step 5: the reaper schedule ------------------------------------------------------

def test_reaper_disabled_fails_and_ssm_untouched(aws):
    aws.expect_reaper("DISABLED")
    assert_failed(run(aws), "reaper schedule")
    assert aws.calls == ["GetSchedule"]


def test_reaper_missing_fails_and_ssm_untouched(aws):
    aws.scheduler_stub.add_client_error("get_schedule", service_error_code="ResourceNotFoundException",
                                        service_message=SENTINEL, http_status_code=404)
    result = run(aws)
    assert_failed(result, "reaper schedule")
    assert "ResourceNotFoundException" in result[2]
    assert aws.calls == ["GetSchedule"]


# --- step 6: the TTL ------------------------------------------------------------------

@pytest.mark.parametrize("ttl", ["12.01", "13", "0", "-1", "nan", "inf", "four"])
def test_bad_ttl_fails_before_anything(aws, ttl):
    gh = FakeGh(good_github())
    assert_failed(run(aws, gh_actions=gh, gh_env=gh, argv=["--ttl-hours", ttl]), "TTL")
    assert gh.calls == []
    aws.assert_untouched()


# --- GhClient -------------------------------------------------------------------------

class FakeRunner:
    def __init__(self, returncode=0, stdout="", stderr=""):
        self.result = subprocess.CompletedProcess([], returncode, stdout, stderr)
        self.calls = []

    def __call__(self, args, **kwargs):
        self.calls.append((args, kwargs))
        return self.result


def test_gh_client_passes_token_via_env_and_parses_json():
    runner = FakeRunner(stdout=json.dumps({"state": "active"}))
    client = GhClient(token="t0ken", runner=runner)
    assert client.api("repos/o/r/actions/workflows/x.yml") == {"state": "active"}
    args, kwargs = runner.calls[0]
    assert args[:2] == ["gh", "api"]
    assert args[-1] == "repos/o/r/actions/workflows/x.yml"
    assert "t0ken" not in args
    assert kwargs["env"]["GH_TOKEN"] == "t0ken"
    assert kwargs["timeout"] > 0


def test_gh_client_without_token_inherits_gh_auth(monkeypatch):
    monkeypatch.delenv("GH_TOKEN", raising=False)
    runner = FakeRunner(stdout="{}")
    GhClient(runner=runner).api("repos/o/r", method="PUT")
    args, kwargs = runner.calls[0]
    assert "GH_TOKEN" not in kwargs["env"]
    assert args[args.index("--method") + 1] == "PUT"


def test_gh_client_empty_body_is_empty_dict():
    assert GhClient(runner=FakeRunner(stdout="")).api("x", method="PUT") == {}


def test_gh_client_error_omits_response_body():
    runner = FakeRunner(returncode=1, stdout=SENTINEL, stderr=f"HTTP 404: Not Found\n{SENTINEL}")
    with pytest.raises(GhError) as exc:
        GhClient(runner=runner).api("repos/o/r/environments/lab-teardown")
    assert "HTTP 404: Not Found" in str(exc.value)
    assert SENTINEL not in str(exc.value)


def test_gh_client_timeout_and_missing_gh_raise_gh_error():
    def timeout(*_a, **_k):
        raise subprocess.TimeoutExpired("gh", 60)

    def missing(*_a, **_k):
        raise FileNotFoundError("gh")

    for runner in (timeout, missing):
        with pytest.raises(GhError):
            GhClient(runner=runner).api("x")


def test_gh_client_bad_json_raises_gh_error():
    with pytest.raises(GhError):
        GhClient(runner=FakeRunner(stdout="<html>")).api("x")
