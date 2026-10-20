"""Static checks on .github/workflows/ci.yml (L1-rest B: CI hygiene).

Third-party actions must be pinned to commit SHAs, and the Dependency-Check job must scan
the real runtime JARs with a CVSS threshold. PyYAML reads the `on:` key as boolean True.

Red commit (SHA-pin test): bac1c09
"""
import re
from pathlib import Path

import pytest
import yaml

REPO = Path(__file__).resolve().parents[3]
WORKFLOWS = REPO / ".github" / "workflows"
CI_FILE = WORKFLOWS / "ci.yml"

SHA_PIN = re.compile(r"^[\w.-]+/[\w./-]+@[0-9a-f]{40}$")


def _load(path):
    with path.open(encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def _steps(job):
    return job.get("steps", [])


@pytest.fixture(scope="module")
def ci():
    return _load(CI_FILE)


def _third_party_refs(workflow):
    refs = []
    for job in workflow["jobs"].values():
        for step in _steps(job):
            uses = step.get("uses")
            if uses and not uses.startswith("actions/"):
                refs.append(uses)
    return refs


def _dc_job(ci):
    return ci["jobs"]["dependency-scan"]


def _dc_step_index(job):
    for i, step in enumerate(_steps(job)):
        if step.get("uses", "").startswith("dependency-check/Dependency-Check_Action@"):
            return i
    pytest.fail("no Dependency-Check step in dependency-scan")


def test_dependency_scan_builds_runtime_jars_before_scanning(ci):
    job = _dc_job(ci)
    steps = _steps(job)
    dc = _dc_step_index(job)
    builds = [
        i for i, s in enumerate(steps)
        if "dependency:copy-dependencies" in s.get("run", "")
        and "-DincludeScope=runtime" in s.get("run", "")
    ]
    assert builds, "no step copies runtime-scope dependencies"
    assert builds[0] < dc
    assert str(steps[dc]["with"]["path"]).rstrip("/").endswith("target/dependency")


def test_dependency_scan_fails_on_cvss_7_with_suppressions(ci):
    job = _dc_job(ci)
    args = _steps(job)[_dc_step_index(job)]["with"]["args"]
    assert "--failOnCVSS 7" in args
    match = re.search(r"--suppression\s+(\S+)", args)
    assert match, "no --suppression argument"
    assert match.group(1).endswith("backend/dependency-check-suppressions.xml")
    assert (REPO / "backend" / "dependency-check-suppressions.xml").is_file()


def test_dependency_scan_uploads_report(ci):
    job = _dc_job(ci)
    out = _steps(job)[_dc_step_index(job)]["with"]["out"].rstrip("/")
    uploads = [
        s for s in _steps(job)
        if s.get("uses", "").startswith("actions/upload-artifact@")
        and s.get("if") == "always()"
        and str(s["with"]["path"]).rstrip("/") == out
    ]
    assert uploads, "no always() upload of the Dependency-Check out directory"


def test_ci_third_party_actions_are_sha_pinned(ci):
    refs = _third_party_refs(ci)
    assert refs, "expected third-party actions in ci.yml"
    owners = {ref.split("/")[0] for ref in refs}
    assert {"dependency-check", "aquasecurity"} <= owners
    unpinned = [ref for ref in refs if not SHA_PIN.match(ref)]
    assert not unpinned, f"third-party actions not pinned to a commit SHA: {unpinned}"
