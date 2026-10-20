"""The lab resource census (docs/infra/teardown-design.md §4 and §6).

It lists AWS APIs directly. It never reads Terraform state or tags. Clients are
injected, so the tests use botocore Stubber and never reach the network.

Fail-closed rules:
- Any error (an API error, a throttle, a page that fails, a repeated page token,
  a response missing its list key) raises CensusError. It never yields a partial or
  empty census.
- Identifiers are names or resource ids, never full ARNs, so output and CI logs don't
  carry the account id. CensusError carries the error code only, never AWS's message.

Bootstrap naming contract (FT-3 must follow it):
- Every bootstrap resource is named with the prefix `fairtix-lab-bootstrap-`: the state
  bucket (`fairtix-lab-bootstrap-tfstate-<suffix>`), the IAM roles, the reaper Lambda
  (exactly `fairtix-lab-bootstrap-reaper`), its schedule, the SNS topic and the alarm.
  The SSM parameter is exactly `/fairtix-lab/expires_at`.
- No lab resource name may start with `fairtix-lab-bootstrap`.
- Bootstrap owns no billable resource and no customer-managed KMS key (state SSE uses an
  AWS-managed key), so any CMK the census finds is a leftover.
- The lab uses no CMK and creates secrets with recovery_window_in_days = 0. Keys and
  secrets pending deletion are counted, so either would keep the census non-empty
  (decide says destroy, verify fails) for 7-30 days after a teardown.
- The census lists none of the bootstrap kinds except the reaper's log group, which is
  excluded by its exact name below. A new bootstrap log group needs an entry in
  BOOTSTRAP_LOG_GROUPS; anything not listed there is counted.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Callable

from botocore.exceptions import ClientError

from .teardown_policy import Census, Item

HOME_REGION = "us-east-1"
BOOTSTRAP_PREFIX = "fairtix-lab-bootstrap-"
EXPIRES_AT_PARAMETER = "/fairtix-lab/expires_at"

# Exact (kind, id) pairs. Prefix matching is deliberately not used: a look-alike lab
# item such as "/aws/lambda/fairtix-lab-bootstrap-reaper-v2" must still be counted.
BOOTSTRAP_LOG_GROUPS = frozenset({f"/aws/lambda/{BOOTSTRAP_PREFIX}reaper"})


class CensusError(Exception):
    """The census could not be completed: treat it as "resources may exist"."""


@dataclass(frozen=True)
class Clients:
    ecs: Any
    elbv2: Any
    rds: Any
    elasticache: Any
    ec2: Any
    ecr: Any
    secrets: Any
    logs: Any
    kms: Any


def make_clients(session=None, region: str = HOME_REGION) -> Clients:
    """Real boto3 clients for the CLIs. Tests never call this."""
    import boto3

    session = session or boto3.session.Session()
    return Clients(**{
        field: session.client(service, region_name=region)
        for field, service in (
            ("ecs", "ecs"), ("elbv2", "elbv2"), ("rds", "rds"), ("elasticache", "elasticache"),
            ("ec2", "ec2"), ("ecr", "ecr"), ("secrets", "secretsmanager"), ("logs", "logs"), ("kms", "kms"),
        )
    })


def is_bootstrap(item: Item) -> bool:
    return item.kind == "log_group" and item.id in BOOTSTRAP_LOG_GROUPS


def _pages(client, operation: str, key: str, **kwargs) -> list:
    """Every element of `key` across all pages. A missing key raises (KeyError).

    botocore stops when the next-page token is missing, even if the page still says
    Truncated (KMS), so a last page that claims more results raises.
    """
    out: list = []
    page: dict = {}
    for page in client.get_paginator(operation).paginate(**kwargs):
        out.extend(page[key])
    if page.get("Truncated") is True or page.get("IsTruncated") is True:
        raise CensusError(f"{operation}: listing truncated without a next-page token")
    return out


def _arn_resource(arn: str, resource_type: str) -> str:
    """'arn:aws:ecs:...:cluster/fairtix-lab' -> 'fairtix-lab' (drops the account id)."""
    marker = f":{resource_type}/"
    if marker not in arn:
        raise ValueError(f"unexpected {resource_type} ARN shape")
    return arn.split(marker, 1)[1]


# --- Billable ------------------------------------------------------------------------------

def list_ecs(clients: Clients) -> list[Item]:
    items: list[Item] = []
    for cluster_arn in _pages(clients.ecs, "list_clusters", "clusterArns"):
        items.append(Item("ecs_cluster", _arn_resource(cluster_arn, "cluster")))
        service_arns = _pages(clients.ecs, "list_services", "serviceArns", cluster=cluster_arn)
        for start in range(0, len(service_arns), 10):
            batch = service_arns[start:start + 10]
            resp = clients.ecs.describe_services(cluster=cluster_arn, services=batch)
            if resp["failures"]:
                raise CensusError(f"ecs describe_services: {len(resp['failures'])} failure(s)")
            for svc in resp["services"]:
                items.append(Item("ecs_service", _arn_resource(svc["serviceArn"], "service"), svc.get("createdAt")))
    return items


def list_elbv2(clients: Clients) -> list[Item]:
    lbs = _pages(clients.elbv2, "describe_load_balancers", "LoadBalancers")
    tgs = _pages(clients.elbv2, "describe_target_groups", "TargetGroups")
    return (
        [Item("elb_load_balancer", lb["LoadBalancerName"], lb.get("CreatedTime")) for lb in lbs]
        + [Item("elb_target_group", tg["TargetGroupName"]) for tg in tgs]
    )


def list_rds(clients: Clients) -> list[Item]:
    instances = _pages(clients.rds, "describe_db_instances", "DBInstances")
    snapshots = _pages(clients.rds, "describe_db_snapshots", "DBSnapshots", SnapshotType="manual")
    backups = _pages(clients.rds, "describe_db_instance_automated_backups", "DBInstanceAutomatedBackups")
    return (
        [Item("rds_instance", i["DBInstanceIdentifier"], i.get("InstanceCreateTime")) for i in instances]
        + [Item("rds_snapshot", s["DBSnapshotIdentifier"], s.get("SnapshotCreateTime")) for s in snapshots]
        + [
            Item("rds_automated_backup", f"{b['DBInstanceIdentifier']} ({b['DbiResourceId']})",
                 b.get("InstanceCreateTime"))
            for b in backups
        ]
    )


def _oldest(times) -> datetime | None:
    times = [t for t in times if t is not None]
    return min(times) if times else None


def list_elasticache(clients: Clients) -> list[Item]:
    ec = clients.elasticache
    clusters = _pages(ec, "describe_cache_clusters", "CacheClusters")
    groups = _pages(ec, "describe_replication_groups", "ReplicationGroups")
    serverless = _pages(ec, "describe_serverless_caches", "ServerlessCaches")
    snapshots = _pages(ec, "describe_snapshots", "Snapshots")
    serverless_snapshots = _pages(ec, "describe_serverless_cache_snapshots", "ServerlessCacheSnapshots")
    return (
        [Item("elasticache_cluster", x["CacheClusterId"], x.get("CacheClusterCreateTime")) for x in clusters]
        + [Item("elasticache_replication_group", x["ReplicationGroupId"], x.get("ReplicationGroupCreateTime"))
           for x in groups]
        + [Item("elasticache_serverless_cache", x["ServerlessCacheName"], x.get("CreateTime")) for x in serverless]
        + [
            Item("elasticache_snapshot", x["SnapshotName"],
                 _oldest(n.get("SnapshotCreateTime") for n in x.get("NodeSnapshots", [])))
            for x in snapshots
        ]
        + [Item("elasticache_snapshot", x["ServerlessCacheSnapshotName"], x.get("CreateTime"))
           for x in serverless_snapshots]
    )


def list_eips(clients: Clients) -> list[Item]:
    # Not paginated: one call returns every address in the region.
    return [Item("eip", a.get("AllocationId") or a["PublicIp"]) for a in clients.ec2.describe_addresses()["Addresses"]]


def list_nat_gateways(clients: Clients) -> list[Item]:
    # No tag filter: the lab has no NAT by design, so any NAT that isn't deleted is a leak.
    return [
        Item("nat_gateway", n["NatGatewayId"], n.get("CreateTime"))
        for n in _pages(clients.ec2, "describe_nat_gateways", "NatGateways")
        if n.get("State") != "deleted"
    ]


def list_vpc_endpoints(clients: Clients) -> list[Item]:
    # Interface and Gateway Load Balancer endpoints bill per hour; S3/DynamoDB Gateway
    # endpoints are free. An endpoint with no type reported still counts.
    return [
        Item("vpc_endpoint", e["VpcEndpointId"], e.get("CreationTimestamp"))
        for e in _pages(clients.ec2, "describe_vpc_endpoints", "VpcEndpoints")
        if e.get("VpcEndpointType") != "Gateway" and str(e.get("State", "")).lower() != "deleted"
    ]


# --- Free --------------------------------------------------------------------------------------

def list_enis(clients: Clients) -> list[Item]:
    return [Item("eni", n["NetworkInterfaceId"])
            for n in _pages(clients.ec2, "describe_network_interfaces", "NetworkInterfaces")]


def list_vpcs(clients: Clients) -> list[Item]:
    return [Item("vpc", v["VpcId"]) for v in _pages(clients.ec2, "describe_vpcs", "Vpcs") if v.get("IsDefault") is not True]


def list_security_groups(clients: Clients) -> list[Item]:
    # Every VPC has a "default" group that can't be deleted; it goes with its VPC.
    return [
        Item("security_group", f"{g['GroupId']} ({g['GroupName']})")
        for g in _pages(clients.ec2, "describe_security_groups", "SecurityGroups")
        if g["GroupName"] != "default"
    ]


def list_ecr(clients: Clients) -> list[Item]:
    return [Item("ecr_repository", r["repositoryName"], r.get("createdAt"))
            for r in _pages(clients.ecr, "describe_repositories", "repositories")]


def list_secrets(clients: Clients) -> list[Item]:
    # Metadata only (ListSecrets); secret values are never fetched.
    return [
        Item("secret", s["Name"] + (" (pending deletion)" if s.get("DeletedDate") else ""), s.get("CreatedDate"))
        for s in _pages(clients.secrets, "list_secrets", "SecretList", IncludePlannedDeletion=True)
    ]


def list_log_groups(clients: Clients) -> list[Item]:
    items = []
    for g in _pages(clients.logs, "describe_log_groups", "logGroups"):
        millis = g.get("creationTime")
        created = datetime.fromtimestamp(millis / 1000, tz=timezone.utc) if millis is not None else None
        items.append(Item("log_group", g["logGroupName"], created))
    return items


def list_kms_keys(clients: Clients) -> list[Item]:
    # Customer-managed keys in any state, including PendingDeletion. Only keys that AWS
    # reports as AWS-managed are skipped.
    items = []
    for key in _pages(clients.kms, "list_keys", "Keys"):
        meta = clients.kms.describe_key(KeyId=key["KeyId"])["KeyMetadata"]
        if meta.get("KeyManager") == "AWS":
            continue
        items.append(Item("kms_key", f"{meta['KeyId']} ({meta.get('KeyState', 'unknown')})", meta.get("CreationDate")))
    return items


BILLABLE_LISTERS: tuple[Callable[[Clients], list[Item]], ...] = (
    list_ecs, list_elbv2, list_rds, list_elasticache, list_eips, list_nat_gateways, list_vpc_endpoints,
)
FREE_LISTERS: tuple[Callable[[Clients], list[Item]], ...] = (
    list_enis, list_vpcs, list_security_groups, list_ecr, list_secrets, list_log_groups, list_kms_keys,
)


def _run_lister(lister: Callable[[Clients], list[Item]], clients: Clients) -> list[Item]:
    return guarded(getattr(lister, "__name__", "lister"), lambda: lister(clients))


def guarded(name: str, listing: Callable[[], list[Item]]) -> list[Item]:
    """Run one listing. Any failure becomes CensusError carrying the error code only."""
    try:
        return listing()
    except CensusError:
        raise
    except ClientError as exc:
        code = exc.response.get("Error", {}).get("Code", "unknown")
        raise CensusError(f"{name}: {code}") from exc
    except Exception as exc:  # noqa: BLE001 - incomplete pagination, a bad response, anything
        raise CensusError(f"{name}: {type(exc).__name__}") from exc


def list_items(clients: Clients, *, billable_only: bool = False) -> list[Item]:
    """Every lab item, bootstrap excluded. Raises CensusError if any listing fails."""
    listers = BILLABLE_LISTERS + (() if billable_only else FREE_LISTERS)
    items: list[Item] = []
    for lister in listers:
        items.extend(_run_lister(lister, clients))
    return [i for i in items if not is_bootstrap(i)]


def build_census(clients: Clients, *, billable_only: bool = False) -> Census:
    census = Census.from_items(list_items(clients, billable_only=billable_only))
    return census.billable_only() if billable_only else census
