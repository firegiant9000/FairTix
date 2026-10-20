"""teardown.verify tests (docs/infra/teardown-design.md §6). All AWS calls are stubbed."""
import io
from datetime import datetime, timezone

import pytest

from teardown import verify as v
from teardown.teardown_policy import Item

from .conftest import ACCOUNT_ID, stub_account, stub_client

UTC = timezone.utc
T0 = datetime(2026, 10, 20, 9, 0, tzinfo=UTC)


class Regions:
    """Stubbed per-region ec2/elbv2 clients for the cross-region sweep."""

    def __init__(self, names):
        self.clients = {}
        self.stubbers = {}
        for region in names:
            for service in ("ec2", "elbv2"):
                client, stubber = stub_client(service, region)
                self.clients[(service, region)] = client
                self.stubbers[(service, region)] = stubber

    def factory(self, service, region):
        return self.clients[(service, region)]

    def empty(self, region):
        self.stubbers[("ec2", region)].add_response("describe_network_interfaces", {"NetworkInterfaces": []})
        self.stubbers[("ec2", region)].add_response("describe_instances", {"Reservations": []})
        self.stubbers[("elbv2", region)].add_response("describe_load_balancers", {"LoadBalancers": []})

    def assert_done(self):
        for s in self.stubbers.values():
            s.assert_no_pending_responses()


def stub_home_extras(stubs, regions=("us-east-1", "eu-west-1"), instances=None):
    """The home-region calls verify adds on top of the census: instances, then regions."""
    stubs.add("ec2", "describe_instances", {"Reservations": instances or []})
    stubs.add("ec2", "describe_regions", {"Regions": [{"RegionName": r} for r in regions]})


def run(stubs, regions):
    out, err = io.StringIO(), io.StringIO()
    code = v.run(stubs.as_clients(), regions.factory, out=out, err=err)
    return code, out.getvalue(), err.getvalue()


# --- Clean and leftover paths ---------------------------------------------------------------

def test_clean_account_exits_zero(stubs):
    regions = Regions(["eu-west-1"])
    stub_account(stubs)
    stub_home_extras(stubs)
    regions.empty("eu-west-1")
    code, out, err = run(stubs, regions)
    assert code == 0
    assert "no lab resources found" in out
    assert err == ""
    stubs.assert_done()
    regions.assert_done()


def test_any_home_region_leftover_exits_nonzero_and_names_it(stubs):
    regions = Regions(["eu-west-1"])
    stub_account(stubs, {
        ("secretsmanager", "list_secrets"): {"SecretList": [{"Name": "fairtix-lab/db", "DeletedDate": T0}]},
        ("kms", "list_keys"): {"Keys": [], "Truncated": False},
        ("rds", "describe_db_snapshots"): {"DBSnapshots": [{"DBSnapshotIdentifier": "fairtix-lab-final"}]},
    })
    stub_home_extras(stubs)
    regions.empty("eu-west-1")
    code, out, _ = run(stubs, regions)
    assert code == 1
    assert "us-east-1 secret fairtix-lab/db (pending deletion)" in out
    assert "us-east-1 rds_snapshot fairtix-lab-final" in out


def test_home_region_instance_counts(stubs):
    regions = Regions(["eu-west-1"])
    stub_account(stubs)
    stub_home_extras(stubs, instances=[{"Instances": [
        {"InstanceId": "i-running", "State": {"Name": "running"}},
        {"InstanceId": "i-gone", "State": {"Name": "terminated"}},
    ]}])
    regions.empty("eu-west-1")
    code, out, _ = run(stubs, regions)
    assert code == 1
    assert "us-east-1 ec2_instance i-running" in out
    assert "i-gone" not in out


@pytest.mark.parametrize(
    "service,op,response,expected",
    [
        ("ec2", "describe_network_interfaces", {"NetworkInterfaces": [{"NetworkInterfaceId": "eni-9"}]},
         "eu-west-1 eni eni-9"),
        ("ec2", "describe_instances", {"Reservations": [{"Instances": [
            {"InstanceId": "i-9", "State": {"Name": "stopped"}}]}]}, "eu-west-1 ec2_instance i-9"),
        ("elbv2", "describe_load_balancers", {"LoadBalancers": [{"LoadBalancerName": "stray"}]},
         "eu-west-1 elb_load_balancer stray"),
    ],
)
def test_other_region_eni_instance_or_lb_exits_nonzero(stubs, service, op, response, expected):
    regions = Regions(["eu-west-1"])
    stub_account(stubs)
    stub_home_extras(stubs)
    plan = {
        ("ec2", "describe_network_interfaces"): {"NetworkInterfaces": []},
        ("ec2", "describe_instances"): {"Reservations": []},
        ("elbv2", "describe_load_balancers"): {"LoadBalancers": []},
    }
    plan[(service, op)] = response
    for (svc, operation), resp in plan.items():
        regions.stubbers[(svc, "eu-west-1")].add_response(operation, resp)
    code, out, _ = run(stubs, regions)
    assert code == 1
    assert expected in out
    regions.assert_done()


def test_home_region_is_not_swept_twice(stubs):
    # describe_regions returns us-east-1 too; only eu-west-1 gets per-region clients.
    regions = Regions(["eu-west-1"])
    stub_account(stubs)
    stub_home_extras(stubs, regions=("us-east-1", "eu-west-1"))
    regions.empty("eu-west-1")
    assert run(stubs, regions)[0] == 0


def test_three_regions_are_all_swept(stubs):
    regions = Regions(["eu-west-1", "ap-southeast-2"])
    stub_account(stubs)
    stub_home_extras(stubs, regions=("us-east-1", "eu-west-1", "ap-southeast-2"))
    regions.empty("eu-west-1")
    regions.empty("ap-southeast-2")
    assert run(stubs, regions)[0] == 0
    regions.assert_done()


# --- Errors exit non-zero ----------------------------------------------------------------------

def test_home_census_error_exits_nonzero(stubs):
    regions = Regions(["eu-west-1"])
    stubs.add("ecs", "list_clusters", {"clusterArns": []})
    stubs.error("elbv2", "describe_load_balancers", "ThrottlingException")
    code, out, err = run(stubs, regions)
    assert code == 2
    assert "ThrottlingException" in err
    assert "no lab resources found" not in out


def test_describe_regions_error_exits_nonzero(stubs):
    regions = Regions(["eu-west-1"])
    stub_account(stubs)
    stubs.add("ec2", "describe_instances", {"Reservations": []})
    stubs.error("ec2", "describe_regions", "UnauthorizedOperation")
    assert run(stubs, regions)[0] == 2


def test_other_region_error_exits_nonzero(stubs):
    regions = Regions(["eu-west-1"])
    stub_account(stubs)
    stub_home_extras(stubs)
    regions.stubbers[("ec2", "eu-west-1")].add_client_error(
        "describe_network_interfaces", service_error_code="AuthFailure", http_status_code=401)
    code, _, err = run(stubs, regions)
    assert code == 2
    assert "AuthFailure" in err


def test_empty_region_list_is_an_error(stubs):
    # describe_regions always includes the caller's own region; none at all is a bad response.
    regions = Regions([])
    stub_account(stubs)
    stub_home_extras(stubs, regions=())
    assert run(stubs, regions)[0] == 2


def test_error_output_carries_no_account_id(stubs):
    regions = Regions(["eu-west-1"])
    stubs.stubbers["ecs"].add_client_error(
        "list_clusters", service_error_code="AccessDeniedException",
        service_message=f"User: arn:aws:sts::{ACCOUNT_ID}:assumed-role/lab-teardown/x is not authorized")
    code, out, err = run(stubs, regions)
    assert code == 2
    assert ACCOUNT_ID not in out + err


# --- No tags, no state ------------------------------------------------------------------------

def test_verify_uses_no_tag_or_state_api():
    import inspect

    source = inspect.getsource(v) + inspect.getsource(v.census)
    for forbidden in ("Filters=", "TagFilters", "get_resources(", "resourcegroupstaggingapi", '"s3"', "'s3'"):
        assert forbidden not in source, forbidden


# --- main(): the CLI entry point ------------------------------------------------------------------

def test_main_returns_run_exit_code(stubs):
    regions = Regions(["eu-west-1"])
    stub_account(stubs)
    stub_home_extras(stubs)
    regions.empty("eu-west-1")
    assert v.main([], clients=stubs.as_clients(), client_factory=regions.factory) == 0


def test_main_unexpected_exception_exits_nonzero():
    def boom(*_a, **_k):
        raise RuntimeError("no credentials")

    assert v.main([], clients=None, client_factory=boom, make_clients=boom) == 2


# --- ALB security group: only one /32 ingress (TD:103) ---------------------------------------------

def perm(*cidrs, v6=(), groups=(), prefix_lists=(), port=80):
    return {
        "IpProtocol": "tcp", "FromPort": port, "ToPort": port,
        "IpRanges": [{"CidrIp": c} for c in cidrs],
        "Ipv6Ranges": [{"CidrIpv6": c} for c in v6],
        "UserIdGroupPairs": [{"GroupId": g} for g in groups],
        "PrefixListIds": [{"PrefixListId": p} for p in prefix_lists],
    }


def test_alb_ingress_single_slash_32_is_ok():
    assert v.alb_ingress_violations([perm("198.51.100.7/32")]) == []


@pytest.mark.parametrize(
    "perms",
    [
        [],
        [perm("0.0.0.0/0")],
        [perm("198.51.100.0/24")],
        [perm("198.51.100.6/31")],
        [perm("198.51.100.7/32", "198.51.100.8/32")],
        [perm("198.51.100.7/32"), perm("198.51.100.7/32", port=443)],
        [perm("198.51.100.7/32", v6=("::/0",))],
        [perm(v6=("2001:db8::1/128",))],
        [perm("198.51.100.7/32", groups=("sg-0abc",))],
        [perm("198.51.100.7/32", prefix_lists=("pl-0abc",))],
        [perm("not-a-cidr/32")],
        [perm("198.51.100.7")],
    ],
)
def test_alb_ingress_anything_else_is_a_violation(perms):
    assert v.alb_ingress_violations(perms) != []


def test_alb_ingress_missing_keys_count_as_no_ranges():
    assert v.alb_ingress_violations([{"IpProtocol": "tcp", "IpRanges": [{"CidrIp": "198.51.100.7/32"}]}]) == []


def test_item_line_format():
    assert v.format_item("eu-west-1", Item("eni", "eni-1")) == "LEFTOVER eu-west-1 eni eni-1"
