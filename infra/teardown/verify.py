"""teardown.verify: is anything from the lab still in the account? (docs/infra/teardown-design.md §6)

    python -m teardown.verify        (run from infra/)

It lists every census type in us-east-1 directly from AWS APIs, plus EC2 instances there,
and sweeps every other enabled region for ENIs, instances and load balancers. It uses no
tags and no Terraform state.

Exit codes: 0 nothing found; 1 leftovers (listed by name or id, never secret values);
2 the listing failed, so nothing can be claimed.
"""
from __future__ import annotations

import ipaddress
import sys
from typing import Callable

from . import census
from .census import HOME_REGION, CensusError, Clients, guarded
from .teardown_policy import Item

EXIT_CLEAN, EXIT_LEFTOVERS, EXIT_ERROR = 0, 1, 2

ClientFactory = Callable[[str, str], object]


def format_item(region: str, item: Item) -> str:
    return f"LEFTOVER {region} {item.kind} {item.id}"


def list_instances(ec2) -> list[Item]:
    # Terminated instances stay visible for about an hour and cost nothing.
    return [
        Item("ec2_instance", inst["InstanceId"], inst.get("LaunchTime"))
        for reservation in census._pages(ec2, "describe_instances", "Reservations")
        for inst in reservation["Instances"]
        if inst["State"]["Name"] != "terminated"
    ]


def list_load_balancers(elbv2) -> list[Item]:
    return [Item("elb_load_balancer", lb["LoadBalancerName"], lb.get("CreatedTime"))
            for lb in census._pages(elbv2, "describe_load_balancers", "LoadBalancers")]


def list_enis(ec2) -> list[Item]:
    return [Item("eni", n["NetworkInterfaceId"])
            for n in census._pages(ec2, "describe_network_interfaces", "NetworkInterfaces")]


def other_regions(ec2_home, home: str = HOME_REGION) -> list[str]:
    def listing():
        names = [r["RegionName"] for r in ec2_home.describe_regions()["Regions"]]
        if home not in names:
            raise CensusError(f"describe_regions did not include {home}")
        return [n for n in names if n != home]

    return guarded("describe_regions", listing)


def sweep_region(region: str, client_factory: ClientFactory) -> list[Item]:
    ec2 = client_factory("ec2", region)
    elbv2 = client_factory("elbv2", region)
    return (
        guarded(f"{region} enis", lambda: list_enis(ec2))
        + guarded(f"{region} instances", lambda: list_instances(ec2))
        + guarded(f"{region} load balancers", lambda: list_load_balancers(elbv2))
    )


def find_leftovers(clients: Clients, client_factory: ClientFactory) -> list[tuple[str, Item]]:
    """(region, item) for every leftover. Raises CensusError if anything can't be listed."""
    found = [(HOME_REGION, i) for i in census.list_items(clients)]
    found += [(HOME_REGION, i) for i in guarded("instances", lambda: list_instances(clients.ec2))]
    for region in other_regions(clients.ec2):
        found += [(region, i) for i in sweep_region(region, client_factory)]
    return found


def run(clients: Clients, client_factory: ClientFactory, out=sys.stdout, err=sys.stderr) -> int:
    try:
        found = find_leftovers(clients, client_factory)
    except CensusError as exc:
        print(f"teardown.verify: ERROR listing failed ({exc}); nothing can be claimed", file=err)
        return EXIT_ERROR
    if not found:
        print("teardown.verify: no lab resources found", file=out)
        return EXIT_CLEAN
    for region, item in found:
        print(format_item(region, item), file=out)
    print(f"teardown.verify: {len(found)} leftover(s)", file=out)
    return EXIT_LEFTOVERS


def alb_ingress_violations(ip_permissions: list[dict]) -> list[str]:
    """The ALB security group may allow exactly one IPv4 /32 source and nothing else (TD:103-107).

    Returns the violations; an empty list means compliant. FT-3 wires this to the lab's ALB SG.
    """
    sources: list[tuple[str, str]] = []
    for p in ip_permissions:
        sources += [("ipv4", r.get("CidrIp", "")) for r in p.get("IpRanges", [])]
        sources += [("ipv6", r.get("CidrIpv6", "")) for r in p.get("Ipv6Ranges", [])]
        sources += [("security-group", g.get("GroupId", "?")) for g in p.get("UserIdGroupPairs", [])]
        sources += [("prefix-list", x.get("PrefixListId", "?")) for x in p.get("PrefixListIds", [])]

    violations = [f"{kind} source {value} is not allowed" for kind, value in sources if kind != "ipv4"]
    ipv4 = [value for kind, value in sources if kind == "ipv4"]
    if len(ipv4) != 1:
        violations.append(f"expected exactly one IPv4 /32 source, found {len(ipv4)}")
    for value in ipv4:
        if "/" not in value:
            violations.append(f"{value} has no prefix length")
            continue
        try:
            network = ipaddress.IPv4Network(value, strict=True)
        except ValueError:
            violations.append(f"{value} is not a valid IPv4 CIDR")
            continue
        if network.prefixlen != 32:
            violations.append(f"{value} is wider than /32")
    return violations


def _default_client_factory(service: str, region: str):
    import boto3

    return boto3.session.Session().client(service, region_name=region)


def main(argv=None, *, clients=None, client_factory=None, make_clients=None) -> int:
    try:
        clients = clients or (make_clients or census.make_clients)()
        return run(clients, client_factory or _default_client_factory)
    except Exception as exc:  # noqa: BLE001 - no credentials, bad config: never exit 0
        print(f"teardown.verify: ERROR {type(exc).__name__}; nothing can be claimed", file=sys.stderr)
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
