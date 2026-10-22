"""teardown.reaper: layer 3, the AWS-side reaper (docs/infra/teardown-design.md §3, TD:80-97).

It runs as the Lambda fairtix-lab-bootstrap-reaper (handler teardown.reaper.handler), which
EventBridge Scheduler invokes every 30 minutes. Locally it runs as a dry run only:

    python -m teardown.reaper --dry-run [--test-mode] [--grace-minutes N]     (run from infra/)

It calls decide() with the billable census and a 2-hour grace, so layer 2 gets the first
try. On "destroy" it deletes the lab's expensive resources directly, in this order:
  1. ECS: each service to 0, then delete the services, then the cluster;
  2. ALBs (deletion protection cleared first), then target groups;
  3. ElastiCache replication groups, standalone clusters and serverless caches;
  4. RDS instances (deletion protection cleared first), skipping the final snapshot and
     deleting automated backups;
  5. NAT gateways, then interface VPC endpoints, then Elastic IPs;
  6. then an SNS email: "reaper acted; run lab-destroy to clean state".

It finds resources by the fairtix-lab- name prefix (bootstrap excluded), and NAT gateways,
VPC endpoints and Elastic IPs, which have no name, by the tag project=fairtix-lab. It
never touches the VPC, ECR, secrets or log groups. Test mode (T5) adds one thing: it
counts the canary VPC (tags project=fairtix-lab and role=canary) in the census and
deletes it with its security groups.

Fail-closed rules:
- A census error raises, so the Lambda's Errors alarm fires.
- A delete refused because the resource is busy (draining, in use, already deleting) is
  deferred to the next run. Any other failure is recorded, the remaining steps still run,
  the email still goes out, and then the run raises.
- --dry-run makes only the read calls and logs each mutating call (the email included)
  instead of making it.
Output names resources by name or id, never full ARNs or AWS error messages.
"""
from __future__ import annotations

import argparse
import os
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Callable

from botocore.exceptions import ClientError

from . import census
from .census import BOOTSTRAP_PREFIX, EXPIRES_AT_PARAMETER, HOME_REGION, CensusError, Clients
from .decide import _describe, read_expiry
from .teardown_policy import DESTROY, REAPER_GRACE, Census, Item, decide_for_reaper

LAB_PREFIX = "fairtix-lab-"
TOPIC_NAME = f"{BOOTSTRAP_PREFIX}alerts"
PROJECT_FILTER = {"Name": "tag:project", "Values": ["fairtix-lab"]}
CANARY_FILTER = {"Name": "tag:role", "Values": ["canary"]}
SUBJECT = "fairtix-lab reaper acted"
ACTED_MESSAGE = "reaper acted; run lab-destroy to clean state"

# AWS refused because the resource is busy or already gone: the next run retries.
DEFERRABLE_CODES = frozenset({
    "ServiceNotActiveException", "ServiceNotFoundException",
    "ClusterContainsServicesException", "ClusterContainsTasksException", "ClusterNotFoundException",
    "ResourceInUse", "LoadBalancerNotFound", "TargetGroupNotFound",
    "InvalidReplicationGroupState", "InvalidCacheClusterState", "InvalidServerlessCacheStateFault",
    "ReplicationGroupNotFoundFault", "CacheClusterNotFound", "ServerlessCacheNotFoundFault",
    "InvalidDBInstanceState", "DBInstanceNotFound",
    "NatGatewayNotFound", "InvalidNatGatewayID.NotFound",
    "InvalidIPAddress.InUse", "InvalidAllocationID.NotFound",
    "InvalidVpcEndpointId.NotFound",
    "DependencyViolation", "InvalidGroup.NotFound", "InvalidVpcID.NotFound",
})

# --- The IAM actions the reaper calls (T5b simulates each; tests check the code uses no other) ---
# Resources are templates: {region} and {account} are filled in at simulation time.
_ARN = "arn:aws:{service}:{{region}}:{{account}}:{resource}"

READ_ACTIONS = {
    action: "*" for action in (
        "ecs:ListClusters", "ecs:ListServices", "ecs:DescribeServices",
        "elasticloadbalancing:DescribeLoadBalancers", "elasticloadbalancing:DescribeTargetGroups",
        "elasticloadbalancing:DescribeLoadBalancerAttributes",
        "rds:DescribeDBInstances", "rds:DescribeDBSnapshots", "rds:DescribeDBInstanceAutomatedBackups",
        "elasticache:DescribeCacheClusters", "elasticache:DescribeReplicationGroups",
        "elasticache:DescribeServerlessCaches", "elasticache:DescribeSnapshots",
        "elasticache:DescribeServerlessCacheSnapshots",
        "ec2:DescribeAddresses", "ec2:DescribeNatGateways", "ec2:DescribeVpcEndpoints",
    )
}
READ_ACTIONS["ssm:GetParameter"] = _ARN.format(service="ssm", resource=f"parameter{EXPIRES_AT_PARAMETER}")

MUTATING_ACTIONS = {
    "ecs:UpdateService": _ARN.format(service="ecs", resource=f"service/{LAB_PREFIX}*"),
    "ecs:DeleteService": _ARN.format(service="ecs", resource=f"service/{LAB_PREFIX}*"),
    "ecs:DeleteCluster": _ARN.format(service="ecs", resource=f"cluster/{LAB_PREFIX}*"),
    "elasticloadbalancing:ModifyLoadBalancerAttributes":
        _ARN.format(service="elasticloadbalancing", resource=f"loadbalancer/app/{LAB_PREFIX}*"),
    "elasticloadbalancing:DeleteLoadBalancer":
        _ARN.format(service="elasticloadbalancing", resource=f"loadbalancer/app/{LAB_PREFIX}*"),
    "elasticloadbalancing:DeleteTargetGroup":
        _ARN.format(service="elasticloadbalancing", resource=f"targetgroup/{LAB_PREFIX}*"),
    "elasticache:DeleteReplicationGroup": _ARN.format(service="elasticache", resource=f"replicationgroup:{LAB_PREFIX}*"),
    "elasticache:DeleteCacheCluster": _ARN.format(service="elasticache", resource=f"cluster:{LAB_PREFIX}*"),
    "elasticache:DeleteServerlessCache": _ARN.format(service="elasticache", resource=f"serverlesscache:{LAB_PREFIX}*"),
    "rds:ModifyDBInstance": _ARN.format(service="rds", resource=f"db:{LAB_PREFIX}*"),
    "rds:DeleteDBInstance": _ARN.format(service="rds", resource=f"db:{LAB_PREFIX}*"),
    # DeleteDBInstance with DeleteAutomatedBackups may also authorize against the backup,
    # whose ARN carries no name. The reaper never calls this action directly.
    "rds:DeleteDBInstanceAutomatedBackup": _ARN.format(service="rds", resource="auto-backup:*"),
    # No name to match: allowed only under aws:ResourceTag/project = fairtix-lab.
    "ec2:DeleteNatGateway": _ARN.format(service="ec2", resource="natgateway/*"),
    "ec2:DeleteVpcEndpoints": _ARN.format(service="ec2", resource="vpc-endpoint/*"),
    "ec2:ReleaseAddress": _ARN.format(service="ec2", resource="elastic-ip/*"),
    "sns:Publish": _ARN.format(service="sns", resource=TOPIC_NAME),
}

# Test mode only (T5). Allowed only under the tags project = fairtix-lab and role = canary.
TEST_MODE_READ_ACTIONS = {"ec2:DescribeVpcs": "*", "ec2:DescribeSecurityGroups": "*"}
TEST_MODE_ACTIONS = {
    "ec2:DeleteSecurityGroup": _ARN.format(service="ec2", resource="security-group/*"),
    "ec2:DeleteVpc": _ARN.format(service="ec2", resource="vpc/*"),
}

TAG_SCOPED_ACTIONS = ("ec2:DeleteNatGateway", "ec2:DeleteVpcEndpoints", "ec2:ReleaseAddress",
                      "ec2:DeleteSecurityGroup", "ec2:DeleteVpc")


class ReaperError(Exception):
    """The reaper couldn't finish: the Lambda fails, and its Errors alarm emails."""


def is_lab(name: str) -> bool:
    return name.startswith(LAB_PREFIX) and not name.startswith(BOOTSTRAP_PREFIX.rstrip("-"))


@dataclass
class Actions:
    """Makes (or, in a dry run, logs) each mutating call, and sorts its outcome."""

    dry_run: bool
    out: Any = None
    calls: list[str] = field(default_factory=list)
    deferred: list[str] = field(default_factory=list)
    failures: list[str] = field(default_factory=list)

    def call(self, label: str, fn: Callable, **kwargs):
        if self.dry_run:
            self.calls.append(label)
            print(f"reaper: DRY-RUN would call {label}", file=self.out)
            return None
        try:
            response = fn(**kwargs)
        except ClientError as exc:
            code = exc.response.get("Error", {}).get("Code", "unknown")
            if code in DEFERRABLE_CODES:
                self.deferred.append(f"{label} ({code})")
                print(f"reaper: deferred {label} ({code}); the next run retries", file=self.out)
            else:
                self.failures.append(f"{label} ({code})")
                print(f"reaper: FAILED {label} ({code})", file=self.out)
            return None
        self.calls.append(label)
        print(f"reaper: called {label}", file=self.out)
        return response

    def step(self, name: str, fn: Callable[[], None]) -> None:
        """One deletion step. A failed listing is recorded, and the next step still runs."""
        try:
            fn()
        except ClientError as exc:
            self.failures.append(f"{name}: {exc.response.get('Error', {}).get('Code', 'unknown')}")
        except Exception as exc:  # noqa: BLE001 - a bad response; keep stopping the meter
            self.failures.append(f"{name}: {type(exc).__name__}")


# --- The deletion steps, in the design order ---------------------------------------------

def reap_ecs(ecs, act: Actions) -> None:
    for cluster_arn in census._pages(ecs, "list_clusters", "clusterArns"):
        cluster = census._arn_resource(cluster_arn, "cluster")
        if not is_lab(cluster):
            continue
        services = [(arn, arn.rsplit("/", 1)[-1])
                    for arn in census._pages(ecs, "list_services", "serviceArns", cluster=cluster_arn)]
        for arn, name in services:
            act.call(f"ecs.update_service {cluster}/{name} desiredCount=0", ecs.update_service,
                     cluster=cluster_arn, service=arn, desiredCount=0)
        for arn, name in services:
            act.call(f"ecs.delete_service {cluster}/{name} force", ecs.delete_service,
                     cluster=cluster_arn, service=arn, force=True)
        act.call(f"ecs.delete_cluster {cluster}", ecs.delete_cluster, cluster=cluster_arn)


def reap_load_balancers(elbv2, act: Actions) -> None:
    for lb in census._pages(elbv2, "describe_load_balancers", "LoadBalancers"):
        name, arn = lb["LoadBalancerName"], lb["LoadBalancerArn"]
        if not is_lab(name):
            continue
        attributes = elbv2.describe_load_balancer_attributes(LoadBalancerArn=arn)["Attributes"]
        if any(a.get("Key") == "deletion_protection.enabled" and a.get("Value") == "true" for a in attributes):
            act.call(f"elbv2.modify_load_balancer_attributes {name} deletion_protection.enabled=false",
                     elbv2.modify_load_balancer_attributes, LoadBalancerArn=arn,
                     Attributes=[{"Key": "deletion_protection.enabled", "Value": "false"}])
        act.call(f"elbv2.delete_load_balancer {name}", elbv2.delete_load_balancer, LoadBalancerArn=arn)
    for tg in census._pages(elbv2, "describe_target_groups", "TargetGroups"):
        name = tg["TargetGroupName"]
        if is_lab(name):
            act.call(f"elbv2.delete_target_group {name}", elbv2.delete_target_group,
                     TargetGroupArn=tg["TargetGroupArn"])


def reap_elasticache(ec, act: Actions) -> None:
    # A Valkey replication group can't be removed with delete_cache_cluster (TD:84).
    for group in census._pages(ec, "describe_replication_groups", "ReplicationGroups"):
        gid = group["ReplicationGroupId"]
        if is_lab(gid) and group.get("Status") != "deleting":
            act.call(f"elasticache.delete_replication_group {gid}", ec.delete_replication_group,
                     ReplicationGroupId=gid, RetainPrimaryCluster=False)
    for cluster in census._pages(ec, "describe_cache_clusters", "CacheClusters"):
        cid = cluster["CacheClusterId"]
        if is_lab(cid) and not cluster.get("ReplicationGroupId") and cluster.get("CacheClusterStatus") != "deleting":
            act.call(f"elasticache.delete_cache_cluster {cid}", ec.delete_cache_cluster, CacheClusterId=cid)
    for cache in census._pages(ec, "describe_serverless_caches", "ServerlessCaches"):
        name = cache["ServerlessCacheName"]
        if is_lab(name) and cache.get("Status") != "deleting":
            act.call(f"elasticache.delete_serverless_cache {name}", ec.delete_serverless_cache,
                     ServerlessCacheName=name)


def reap_rds(rds, act: Actions) -> None:
    for instance in census._pages(rds, "describe_db_instances", "DBInstances"):
        ident = instance["DBInstanceIdentifier"]
        if not is_lab(ident) or instance.get("DBInstanceStatus") == "deleting":
            continue
        if instance.get("DeletionProtection"):
            act.call(f"rds.modify_db_instance {ident} DeletionProtection=false", rds.modify_db_instance,
                     DBInstanceIdentifier=ident, DeletionProtection=False, ApplyImmediately=True)
        act.call(f"rds.delete_db_instance {ident} skip-final-snapshot delete-automated-backups",
                 rds.delete_db_instance, DBInstanceIdentifier=ident, SkipFinalSnapshot=True,
                 DeleteAutomatedBackups=True)


def reap_nat_gateways(ec2, act: Actions) -> None:
    # DescribeNatGateways names its filter parameter "Filter", not "Filters".
    for nat in census._pages(ec2, "describe_nat_gateways", "NatGateways", Filter=[PROJECT_FILTER]):
        if nat.get("State") not in ("deleting", "deleted"):
            act.call(f"ec2.delete_nat_gateway {nat['NatGatewayId']}", ec2.delete_nat_gateway,
                     NatGatewayId=nat["NatGatewayId"])


def reap_vpc_endpoints(ec2, act: Actions) -> None:
    # Gateway endpoints (S3, DynamoDB) are free; interface and GWLB endpoints bill per hour.
    for endpoint in census._pages(ec2, "describe_vpc_endpoints", "VpcEndpoints", Filters=[PROJECT_FILTER]):
        eid = endpoint["VpcEndpointId"]
        if endpoint.get("VpcEndpointType") == "Gateway" or str(endpoint.get("State", "")).lower() in ("deleting", "deleted"):
            continue
        label = f"ec2.delete_vpc_endpoints {eid}"
        response = act.call(label, ec2.delete_vpc_endpoints, VpcEndpointIds=[eid])
        for item in (response or {}).get("Unsuccessful", []):
            code = item.get("Error", {}).get("Code", "unknown")
            act.calls.remove(label)
            act.failures.append(f"{label} ({code})")


def reap_elastic_ips(ec2, act: Actions) -> None:
    # Not paginated: one call returns every matching address in the region.
    for address in ec2.describe_addresses(Filters=[PROJECT_FILTER])["Addresses"]:
        allocation = address.get("AllocationId")
        if allocation:
            act.call(f"ec2.release_address {allocation}", ec2.release_address, AllocationId=allocation)


def canary_vpcs(ec2) -> list[str]:
    return [v["VpcId"] for v in census._pages(ec2, "describe_vpcs", "Vpcs", Filters=[PROJECT_FILTER, CANARY_FILTER])
            if v.get("IsDefault") is not True]


def reap_canary(ec2, act: Actions) -> None:
    for vpc_id in canary_vpcs(ec2):
        groups = census._pages(ec2, "describe_security_groups", "SecurityGroups",
                               Filters=[{"Name": "vpc-id", "Values": [vpc_id]}])
        for group in groups:
            if group["GroupName"] != "default":  # deleted with its VPC
                act.call(f"ec2.delete_security_group {group['GroupId']}", ec2.delete_security_group,
                         GroupId=group["GroupId"])
        act.call(f"ec2.delete_vpc {vpc_id}", ec2.delete_vpc, VpcId=vpc_id)


def deletion_steps(clients: Clients, test_mode: bool) -> list[tuple[str, Callable[[Actions], None]]]:
    steps = [
        ("ecs", lambda act: reap_ecs(clients.ecs, act)),
        ("load balancers", lambda act: reap_load_balancers(clients.elbv2, act)),
        ("elasticache", lambda act: reap_elasticache(clients.elasticache, act)),
        ("rds", lambda act: reap_rds(clients.rds, act)),
        ("nat gateways", lambda act: reap_nat_gateways(clients.ec2, act)),
        ("vpc endpoints", lambda act: reap_vpc_endpoints(clients.ec2, act)),
        ("elastic ips", lambda act: reap_elastic_ips(clients.ec2, act)),
    ]
    if test_mode:
        steps.append(("canary vpc", lambda act: reap_canary(clients.ec2, act)))
    return steps


# --- The run -------------------------------------------------------------------------------

def build_reaper_census(clients: Clients, *, test_mode: bool = False) -> Census:
    """The billable census; in test mode, plus the canary VPC. Raises CensusError on any failure."""
    items = census.list_items(clients, billable_only=True)
    if test_mode:
        items += census.guarded("canary vpcs", lambda: [Item("canary_vpc", v) for v in canary_vpcs(clients.ec2)])
    # "canary_vpc" isn't a free kind, so from_items counts it as billable.
    return Census.from_items(items).billable_only()


def compose_message(found: Census, act: Actions) -> str:
    lines = [ACTED_MESSAGE, "", f"Billable items found ({len(found.billable)}):"]
    lines += [f"  {item.kind} {item.id}" for item in found.billable]
    for title, entries in (("Calls made", act.calls), ("Deferred to the next run", act.deferred),
                           ("Failed", act.failures)):
        lines += ["", f"{title} ({len(entries)}):"] + [f"  {e}" for e in entries]
    lines += ["", "Recovery: docs/infra/teardown-design.md §7 and §9. Run teardown.verify when done."]
    return "\n".join(lines)


def run(clients: Clients, ssm, sns, *, topic_arn: str | None, now: datetime,
        grace: timedelta = REAPER_GRACE, test_mode: bool = False, dry_run: bool = False,
        out=None, err=None) -> dict:
    # Resolved per call (print(file=None) is the current stdout), so captured output works.
    expires_at_raw = read_expiry(ssm, err or sys.stderr)
    seen: list[Census] = []

    def census_fn() -> Census:
        seen.append(build_reaper_census(clients, test_mode=test_mode))
        return seen[-1]

    decision = decide_for_reaper(now, expires_at_raw, census_fn, grace=grace)
    found = seen[-1]
    mode = " (dry run)" if dry_run else ""
    print(f"reaper: decision {decision}{mode}, {len(found.billable)} billable item(s)", file=out)
    result = {"decision": decision, "dry_run": dry_run, "calls": [], "deferred": [], "failures": []}
    if decision != DESTROY:
        return result

    act = Actions(dry_run=dry_run, out=out)
    for name, step in deletion_steps(clients, test_mode):
        act.step(name, lambda step=step: step(act))
    act.call("sns.publish", getattr(sns, "publish", None), TopicArn=topic_arn, Subject=SUBJECT, Message=compose_message(found, act))

    result.update(calls=act.calls, deferred=act.deferred, failures=act.failures)
    if act.failures:
        raise ReaperError(f"{len(act.failures)} failure(s): " + "; ".join(act.failures))
    return result


def make_clients(session=None):
    import boto3

    session = session or boto3.session.Session()
    return (census.make_clients(session), session.client("ssm", region_name=HOME_REGION),
            session.client("sns", region_name=HOME_REGION))


def _grace_from(raw: str | None) -> timedelta:
    if raw is None:
        return REAPER_GRACE
    try:
        minutes = int(raw)
    except ValueError:
        raise ReaperError("REAPER_GRACE_MINUTES is not a whole number of minutes") from None
    if minutes <= 0:
        raise ReaperError("REAPER_GRACE_MINUTES must be positive")
    return timedelta(minutes=minutes)


def handler(event, context):  # noqa: ARG001 - the Lambda signature
    """The Lambda entry point. An event {"dry_run": true} makes it a dry run (T7)."""
    topic_arn = os.environ.get("REAPER_SNS_TOPIC_ARN")
    if not topic_arn:
        raise ReaperError("REAPER_SNS_TOPIC_ARN is not set")
    grace = _grace_from(os.environ.get("REAPER_GRACE_MINUTES"))
    test_mode = os.environ.get("REAPER_TEST_MODE") == "true"
    dry_run = isinstance(event, dict) and event.get("dry_run") is True
    clients, ssm, sns = make_clients()
    return run(clients, ssm, sns, topic_arn=topic_arn, now=datetime.now(timezone.utc),
               grace=grace, test_mode=test_mode, dry_run=dry_run)


def main(argv=None, *, clients=None, ssm=None, now=None, out=None, err=None) -> int:
    err = err or sys.stderr
    parser = argparse.ArgumentParser(prog="python -m teardown.reaper")
    parser.add_argument("--dry-run", action="store_true", help="required: log the calls without making them")
    parser.add_argument("--test-mode", action="store_true", help="also count and target the canary VPC (T5)")
    parser.add_argument("--grace-minutes", default=str(int(REAPER_GRACE.total_seconds() // 60)))
    args = parser.parse_args(argv)
    if not args.dry_run:
        parser.error("only --dry-run runs locally; real deletes run in the Lambda")

    try:
        grace = _grace_from(args.grace_minutes)
        if clients is None or ssm is None:
            clients, ssm, _ = make_clients()
        result = run(clients, ssm, None, topic_arn=None, now=now or datetime.now(timezone.utc),
                     grace=grace, test_mode=args.test_mode, dry_run=True, out=out, err=err)
    except CensusError as exc:
        print(f"reaper: ERROR census incomplete ({exc}); resources may exist", file=err)
        return 2
    except Exception as exc:  # noqa: BLE001
        print(f"reaper: ERROR {_describe(exc) if not isinstance(exc, ReaperError) else exc}", file=err)
        return 2
    print(f"reaper: dry run done, {len(result['calls'])} call(s) would be made", file=out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
