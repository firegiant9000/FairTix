"""T2 (docs/infra/teardown-design.md §5): static checks on the two lab workflows.

actionlint runs in CI (infra.yml). These tests parse the YAML and assert the T2 pass
conditions, plus the guards stage B adds (FT-7's LAB_ENABLED gate, SHA pins, no secrets
in `run:`, the preflight before Terraform). PyYAML reads the `on:` key as boolean True.
"""
import re
from pathlib import Path

import pytest
import yaml

WORKFLOWS = Path(__file__).resolve().parents[3] / ".github" / "workflows"
TEARDOWN_FILE = WORKFLOWS / "lab-teardown.yml"
APPLY_FILE = WORKFLOWS / "lab-apply.yml"

SHA_PIN = re.compile(r"^[\w.-]+/[\w./-]+@[0-9a-f]{40}$")
SECRETS_EXPR = re.compile(r"\$\{\{[^}]*\bsecrets\.")


def _load(path):
    with path.open(encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def _triggers(workflow):
    # YAML 1.1 reads a bare `on` key as boolean True.
    return workflow.get("on", workflow.get(True))


def _steps(job):
    return job.get("steps", [])


def _is_terraform(step):
    return "terraform" in step.get("uses", "") or re.search(r"\bterraform\b", step.get("run", "")) is not None


@pytest.fixture(scope="module")
def teardown():
    return _load(TEARDOWN_FILE)


@pytest.fixture(scope="module")
def apply():
    return _load(APPLY_FILE)


@pytest.fixture(scope="module")
def teardown_job(teardown):
    return teardown["jobs"]["teardown"]


@pytest.fixture(scope="module")
def apply_job(apply):
    return apply["jobs"]["apply"]


# --- T2 conditions, verbatim from TD:151 ---------------------------------------------

def test_t2_teardown_job_environment_is_lab_teardown(teardown_job):
    assert teardown_job["environment"] == "lab-teardown"


def test_t2_teardown_concurrency_group_is_fairtix_lab_teardown(teardown):
    assert teardown["concurrency"]["group"] == "fairtix-lab-teardown"


def test_t2_teardown_concurrency_group_differs_from_apply(teardown, apply):
    assert apply["concurrency"]["group"] == "fairtix-lab-apply"
    assert teardown["concurrency"]["group"] != apply["concurrency"]["group"]


def test_t2_cancel_in_progress_false_on_both(teardown, apply):
    assert teardown["concurrency"]["cancel-in-progress"] is False
    assert apply["concurrency"]["cancel-in-progress"] is False


def test_t2_destroy_step_passes_lock_timeout(teardown_job):
    destroys = [s for s in _steps(teardown_job) if "destroy" in s.get("run", "") and "terraform" in s.get("run", "")]
    assert destroys, "no terraform destroy step"
    for step in destroys:
        for line in step["run"].splitlines():
            if re.search(r"\bterraform\b.*\bdestroy\b", line):
                assert "-lock-timeout=" in line, line


def test_t2_schedule_exists(teardown):
    schedule = _triggers(teardown)["schedule"]
    assert schedule == [{"cron": "17 * * * *"}]


def test_t2_teardown_has_id_token_write(teardown):
    assert teardown["permissions"]["id-token"] == "write"


def test_t2_apply_has_actions_write(apply):
    assert apply["permissions"]["actions"] == "write"


# --- Stage B guards ------------------------------------------------------------------

def test_teardown_job_is_gated_on_lab_enabled(teardown_job):
    # FT-7: until Arlo enables the lab, the hourly schedule is a skip, not a failure.
    assert teardown_job["if"].replace(" ", "") == "vars.LAB_ENABLED=='true'"


def test_apply_reads_lab_enabled_at_repository_scope(apply, apply_job):
    # An environment-level LAB_ENABLED on lab-apply would override the repository one, so
    # apply reads it from a job with no environment, the same scope teardown's `if:` sees.
    gate = apply["jobs"]["gate"]
    assert "environment" not in gate
    assert gate["outputs"]["lab_enabled"] == "${{ vars.LAB_ENABLED }}"
    assert apply_job["needs"] == "gate"
    assert apply_job["env"]["LAB_ENABLED"] == "${{ needs.gate.outputs.lab_enabled }}"
    for job in apply["jobs"].values():
        for step in _steps(job):
            assert "vars.LAB_ENABLED" not in step.get("run", "") + str(step.get("env", ""))


@pytest.mark.parametrize("path,command", [(TEARDOWN_FILE, "destroy"), (APPLY_FILE, "apply")],
                         ids=["destroy", "apply"])
def test_terraform_plan_output_stays_out_of_the_public_log(path, command):
    workflow = _load(path)
    runs = [s["run"] for job in workflow["jobs"].values() for s in _steps(job)
            if re.search(rf"\bterraform\b.*\s{command}\s", s.get("run", ""))]
    assert runs
    for run in runs:
        lines = run.splitlines()
        tf = next(i for i, line in enumerate(lines) if re.search(rf"\bterraform\b.*\s{command}\s", line))
        assert '> "$RUNNER_TEMP/' in lines[tf] and "2>&1" in lines[tf], lines[tf]
        assert "tee" not in lines[tf], lines[tf]
        # The summary and the errors still reach the log, after the command.
        summary = [i for i, line in enumerate(lines)
                   if "grep" in line and "Error:" in line and f"{command.capitalize()} complete" in line]
        assert summary and summary[0] > tf
        # Unanchored: Terraform diagnostics can carry a "│ " box prefix, so
        # '^Error:' would miss them.
        assert "^" not in lines[summary[0]], lines[summary[0]]


def test_teardown_triggers_are_schedule_and_dispatch_with_force(teardown):
    triggers = _triggers(teardown)
    assert set(triggers) == {"schedule", "workflow_dispatch"}
    force = triggers["workflow_dispatch"]["inputs"]["force"]
    assert force["type"] == "boolean"
    assert force["default"] is False


def test_teardown_permissions_and_timeout(teardown, teardown_job):
    assert teardown["permissions"] == {"id-token": "write", "contents": "read", "issues": "write"}
    assert teardown_job["timeout-minutes"] == 45
    assert "permissions" not in teardown_job


def test_apply_is_dispatch_only(apply):
    assert set(_triggers(apply)) == {"workflow_dispatch"}


def test_apply_permissions_and_environment(apply, apply_job):
    assert apply["permissions"] == {"id-token": "write", "contents": "read", "actions": "write"}
    assert apply_job["environment"] == "lab-apply"
    assert "permissions" not in apply_job


def test_issues_write_only_on_teardown_and_actions_write_only_on_apply(teardown, apply):
    assert "actions" not in teardown["permissions"]
    assert "issues" not in apply["permissions"]


@pytest.mark.parametrize("path", [TEARDOWN_FILE, APPLY_FILE], ids=lambda p: p.name)
def test_every_uses_is_sha_pinned(path):
    workflow = _load(path)
    uses = [s["uses"] for job in workflow["jobs"].values() for s in _steps(job) if "uses" in s]
    assert uses
    for ref in uses:
        assert SHA_PIN.match(ref), f"{ref} is not pinned to a full commit SHA"


@pytest.mark.parametrize("path", [TEARDOWN_FILE, APPLY_FILE], ids=lambda p: p.name)
def test_no_secrets_expression_inside_run(path):
    workflow = _load(path)
    for job in workflow["jobs"].values():
        for step in _steps(job):
            assert not SECRETS_EXPR.search(step.get("run", "")), f"secrets in run: of {step.get('name')}"


@pytest.mark.parametrize("path", [TEARDOWN_FILE, APPLY_FILE], ids=lambda p: p.name)
def test_pip_installs_require_hashes_from_the_lock(path):
    # These jobs hold id-token: write, so no transitive dependency may float (FT-3 carry-in).
    workflow = _load(path)
    installs = [s["run"] for job in workflow["jobs"].values() for s in _steps(job)
                if re.search(r"\bpip\b.*\binstall\b", s.get("run", ""))]
    assert installs
    for run in installs:
        assert "--require-hashes" in run, run
        assert "-r teardown/requirements.lock" in run, run


def test_the_lock_pins_every_top_level_requirement():
    teardown_dir = WORKFLOWS.parents[1] / "infra" / "teardown"
    lock = (teardown_dir / "requirements.lock").read_text(encoding="utf-8")
    pins = [line.strip() for line in (teardown_dir / "requirements.txt").read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")]
    assert pins
    for pin in pins:
        assert re.search(rf"^{re.escape(pin)} \\$", lock, re.MULTILINE), f"{pin} missing from the lock; regenerate it"
    entries = re.findall(r"^\S+==\S+ \\\n\s+--hash=sha256:[0-9a-f]{64}$", lock, re.MULTILINE)
    assert len(entries) == len(re.findall(r"^\S+==", lock, re.MULTILINE))


def test_apply_preflight_precedes_every_terraform_step(apply_job):
    steps = _steps(apply_job)
    preflights = [i for i, s in enumerate(steps) if "teardown.preflight" in s.get("run", "")]
    terraform = [i for i, s in enumerate(steps) if _is_terraform(s)]
    assert preflights, "no preflight step"
    assert terraform, "no terraform step"
    assert max(preflights) < min(terraform)


def test_apply_github_preflight_precedes_aws_credentials(apply_job):
    # The GitHub-side checks fail before OIDC, so a blocked apply makes no AWS call at all.
    steps = _steps(apply_job)
    github_only = [i for i, s in enumerate(steps) if "teardown.preflight --github-only" in s.get("run", "")]
    oidc = [i for i, s in enumerate(steps) if "configure-aws-credentials" in s.get("uses", "")]
    assert github_only and oidc
    assert github_only[0] < oidc[0]


def test_apply_step_fails_clearly_without_lab_stack(apply_job):
    apply_steps = [s for s in _steps(apply_job) if re.search(r"terraform\b.*\bapply\b", s.get("run", ""))]
    assert apply_steps
    assert "lab stack not present" in apply_steps[0]["run"]


def test_teardown_steps_run_decide_destroy_verify_reset_and_report(teardown_job):
    runs = [s.get("run", "") for s in _steps(teardown_job)]
    joined = "\n".join(runs)
    order = [
        joined.index("python -m teardown.decide"),
        joined.index('terraform -chdir="$stack" destroy'),
        joined.index("python -m teardown.verify"),
        joined.index("/fairtix-lab/expires_at"),
        joined.index("lab teardown failed"),
    ]
    assert order == sorted(order)


def test_teardown_oidc_runs_before_decide(teardown_job):
    steps = _steps(teardown_job)
    oidc = next(i for i, s in enumerate(steps) if "configure-aws-credentials" in s.get("uses", ""))
    decide = next(i for i, s in enumerate(steps) if "teardown.decide" in s.get("run", ""))
    assert oidc < decide
    assert steps[oidc]["with"]["mask-aws-account-id"] is True


def test_destroy_retries_once_on_dependency_violation(teardown_job):
    step = next(s for s in _steps(teardown_job) if 'terraform -chdir="$stack" destroy' in s.get("run", ""))
    assert "DependencyViolation" in step["run"]
    assert "sleep 90" in step["run"]


def test_destroy_covers_the_lab_then_the_canary(teardown_job):
    # T4/T5 need the scheduled run to destroy infra/canary; stage B adds infra/lab.
    step = next(s for s in _steps(teardown_job) if 'terraform -chdir="$stack" destroy' in s.get("run", ""))
    assert "for stack in infra/lab infra/canary; do" in step["run"]
    assert 'terraform -chdir="$stack" init -input=false -lockfile=readonly' in step["run"]
    assert 'exit "$failed"' in step["run"]


def test_verify_and_reset_only_after_destroy(teardown_job):
    for marker in ("python -m teardown.verify", "/fairtix-lab/expires_at", 'terraform -chdir="$stack" destroy'):
        step = next(s for s in _steps(teardown_job) if marker in s.get("run", ""))
        assert "steps.decide.outputs.decision == 'destroy'" in step["if"], marker


def test_failure_issue_step_runs_on_failure_or_timeout(teardown_job):
    step = next(s for s in _steps(teardown_job) if "lab teardown failed" in s.get("run", ""))
    assert "failure()" in step["if"]
    assert "cancelled()" in step["if"]
    assert "--label" in step["run"] and "lab-teardown" in step["run"]
