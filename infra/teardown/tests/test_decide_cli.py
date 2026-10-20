"""The `python -m teardown.decide` CLI (layer 2). SSM and the census are stubbed."""
import io
from datetime import datetime, timedelta, timezone

import pytest

from teardown import decide as d
from teardown.census import CensusError
from teardown.teardown_policy import Census, Item

from .conftest import ACCOUNT_ID, stub_account, stub_client

UTC = timezone.utc
NOW = datetime(2026, 10, 20, 12, 0, tzinfo=UTC)
PARAM = {"Name": "/fairtix-lab/expires_at"}


@pytest.fixture
def ssm():
    client, stubber = stub_client("ssm")
    yield client, stubber
    stubber.deactivate()


def ssm_value(stubber, value):
    stubber.add_response("get_parameter", {"Parameter": {"Name": PARAM["Name"], "Value": value}}, PARAM)


def lab_up():
    return Census(billable=(Item("rds_instance", "fairtix-lab-db", NOW - timedelta(hours=1)),))


def run(ssm_client, census_fn, now=NOW):
    out, err = io.StringIO(), io.StringIO()
    decision = d.run(now, ssm_client, census_fn, out=out, err=err)
    return decision, out.getvalue(), err.getvalue()


def test_future_expiry_with_lab_up_prints_noop(ssm):
    client, stubber = ssm
    ssm_value(stubber, "2026-10-20T15:00:00Z")
    decision, out, err = run(client, lab_up)
    assert (decision, out) == ("noop", "noop\n")
    assert err == ""
    stubber.assert_no_pending_responses()


def test_past_expiry_with_lab_up_prints_destroy(ssm):
    client, stubber = ssm
    ssm_value(stubber, "2026-10-20T11:59:59Z")
    assert run(client, lab_up)[1] == "destroy\n"


def test_expiry_none_with_empty_lab_prints_noop(ssm):
    client, stubber = ssm
    ssm_value(stubber, "none")
    assert run(client, lambda: Census())[1] == "noop\n"


def test_census_error_prints_destroy_with_a_warning(ssm):
    client, stubber = ssm
    ssm_value(stubber, "2026-10-20T15:00:00Z")

    def failing():
        raise CensusError("list_rds: Throttling")

    decision, out, err = run(client, failing)
    assert (decision, out) == ("destroy", "destroy\n")
    assert "WARNING" in err and "list_rds: Throttling" in err


def test_unexpected_census_exception_also_prints_destroy(ssm):
    client, stubber = ssm
    ssm_value(stubber, "2026-10-20T15:00:00Z")

    def failing():
        raise KeyError("x")

    assert run(client, failing)[1] == "destroy\n"


def test_missing_parameter_with_lab_up_prints_destroy(ssm):
    client, stubber = ssm
    stubber.add_client_error("get_parameter", service_error_code="ParameterNotFound", http_status_code=400,
                             expected_params=PARAM)
    decision, out, err = run(client, lab_up)
    assert out == "destroy\n"
    assert "ParameterNotFound" in err


def test_unreadable_parameter_is_treated_as_missing_and_hides_the_message(ssm):
    client, stubber = ssm
    stubber.add_client_error(
        "get_parameter", service_error_code="AccessDeniedException", http_status_code=400,
        service_message=f"User: arn:aws:sts::{ACCOUNT_ID}:assumed-role/x is not authorized")
    decision, out, err = run(client, lab_up)
    assert out == "destroy\n"
    assert "AccessDeniedException" in err
    assert ACCOUNT_ID not in err


def test_missing_parameter_with_empty_lab_prints_noop(ssm):
    client, stubber = ssm
    stubber.add_client_error("get_parameter", service_error_code="ParameterNotFound", http_status_code=400)
    assert run(client, lambda: Census())[1] == "noop\n"


def test_census_is_built_from_the_full_census(ssm, stubs):
    # Free-only leftovers past expiry: layer 2 (full census) destroys.
    client, stubber = ssm
    ssm_value(stubber, "2026-10-20T09:00:00Z")
    stub_account(stubs, {("ecr", "describe_repositories"): {"repositories": [{"repositoryName": "fairtix-lab-backend"}]}})
    out, err = io.StringIO(), io.StringIO()
    code = d.main([], ssm=client, clients=stubs.as_clients(), now=NOW, out=out, err=err)
    assert code == 0
    assert out.getvalue() == "destroy\n"
    stubs.assert_done()


def test_main_failure_before_the_census_still_prints_destroy():
    def no_credentials():
        raise RuntimeError("Unable to locate credentials")

    out, err = io.StringIO(), io.StringIO()
    code = d.main([], make_clients=no_credentials, now=NOW, out=out, err=err)
    assert code == 0
    assert out.getvalue() == "destroy\n"
    assert "WARNING" in err.getvalue()


def test_main_uses_utc_wall_clock_when_now_not_given(ssm, monkeypatch):
    client, stubber = ssm
    ssm_value(stubber, "2000-01-01T00:00:00Z")
    out, err = io.StringIO(), io.StringIO()
    d.main([], ssm=client, census_fn=lab_up, out=out, err=err)
    assert out.getvalue() == "destroy\n"
