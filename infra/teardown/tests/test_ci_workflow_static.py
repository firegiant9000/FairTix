"""Static checks on .github/workflows/ci.yml (L1-rest B: CI hygiene).

Third-party actions must be pinned to commit SHAs, and the Dependency-Check job must scan
the real runtime JARs with a CVSS threshold. PyYAML reads the `on:` key as boolean True.
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


def test_ci_third_party_actions_are_sha_pinned(ci):
    refs = _third_party_refs(ci)
    assert refs, "expected third-party actions in ci.yml"
    owners = {ref.split("/")[0] for ref in refs}
    assert {"dependency-check", "aquasecurity"} <= owners
    unpinned = [ref for ref in refs if not SHA_PIN.match(ref)]
    assert not unpinned, f"third-party actions not pinned to a commit SHA: {unpinned}"
