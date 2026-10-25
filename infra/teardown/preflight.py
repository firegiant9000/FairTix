"""teardown.preflight: lab-apply's checks before any Terraform step (docs/infra/teardown-design.md §3).

    python -m teardown.preflight [--github-only] [--ttl-hours N]     (run from infra/)

In order; the first failure exits 1, and every GitHub check runs before any AWS call:
  1. LAB_ENABLED is exactly 'true' (FT-7).
  2. The lab-teardown environment (read with LAB_PREFLIGHT_TOKEN, FT-8) has no required
     reviewers, no wait timer, and exactly one deployment-branch policy: main.
  3. lab-teardown.yml is re-enabled and reports state 'active'.
  4. No lab-teardown run is queued or in progress.
  5. The reaper's EventBridge schedule is ENABLED.
     5b. The reaper is out of test mode, with a grace of at least 120 minutes.
     5c. Its alert topic has a confirmed subscription, so its emails reach someone.
  6. SSM /fairtix-lab/expires_at = now + TTL (default 4 h, at most 12 h). Only then does
     it report success, so the expiry exists before Terraform creates anything.

--github-only stops after step 4 and never creates an AWS client (T3). The TTL argument
is validated before step 1. Output names the failed check and a reason, never a full
API response.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from .census import EXPIRES_AT_PARAMETER, HOME_REGION
from .decide import _describe
from .teardown_policy import MAX_TTL

DEFAULT_REPO = "firegiant9000/FairTix"
TEARDOWN_ENVIRONMENT = "lab-teardown"
TEARDOWN_WORKFLOW = "lab-teardown.yml"
# Bootstrap naming contract (teardown/README.md): FT-3 creates the schedule with this name.
REAPER_SCHEDULE = "fairtix-lab-bootstrap-reaper"
REAPER_SCHEDULE_GROUP = "default"
REAPER_FUNCTION = REAPER_SCHEDULE  # the Lambda has the same name
MIN_REAPER_GRACE_MINUTES = 120
DEFAULT_TTL_HOURS = 4.0
GH_TIMEOUT_SECONDS = 60


class GhError(Exception):
    """A gh call failed. The message carries gh's first stderr line, never the response body."""


class GhClient:
    """`gh api` through subprocess. With a token, the call uses it (GH_TOKEN); without one,
    gh's own auth (GH_TOKEN from the job, or a local `gh auth login`)."""

    def __init__(self, token: str | None = None, runner=subprocess.run):
        self._token = token
        self._runner = runner

    def api(self, path: str, method: str = "GET") -> dict:
        env = dict(os.environ)
        if self._token:
            env["GH_TOKEN"] = self._token
        args = ["gh", "api", "--method", method, "-H", "Accept: application/vnd.github+json", path]
        try:
            proc = self._runner(args, capture_output=True, text=True, env=env, timeout=GH_TIMEOUT_SECONDS)
        except (OSError, subprocess.SubprocessError) as exc:
            raise GhError(f"gh api {method} {path} could not run ({type(exc).__name__})") from None
        if proc.returncode != 0:
            first = (proc.stderr or "").strip().splitlines()[:1]
            detail = f": {first[0][:200]}" if first else ""
            raise GhError(f"gh api {method} {path} failed (exit {proc.returncode}){detail}")
        if not (proc.stdout or "").strip():
            return {}
        try:
            return json.loads(proc.stdout)
        except ValueError:
            raise GhError(f"gh api {method} {path} returned a body that isn't JSON") from None


@dataclass(frozen=True)
class AwsClients:
    scheduler: object
    ssm: object
    lambda_: object
    sns: object


def make_aws_clients(session=None) -> AwsClients:
    import boto3

    session = session or boto3.session.Session()
    return AwsClients(
        scheduler=session.client("scheduler", region_name=HOME_REGION),
        ssm=session.client("ssm", region_name=HOME_REGION),
        lambda_=session.client("lambda", region_name=HOME_REGION),
        sns=session.client("sns", region_name=HOME_REGION),
    )


class PreflightFailed(Exception):
    def __init__(self, check: str, reason: str):
        super().__init__(f"{check}: {reason}")
        self.check = check
        self.reason = reason


def parse_ttl(raw: str) -> timedelta:
    try:
        hours = float(raw)
    except ValueError:
        raise PreflightFailed("TTL", "--ttl-hours is not a number") from None
    if not math.isfinite(hours) or hours <= 0:
        raise PreflightFailed("TTL", "--ttl-hours must be a positive number of hours")
    ttl = timedelta(hours=hours)
    if ttl > MAX_TTL:
        raise PreflightFailed("TTL", f"--ttl-hours {hours:g} is over the {MAX_TTL.total_seconds() / 3600:g} h maximum")
    return ttl


def check_lab_enabled(value) -> None:
    # Exact match on purpose: stricter than the workflow's case-insensitive `if:`, so a
    # value that would leave teardown enabled can never let apply through by accident.
    if value != "true":
        raise PreflightFailed("LAB_ENABLED", "the repository variable LAB_ENABLED is not 'true'")


def check_teardown_environment(gh_env, repo: str) -> None:
    check = "lab-teardown environment"
    if gh_env is None:
        raise PreflightFailed(check, "LAB_PREFLIGHT_TOKEN is not set")
    base = f"repos/{repo}/environments/{TEARDOWN_ENVIRONMENT}"
    try:
        env = gh_env.api(base)
    except GhError as exc:
        raise PreflightFailed(check, str(exc)) from None

    rules = env.get("protection_rules")
    if not isinstance(rules, list):
        raise PreflightFailed(check, "protection_rules missing from the environment API response")
    for rule in rules:
        kind = rule.get("type") if isinstance(rule, dict) else None
        if kind == "required_reviewers":
            raise PreflightFailed(check, "has required reviewers; teardown would wait for approval")
        if kind == "wait_timer":
            if rule.get("wait_timer") != 0:
                raise PreflightFailed(check, "has a wait timer; teardown would be delayed")
        elif kind != "branch_policy":
            raise PreflightFailed(check, f"has an unrecognised protection rule ({kind!r}); failing closed")

    policy = env.get("deployment_branch_policy")
    if not (isinstance(policy, dict) and policy.get("custom_branch_policies") is True
            and policy.get("protected_branches") is False):
        raise PreflightFailed(check, "deployment branches must be a custom policy allowing only main")

    try:
        listing = gh_env.api(f"{base}/deployment-branch-policies")
    except GhError as exc:
        raise PreflightFailed(check, str(exc)) from None
    policies = listing.get("branch_policies")
    if not isinstance(policies, list) or listing.get("total_count") != 1 or len(policies) != 1:
        count = listing.get("total_count")
        raise PreflightFailed(check, f"expected exactly one deployment-branch policy, found {count!r}")
    only = policies[0] if isinstance(policies[0], dict) else {}
    if only.get("name") != "main" or only.get("type", "branch") != "branch":
        raise PreflightFailed(check, "the only deployment-branch policy must be the branch 'main'")


def ensure_teardown_workflow_active(gh, repo: str) -> None:
    check = "lab-teardown.yml"
    path = f"repos/{repo}/actions/workflows/{TEARDOWN_WORKFLOW}"
    try:
        gh.api(f"{path}/enable", method="PUT")
        state = gh.api(path).get("state")
    except GhError as exc:
        raise PreflightFailed(check, str(exc)) from None
    if state != "active":
        raise PreflightFailed(check, f"state is {state!r} after enabling it, not 'active'")


def check_no_teardown_run_active(gh, repo: str) -> None:
    check = "lab-teardown runs"
    for status in ("queued", "in_progress"):
        try:
            runs = gh.api(f"repos/{repo}/actions/workflows/{TEARDOWN_WORKFLOW}/runs?status={status}&per_page=1")
        except GhError as exc:
            raise PreflightFailed(check, str(exc)) from None
        count = runs.get("total_count")
        if not isinstance(count, int) or isinstance(count, bool):
            raise PreflightFailed(check, f"could not read the {status} run count")
        if count:
            raise PreflightFailed(check, f"a lab-teardown run is {status}; wait for it to finish")


def check_reaper_enabled(scheduler) -> None:
    check = "reaper schedule"
    try:
        state = scheduler.get_schedule(Name=REAPER_SCHEDULE, GroupName=REAPER_SCHEDULE_GROUP).get("State")
    except Exception as exc:  # noqa: BLE001 - unreadable means not proven enabled
        raise PreflightFailed(check, f"{REAPER_SCHEDULE} unreadable ({_describe(exc)})") from None
    if state != "ENABLED":
        raise PreflightFailed(check, f"{REAPER_SCHEDULE} is {state!r}, not 'ENABLED'")


def check_reaper_settings(lambda_) -> str:
    """The reaper is out of T5's test mode, with the full grace. Returns its alert topic ARN.

    A forgotten `reaper_test_mode` would reap a real lab 5 minutes after expiry, before
    layer 2 gets its turn (TD:81).
    """
    check = "reaper settings"
    try:
        config = lambda_.get_function_configuration(FunctionName=REAPER_FUNCTION)
    except Exception as exc:  # noqa: BLE001 - unreadable means not proven safe
        raise PreflightFailed(check, f"{REAPER_FUNCTION} unreadable ({_describe(exc)})") from None
    variables = (config.get("Environment") or {}).get("Variables") or {}
    if variables.get("REAPER_TEST_MODE") != "false":
        raise PreflightFailed(check, "the reaper is in test mode; re-apply infra/bootstrap without reaper_test_mode")
    grace = variables.get("REAPER_GRACE_MINUTES", "")
    if not grace.isdigit() or int(grace) < MIN_REAPER_GRACE_MINUTES:
        raise PreflightFailed(check, f"the reaper's grace is under {MIN_REAPER_GRACE_MINUTES} minutes")
    topic = variables.get("REAPER_SNS_TOPIC_ARN")
    if not topic:
        raise PreflightFailed(check, "the reaper has no alert topic")
    return topic


def check_alerts_confirmed(sns, topic_arn: str) -> None:
    """Someone receives the reaper's email and the Errors alarm: a confirmed subscription exists."""
    check = "alert subscription"
    try:
        subscriptions = [
            s for page in sns.get_paginator("list_subscriptions_by_topic").paginate(TopicArn=topic_arn)
            for s in page["Subscriptions"]
        ]
    except Exception as exc:  # noqa: BLE001
        raise PreflightFailed(check, f"the alert topic's subscriptions are unreadable ({_describe(exc)})") from None
    # Unconfirmed and removed subscriptions report a placeholder instead of an ARN.
    if not any(str(s.get("SubscriptionArn", "")).startswith("arn:") for s in subscriptions):
        raise PreflightFailed(check, "no confirmed subscription on the alert topic; confirm the email from AWS")


def write_expiry(ssm, now: datetime, ttl: timedelta) -> str:
    value = (now + ttl).astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    try:
        ssm.put_parameter(Name=EXPIRES_AT_PARAMETER, Value=value, Type="String", Overwrite=True)
    except Exception as exc:  # noqa: BLE001
        raise PreflightFailed("expiry", f"could not write {EXPIRES_AT_PARAMETER} ({_describe(exc)})") from None
    return value


def run(*, repo, lab_enabled, gh_env, gh_actions, ttl_raw, github_only, aws_clients, now, out) -> None:
    ttl = parse_ttl(ttl_raw)
    check_lab_enabled(lab_enabled)
    print("preflight: [1] LAB_ENABLED is 'true'", file=out)
    check_teardown_environment(gh_env, repo)
    print("preflight: [2] lab-teardown has no reviewers or wait timer; branch policy is main only", file=out)
    ensure_teardown_workflow_active(gh_actions, repo)
    print("preflight: [3] lab-teardown.yml is active", file=out)
    check_no_teardown_run_active(gh_actions, repo)
    print("preflight: [4] no lab-teardown run is queued or in progress", file=out)
    if github_only:
        print("preflight: GitHub checks passed (--github-only: no AWS call made)", file=out)
        return

    clients = aws_clients()
    check_reaper_enabled(clients.scheduler)
    print(f"preflight: [5] {REAPER_SCHEDULE} is ENABLED", file=out)
    topic = check_reaper_settings(clients.lambda_)
    print("preflight: [5b] the reaper is out of test mode, with the full grace", file=out)
    check_alerts_confirmed(clients.sns, topic)
    print("preflight: [5c] the alert topic has a confirmed subscription", file=out)
    expires_at = write_expiry(clients.ssm, now() if callable(now) else now, ttl)
    print(f"preflight passed: expires_at = {expires_at}", file=out)


def main(argv=None, *, environ=None, gh_env=None, gh_actions=None, aws_clients=None, now=None,
         out=sys.stdout, err=sys.stderr) -> int:
    parser = argparse.ArgumentParser(prog="python -m teardown.preflight")
    parser.add_argument("--github-only", action="store_true", help="run steps 1-4 only; no AWS call (T3)")
    parser.add_argument("--ttl-hours", default=str(DEFAULT_TTL_HOURS), help="lab lifetime, at most 12")
    args = parser.parse_args(argv)

    environ = os.environ if environ is None else environ
    # The environment checks need the fine-grained token itself. Falling back to gh's own
    # auth (often an admin locally) would hide a token that can't read the environment.
    token = environ.get("LAB_PREFLIGHT_TOKEN")
    if not token:
        gh_env = None
    elif gh_env is None:
        gh_env = GhClient(token=token)
    try:
        run(
            repo=environ.get("GITHUB_REPOSITORY") or DEFAULT_REPO,
            lab_enabled=environ.get("LAB_ENABLED"),
            gh_env=gh_env,
            gh_actions=gh_actions or GhClient(),
            ttl_raw=args.ttl_hours,
            github_only=args.github_only,
            aws_clients=aws_clients or make_aws_clients,
            now=now or (lambda: datetime.now(timezone.utc)),
            out=out,
        )
    except PreflightFailed as exc:
        print(f"preflight FAILED at {exc.check}: {exc.reason}", file=err)
        return 1
    except Exception as exc:  # noqa: BLE001 - anything unexpected blocks the apply
        print(f"preflight FAILED at unexpected error: {type(exc).__name__}", file=err)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
