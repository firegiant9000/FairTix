"""teardown.reaper: layer 3 (docs/infra/teardown-design.md §3, TD:80-97).

Every client is a Stubber. Each service's stubber is a FIFO queue, so a call the reaper
shouldn't make (a delete in a dry run, anything on ECR, secrets or logs) has no queued
response and fails the test. Cross-service order is checked from the recorded calls.
"""
from datetime import datetime, timedelta, timezone

import pytest
from botocore.stub import ANY

from teardown import census, reaper
from teardown.census import CensusError
from teardown.tests.conftest import ACCOUNT_ID, stub_account, stub_client

NOW = datetime(2026, 10, 22, 12, 0, 1, tzinfo=timezone.utc)
EXPIRES = "2026-10-22T10:00:00Z"  # NOW is expiry + 2 h + 1 s: just past the reaper's grace
CREATED = datetime(2026, 10, 22, 9, 0, tzinfo=timezone.utc)
TOPIC = f"arn:aws:sns:us-east-1:{ACCOUNT_ID}:fairtix-lab-bootstrap-alerts"

CLUSTER_ARN = f"arn:aws:ecs:us-east-1:{ACCOUNT_ID}:cluster/fairtix-lab-cluster"
SERVICE_ARN = f"arn:aws:ecs:us-east-1:{ACCOUNT_ID}:service/fairtix-lab-cluster/fairtix-lab-backend"
LB_ARN = f"arn:aws:elasticloadbalancing:us-east-1:{ACCOUNT_ID}:loadbalancer/app/fairtix-lab-alb/0123456789abcdef"
TG_ARN = f"arn:aws:elasticloadbalancing:us-east-1:{ACCOUNT_ID}:targetgroup/fairtix-lab-tg/0123456789abcdef"
OTHER_LB_ARN = f"arn:aws:elasticloadbalancing:us-east-1:{ACCOUNT_ID}:loadbalancer/app/someone-else/0123456789abcdef"
PROJECT = {"Name": "tag:project", "Values": ["fairtix-lab"]}
CANARY = {"Name": "tag:role", "Values": ["canary"]}

LAB = {
    ("ecs", "list_clusters"): {"clusterArns": [CLUSTER_ARN]},
    ("elbv2", "describe_load_balancers"): {"LoadBalancers": [
        {"LoadBalancerName": "fairtix-lab-alb", "LoadBalancerArn": LB_ARN, "CreatedTime": CREATED}]},
    ("elbv2", "describe_target_groups"): {"TargetGroups": [
        {"TargetGroupName": "fairtix-lab-tg", "TargetGroupArn": TG_ARN}]},
    ("rds", "describe_db_instances"): {"DBInstances": [
        {"DBInstanceIdentifier": "fairtix-lab-db", "DBInstanceStatus": "available",
         "DeletionProtection": True, "InstanceCreateTime": CREATED}]},
    ("elasticache", "describe_replication_groups"): {"ReplicationGroups": [
        {"ReplicationGroupId": "fairtix-lab-valkey", "Status": "available",
         "ReplicationGroupCreateTime": CREATED}]},
    ("elasticache", "describe_cache_clusters"): {"CacheClusters": [
        # A member of the replication group: deleted with it, never on its own.
        {"CacheClusterId": "fairtix-lab-valkey-001", "ReplicationGroupId": "fairtix-lab-valkey",
         "CacheClusterStatus": "available"}]},
    ("ec2", "describe_nat_gateways"): {"NatGateways": [
        {"NatGatewayId": "nat-0abc", "State": "available", "CreateTime": CREATED}]},
    ("ec2", "describe_vpc_endpoints"): {"VpcEndpoints": [
        {"VpcEndpointId": "vpce-0abc", "VpcEndpointType": "Interface", "State": "available"}]},
    ("ec2", "describe_addresses"): {"Addresses": [{"AllocationId": "eipalloc-0abc", "PublicIp": "192.0.2.10"}]},
}

# The calls a live run makes, in the order TD:80-88 requires.
EXPECTED_ORDER = [
    "ecs.update_service fairtix-lab-cluster/fairtix-lab-backend desiredCount=0",
    "ecs.delete_service fairtix-lab-cluster/fairtix-lab-backend force",
    "ecs.delete_cluster fairtix-lab-cluster",
    "elbv2.modify_load_balancer_attributes fairtix-lab-alb deletion_protection.enabled=false",
    "elbv2.delete_load_balancer fairtix-lab-alb",
    "elbv2.delete_target_group fairtix-lab-tg",
    "elasticache.delete_replication_group fairtix-lab-valkey",
    "rds.modify_db_instance fairtix-lab-db DeletionProtection=false",
    "rds.delete_db_instance fairtix-lab-db skip-final-snapshot delete-automated-backups",
    "ec2.delete_nat_gateway nat-0abc",
    "ec2.delete_vpc_endpoints vpce-0abc",
    "ec2.release_address eipalloc-0abc",
    "sns.publish",
]


class Recorder:
    """Records "iamprefix:Operation" for every call any attached client makes (before the stub answers)."""

    def __init__(self):
        self.calls = []

    def attach(self, client):
        prefix = client.meta.service_model.endpoint_prefix

        def hook(params, model, **_):
            self.calls.append((f"{prefix}:{model.name}", params))

        client.meta.events.register("before-parameter-build", hook)

    @property
    def actions(self):
        return [action for action, _ in self.calls]


@pytest.fixture
def aws(stubs):
    """The census clients plus SSM and SNS, all stubbed and recorded."""
    ssm, ssm_stub = stub_client("ssm")
    sns, sns_stub = stub_client("sns")
    recorder = Recorder()
    for client in [*stubs.clients.values(), ssm, sns]:
        recorder.attach(client)

    class Aws:
        clients = stubs.as_clients()

    Aws.ssm, Aws.ssm_stub, Aws.sns, Aws.sns_stub, Aws.recorder = ssm, ssm_stub, sns, sns_stub, recorder
    yield Aws
    ssm_stub.deactivate()
    sns_stub.deactivate()


def stub_expiry(aws, value=EXPIRES):
    aws.ssm_stub.add_response("get_parameter", {"Parameter": {"Name": "/fairtix-lab/expires_at", "Value": value}},
                              {"Name": "/fairtix-lab/expires_at"})


def stub_lab_census(stubs, overrides=None):
    stub_account(stubs, {**LAB, **(overrides or {})}, billable_only=True)
    stubs.add("ecs", "list_services", {"serviceArns": [SERVICE_ARN]}, {"cluster": CLUSTER_ARN})
    stubs.add("ecs", "describe_services", {"services": [{"serviceArn": SERVICE_ARN, "createdAt": CREATED}],
                                           "failures": []}, {"cluster": CLUSTER_ARN, "services": [SERVICE_ARN]})


def stub_reap_reads(stubs, *, protected=True):
    """The read calls the deletion steps make, per service, in their order."""
    stubs.add("ecs", "list_clusters", LAB[("ecs", "list_clusters")])
    stubs.add("ecs", "list_services", {"serviceArns": [SERVICE_ARN]}, {"cluster": CLUSTER_ARN})
    stubs.add("elbv2", "describe_load_balancers", LAB[("elbv2", "describe_load_balancers")])
    stubs.add("elbv2", "describe_load_balancer_attributes",
              {"Attributes": [{"Key": "deletion_protection.enabled", "Value": "true" if protected else "false"}]},
              {"LoadBalancerArn": LB_ARN})
    stubs.add("elasticache", "describe_replication_groups", LAB[("elasticache", "describe_replication_groups")])
    stubs.add("elasticache", "describe_cache_clusters", LAB[("elasticache", "describe_cache_clusters")])
    stubs.add("elasticache", "describe_serverless_caches", {"ServerlessCaches": []})
    stubs.add("rds", "describe_db_instances", LAB[("rds", "describe_db_instances")])


def stub_live_lab(stubs, aws):
    """Every call of a full live run against LAB, in each service's order."""
    stub_expiry(aws)
    stub_lab_census(stubs)
    stubs.add("ecs", "list_clusters", LAB[("ecs", "list_clusters")])
    stubs.add("ecs", "list_services", {"serviceArns": [SERVICE_ARN]}, {"cluster": CLUSTER_ARN})
    stubs.add("ecs", "update_service", {}, {"cluster": CLUSTER_ARN, "service": SERVICE_ARN, "desiredCount": 0})
    stubs.add("ecs", "delete_service", {}, {"cluster": CLUSTER_ARN, "service": SERVICE_ARN, "force": True})
    stubs.add("ecs", "delete_cluster", {}, {"cluster": CLUSTER_ARN})

    stubs.add("elbv2", "describe_load_balancers", LAB[("elbv2", "describe_load_balancers")])
    stubs.add("elbv2", "describe_load_balancer_attributes",
              {"Attributes": [{"Key": "deletion_protection.enabled", "Value": "true"}]}, {"LoadBalancerArn": LB_ARN})
    stubs.add("elbv2", "modify_load_balancer_attributes", {},
              {"LoadBalancerArn": LB_ARN, "Attributes": [{"Key": "deletion_protection.enabled", "Value": "false"}]})
    stubs.add("elbv2", "delete_load_balancer", {}, {"LoadBalancerArn": LB_ARN})
    stubs.add("elbv2", "describe_target_groups", LAB[("elbv2", "describe_target_groups")])
    stubs.add("elbv2", "delete_target_group", {}, {"TargetGroupArn": TG_ARN})

    stubs.add("elasticache", "describe_replication_groups", LAB[("elasticache", "describe_replication_groups")])
    stubs.add("elasticache", "delete_replication_group", {},
              {"ReplicationGroupId": "fairtix-lab-valkey", "RetainPrimaryCluster": False})
    stubs.add("elasticache", "describe_cache_clusters", LAB[("elasticache", "describe_cache_clusters")])
    stubs.add("elasticache", "describe_serverless_caches", {"ServerlessCaches": []})

    stubs.add("rds", "describe_db_instances", LAB[("rds", "describe_db_instances")])
    stubs.add("rds", "modify_db_instance", {},
              {"DBInstanceIdentifier": "fairtix-lab-db", "DeletionProtection": False, "ApplyImmediately": True})
    stubs.add("rds", "delete_db_instance", {},
              {"DBInstanceIdentifier": "fairtix-lab-db", "SkipFinalSnapshot": True, "DeleteAutomatedBackups": True})

    stubs.add("ec2", "describe_nat_gateways", LAB[("ec2", "describe_nat_gateways")], {"Filter": [PROJECT]})
    stubs.add("ec2", "delete_nat_gateway", {}, {"NatGatewayId": "nat-0abc"})
    stubs.add("ec2", "describe_vpc_endpoints", LAB[("ec2", "describe_vpc_endpoints")], {"Filters": [PROJECT]})
    stubs.add("ec2", "delete_vpc_endpoints", {"Unsuccessful": []}, {"VpcEndpointIds": ["vpce-0abc"]})
    stubs.add("ec2", "describe_addresses", LAB[("ec2", "describe_addresses")], {"Filters": [PROJECT]})
    stubs.add("ec2", "release_address", {}, {"AllocationId": "eipalloc-0abc"})

    aws.sns_stub.add_response("publish", {"MessageId": "m-1"},
                              {"TopicArn": TOPIC, "Subject": "fairtix-lab reaper acted", "Message": ANY})


def run(aws, **kwargs):
    kwargs.setdefault("topic_arn", TOPIC)
    kwargs.setdefault("now", NOW)
    return reaper.run(aws.clients, aws.ssm, aws.sns, **kwargs)


def sns_message(aws):
    return next(params["Message"] for action, params in aws.recorder.calls if action == "sns:Publish")


# --- decide() with the billable census and grace = 2 h ---------------------------------

def test_empty_billable_census_is_noop_and_sends_nothing(stubs, aws):
    stub_expiry(aws, "none")
    stub_account(stubs, billable_only=True)

    result = run(aws)

    assert result["decision"] == "noop"
    assert result["calls"] == []
    stubs.assert_done()
    aws.sns_stub.assert_no_pending_responses()


def test_inside_the_two_hour_grace_is_noop(stubs, aws):
    stub_expiry(aws)
    stub_lab_census(stubs)

    result = run(aws, now=NOW - timedelta(seconds=2))  # expiry + 1 h 59 m 59 s

    assert result["decision"] == "noop"
    assert result["calls"] == []
    stubs.assert_done()


def test_only_the_billable_census_is_taken(stubs, aws):
    # No free lister (ENIs, VPCs, SGs, ECR, secrets, logs, KMS) is queued, so calling one fails.
    stub_expiry(aws)
    stub_account(stubs, billable_only=True)

    run(aws)

    assert not [a for a in aws.recorder.actions
                if a.split(":")[0] in ("ecr", "secretsmanager", "logs", "kms")
                or a in ("ec2:DescribeVpcs", "ec2:DescribeSecurityGroups", "ec2:DescribeNetworkInterfaces")]


def test_unreadable_expiry_counts_as_missing_and_waits_for_the_grace(stubs, aws):
    aws.ssm_stub.add_client_error("get_parameter", service_error_code="ParameterNotFound", http_status_code=400)
    stub_lab_census(stubs)

    # The oldest billable item is 3 h old, past the 2 h grace: destroy.
    stub_reap_reads(stubs)
    stubs.add("elbv2", "describe_target_groups", LAB[("elbv2", "describe_target_groups")])
    stubs.add("ec2", "describe_nat_gateways", LAB[("ec2", "describe_nat_gateways")], {"Filter": [PROJECT]})
    stubs.add("ec2", "describe_vpc_endpoints", LAB[("ec2", "describe_vpc_endpoints")], {"Filters": [PROJECT]})
    stubs.add("ec2", "describe_addresses", LAB[("ec2", "describe_addresses")], {"Filters": [PROJECT]})

    result = run(aws, dry_run=True)

    assert result["decision"] == "destroy"


# --- The deletion order (TD:80-88) -------------------------------------------------------

def test_live_run_deletes_in_the_design_order_then_emails(stubs, aws):
    stub_live_lab(stubs, aws)

    result = run(aws)

    assert result["decision"] == "destroy"
    assert result["calls"] == EXPECTED_ORDER
    assert result["deferred"] == [] and result["failures"] == []
    stubs.assert_done()
    aws.sns_stub.assert_no_pending_responses()
    assert reaper.ACTED_MESSAGE in sns_message(aws)


def test_email_names_resources_without_arns_or_the_account_id(stubs, aws):
    stub_live_lab(stubs, aws)

    run(aws)

    message = sns_message(aws)
    assert "fairtix-lab-db" in message
    assert ACCOUNT_ID not in message
    assert "arn:" not in message


def test_output_never_carries_arns_or_the_account_id(stubs, aws, capsys):
    stub_live_lab(stubs, aws)

    run(aws)

    printed = capsys.readouterr()
    assert ACCOUNT_ID not in printed.out + printed.err
    assert "arn:" not in printed.out + printed.err


def test_unprotected_alb_and_db_skip_the_modify_calls(stubs, aws):
    stub_expiry(aws)
    unprotected_db = {"DBInstances": [{"DBInstanceIdentifier": "fairtix-lab-db", "DBInstanceStatus": "available",
                                       "DeletionProtection": False, "InstanceCreateTime": CREATED}]}
    stub_lab_census(stubs, {("rds", "describe_db_instances"): unprotected_db})
    stub_reap_reads(stubs, protected=False)
    # Replace the queued protected-DB read with the unprotected one.
    stubs.stubbers["rds"]._queue.pop()
    stubs.add("rds", "describe_db_instances", unprotected_db)
    stubs.add("elbv2", "describe_target_groups", LAB[("elbv2", "describe_target_groups")])
    stubs.add("ec2", "describe_nat_gateways", {"NatGateways": []}, {"Filter": [PROJECT]})
    stubs.add("ec2", "describe_vpc_endpoints", {"VpcEndpoints": []}, {"Filters": [PROJECT]})
    stubs.add("ec2", "describe_addresses", {"Addresses": []}, {"Filters": [PROJECT]})

    result = run(aws, dry_run=True)

    assert not [c for c in result["calls"] if "modify" in c]
    assert "elbv2.delete_load_balancer fairtix-lab-alb" in result["calls"]
    assert "rds.delete_db_instance fairtix-lab-db skip-final-snapshot delete-automated-backups" in result["calls"]


# --- What it never touches ---------------------------------------------------------------

def test_never_touches_resources_outside_the_lab_prefix(stubs, aws):
    stub_expiry(aws)
    foreign = {
        ("ecs", "list_clusters"): {"clusterArns": []},
        ("elbv2", "describe_load_balancers"): {"LoadBalancers": [
            {"LoadBalancerName": "someone-else", "LoadBalancerArn": OTHER_LB_ARN, "CreatedTime": CREATED}]},
        ("elbv2", "describe_target_groups"): {"TargetGroups": []},
        ("rds", "describe_db_instances"): {"DBInstances": [
            {"DBInstanceIdentifier": "fairtix-lab-bootstrap-x", "DBInstanceStatus": "available",
             "InstanceCreateTime": CREATED},
            {"DBInstanceIdentifier": "fairtix-labx", "DBInstanceStatus": "available", "InstanceCreateTime": CREATED}]},
        ("elasticache", "describe_replication_groups"): {"ReplicationGroups": []},
        ("elasticache", "describe_cache_clusters"): {"CacheClusters": []},
    }
    stub_account(stubs, foreign, billable_only=True)
    stubs.add("ecs", "list_clusters", {"clusterArns": []})
    stubs.add("elbv2", "describe_load_balancers", foreign[("elbv2", "describe_load_balancers")])
    stubs.add("elbv2", "describe_target_groups", {"TargetGroups": []})
    stubs.add("elasticache", "describe_replication_groups", {"ReplicationGroups": []})
    stubs.add("elasticache", "describe_cache_clusters", {"CacheClusters": []})
    stubs.add("elasticache", "describe_serverless_caches", {"ServerlessCaches": []})
    stubs.add("rds", "describe_db_instances", foreign[("rds", "describe_db_instances")])
    stubs.add("ec2", "describe_nat_gateways", {"NatGateways": []}, {"Filter": [PROJECT]})
    stubs.add("ec2", "describe_vpc_endpoints", {"VpcEndpoints": []}, {"Filters": [PROJECT]})
    stubs.add("ec2", "describe_addresses", {"Addresses": []}, {"Filters": [PROJECT]})
    aws.sns_stub.add_response("publish", {"MessageId": "m-1"},
                              {"TopicArn": TOPIC, "Subject": "fairtix-lab reaper acted", "Message": ANY})

    result = run(aws)

    # The census still sees them (destroy), so the email goes out, but nothing is deleted.
    assert result["decision"] == "destroy"
    assert result["calls"] == ["sns.publish"]
    stubs.assert_done()
    assert "someone-else" in sns_message(aws)


def test_outside_test_mode_never_touches_the_vpc_ecr_secrets_or_logs(stubs, aws):
    stub_live_lab(stubs, aws)

    run(aws)

    touched = aws.recorder.actions
    assert not [a for a in touched if a.split(":")[0] in ("ecr", "secretsmanager", "logs", "kms")]
    assert not [a for a in touched if a in ("ec2:DeleteVpc", "ec2:DeleteSecurityGroup", "ec2:DeleteSubnet",
                                            "ec2:DescribeVpcs", "ec2:DescribeSecurityGroups")]


# --- --dry-run ---------------------------------------------------------------------------

def test_dry_run_makes_no_mutating_call(stubs, aws, capsys):
    # Only read responses are queued: any delete, modify or publish would find no stub and fail.
    stub_expiry(aws)
    stub_lab_census(stubs)
    stub_reap_reads(stubs)
    stubs.add("elbv2", "describe_target_groups", LAB[("elbv2", "describe_target_groups")])
    stubs.add("ec2", "describe_nat_gateways", LAB[("ec2", "describe_nat_gateways")], {"Filter": [PROJECT]})
    stubs.add("ec2", "describe_vpc_endpoints", LAB[("ec2", "describe_vpc_endpoints")], {"Filters": [PROJECT]})
    stubs.add("ec2", "describe_addresses", LAB[("ec2", "describe_addresses")], {"Filters": [PROJECT]})

    result = run(aws, dry_run=True)

    assert result["calls"] == EXPECTED_ORDER
    stubs.assert_done()
    mutating = [a for a in aws.recorder.actions if not a.split(":")[1].startswith(("Describe", "List", "Get"))]
    assert mutating == []
    out = capsys.readouterr().out
    assert "DRY-RUN would call ecs.delete_cluster fairtix-lab-cluster" in out
    assert "DRY-RUN would call sns.publish" in out


# --- Errors ------------------------------------------------------------------------------

def test_census_error_raises_and_nothing_is_deleted(stubs, aws):
    stub_expiry(aws)
    stubs.error("ecs", "list_clusters", "ThrottlingException")

    with pytest.raises(CensusError):
        run(aws)

    assert [a for a in aws.recorder.actions if not a.split(":")[1].startswith(("Describe", "List", "Get"))] == []
    aws.sns_stub.assert_no_pending_responses()


def test_busy_resource_is_deferred_without_raising(stubs, aws):
    stub_live_lab(stubs, aws)
    queue = stubs.stubbers["elbv2"]._queue
    index = next(i for i, r in enumerate(queue) if r["operation_name"] == "DeleteTargetGroup")
    del queue[index]
    stubs.stubbers["elbv2"].add_client_error("delete_target_group", service_error_code="ResourceInUse",
                                             http_status_code=400)

    result = run(aws)

    assert result["deferred"] == ["elbv2.delete_target_group fairtix-lab-tg (ResourceInUse)"]
    assert result["failures"] == []
    assert "elbv2.delete_target_group fairtix-lab-tg" not in result["calls"]
    assert "rds.delete_db_instance fairtix-lab-db skip-final-snapshot delete-automated-backups" in result["calls"]
    assert "deferred" in sns_message(aws).lower()


def test_access_denied_keeps_going_then_raises_after_the_email(stubs, aws):
    stub_live_lab(stubs, aws)
    queue = stubs.stubbers["rds"]._queue
    index = next(i for i, r in enumerate(queue) if r["operation_name"] == "DeleteDBInstance")
    del queue[index]
    stubs.stubbers["rds"].add_client_error("delete_db_instance", service_error_code="AccessDenied",
                                           http_status_code=403)

    with pytest.raises(reaper.ReaperError, match="1 failure"):
        run(aws)

    # The later steps still ran, and the email went out first.
    assert "ec2:ReleaseAddress" in aws.recorder.actions
    assert "sns:Publish" in aws.recorder.actions
    stubs.assert_done()


def test_a_failed_step_listing_doesnt_stop_later_steps(stubs, aws):
    stub_expiry(aws)
    stub_lab_census(stubs)
    stubs.error("ecs", "list_clusters", "ThrottlingException")
    stubs.add("elbv2", "describe_load_balancers", {"LoadBalancers": []})
    stubs.add("elbv2", "describe_target_groups", {"TargetGroups": []})
    stubs.add("elasticache", "describe_replication_groups", {"ReplicationGroups": []})
    stubs.add("elasticache", "describe_cache_clusters", {"CacheClusters": []})
    stubs.add("elasticache", "describe_serverless_caches", {"ServerlessCaches": []})
    stubs.add("rds", "describe_db_instances", LAB[("rds", "describe_db_instances")])
    stubs.add("ec2", "describe_nat_gateways", {"NatGateways": []}, {"Filter": [PROJECT]})
    stubs.add("ec2", "describe_vpc_endpoints", {"VpcEndpoints": []}, {"Filters": [PROJECT]})
    stubs.add("ec2", "describe_addresses", {"Addresses": []}, {"Filters": [PROJECT]})

    with pytest.raises(reaper.ReaperError, match="ecs: ThrottlingException"):
        run(aws, dry_run=True)

    assert "rds:DescribeDBInstances" in aws.recorder.actions


def test_vpc_endpoint_unsuccessful_item_is_a_failure(stubs, aws):
    stub_live_lab(stubs, aws)
    queue = stubs.stubbers["ec2"]._queue
    index = next(i for i, r in enumerate(queue) if r["operation_name"] == "DeleteVpcEndpoints")
    queue[index]["response"] = (queue[index]["response"][0], {"Unsuccessful": [
        {"ResourceId": "vpce-0abc", "Error": {"Code": "UnauthorizedOperation", "Message": "stubbed"}}]})

    with pytest.raises(reaper.ReaperError):
        run(aws)


def test_resources_already_deleting_are_skipped(stubs, aws):
    stub_expiry(aws)
    deleting = {
        ("ecs", "list_clusters"): {"clusterArns": []},
        ("elbv2", "describe_load_balancers"): {"LoadBalancers": []},
        ("elbv2", "describe_target_groups"): {"TargetGroups": []},
        ("rds", "describe_db_instances"): {"DBInstances": [
            {"DBInstanceIdentifier": "fairtix-lab-db", "DBInstanceStatus": "deleting", "InstanceCreateTime": CREATED}]},
        ("elasticache", "describe_replication_groups"): {"ReplicationGroups": [
            {"ReplicationGroupId": "fairtix-lab-valkey", "Status": "deleting"}]},
        ("elasticache", "describe_cache_clusters"): {"CacheClusters": []},
        ("ec2", "describe_nat_gateways"): {"NatGateways": [{"NatGatewayId": "nat-0abc", "State": "deleting"}]},
    }
    stub_account(stubs, deleting, billable_only=True)
    stubs.add("ecs", "list_clusters", {"clusterArns": []})
    stubs.add("elbv2", "describe_load_balancers", {"LoadBalancers": []})
    stubs.add("elbv2", "describe_target_groups", {"TargetGroups": []})
    stubs.add("elasticache", "describe_replication_groups", deleting[("elasticache", "describe_replication_groups")])
    stubs.add("elasticache", "describe_cache_clusters", {"CacheClusters": []})
    stubs.add("elasticache", "describe_serverless_caches", {"ServerlessCaches": []})
    stubs.add("rds", "describe_db_instances", deleting[("rds", "describe_db_instances")])
    stubs.add("ec2", "describe_nat_gateways", deleting[("ec2", "describe_nat_gateways")], {"Filter": [PROJECT]})
    stubs.add("ec2", "describe_vpc_endpoints", {"VpcEndpoints": []}, {"Filters": [PROJECT]})
    stubs.add("ec2", "describe_addresses", {"Addresses": []}, {"Filters": [PROJECT]})

    result = run(aws, dry_run=True)

    assert result["calls"] == ["sns.publish"]


# --- Test mode (T5) ----------------------------------------------------------------------

def test_test_mode_counts_and_deletes_the_canary_vpc(stubs, aws):
    stub_expiry(aws, "2026-10-22T11:50:00Z")  # NOW is expiry + 10 m: past a 5-minute grace
    stub_account(stubs, billable_only=True)
    vpcs = {"Vpcs": [{"VpcId": "vpc-0canary", "IsDefault": False}]}
    stubs.add("ec2", "describe_vpcs", vpcs, {"Filters": [PROJECT, CANARY]})
    stubs.add("ecs", "list_clusters", {"clusterArns": []})
    stubs.add("elbv2", "describe_load_balancers", {"LoadBalancers": []})
    stubs.add("elbv2", "describe_target_groups", {"TargetGroups": []})
    stubs.add("elasticache", "describe_replication_groups", {"ReplicationGroups": []})
    stubs.add("elasticache", "describe_cache_clusters", {"CacheClusters": []})
    stubs.add("elasticache", "describe_serverless_caches", {"ServerlessCaches": []})
    stubs.add("rds", "describe_db_instances", {"DBInstances": []})
    stubs.add("ec2", "describe_nat_gateways", {"NatGateways": []}, {"Filter": [PROJECT]})
    stubs.add("ec2", "describe_vpc_endpoints", {"VpcEndpoints": []}, {"Filters": [PROJECT]})
    stubs.add("ec2", "describe_addresses", {"Addresses": []}, {"Filters": [PROJECT]})
    stubs.add("ec2", "describe_vpcs", vpcs, {"Filters": [PROJECT, CANARY]})
    stubs.add("ec2", "describe_security_groups", {"SecurityGroups": [
        {"GroupId": "sg-0default", "GroupName": "default"},
        {"GroupId": "sg-0canary", "GroupName": "fairtix-lab-canary"}]},
        {"Filters": [{"Name": "vpc-id", "Values": ["vpc-0canary"]}]})
    stubs.add("ec2", "delete_security_group", {}, {"GroupId": "sg-0canary"})
    stubs.add("ec2", "delete_vpc", {}, {"VpcId": "vpc-0canary"})
    aws.sns_stub.add_response("publish", {"MessageId": "m-1"},
                              {"TopicArn": TOPIC, "Subject": "fairtix-lab reaper acted", "Message": ANY})

    result = run(aws, test_mode=True, grace=timedelta(minutes=5))

    assert result["decision"] == "destroy"
    assert result["calls"] == ["ec2.delete_security_group sg-0canary", "ec2.delete_vpc vpc-0canary", "sns.publish"]
    stubs.assert_done()


def test_test_mode_with_no_canary_left_is_noop(stubs, aws):
    stub_expiry(aws, "2026-10-22T11:50:00Z")
    stub_account(stubs, billable_only=True)
    stubs.add("ec2", "describe_vpcs", {"Vpcs": []}, {"Filters": [PROJECT, CANARY]})

    result = run(aws, test_mode=True, grace=timedelta(minutes=5))

    assert result["decision"] == "noop"
    stubs.assert_done()


# --- The action list T5b simulates -------------------------------------------------------

def test_every_call_a_live_run_makes_is_in_the_simulated_action_list(stubs, aws):
    stub_live_lab(stubs, aws)

    run(aws)

    declared = set(reaper.READ_ACTIONS) | set(reaper.MUTATING_ACTIONS)
    assert set(aws.recorder.actions) <= declared, set(aws.recorder.actions) - declared


def test_every_call_a_test_mode_run_makes_is_declared(stubs, aws):
    test_test_mode_counts_and_deletes_the_canary_vpc(stubs, aws)

    declared = set(reaper.READ_ACTIONS) | set(reaper.MUTATING_ACTIONS) | set(reaper.TEST_MODE_ACTIONS) | set(reaper.TEST_MODE_READ_ACTIONS)
    assert set(aws.recorder.actions) <= declared, set(aws.recorder.actions) - declared


def test_mutating_actions_name_lab_scoped_resources():
    for action, resource in {**reaper.MUTATING_ACTIONS, **reaper.TEST_MODE_ACTIONS}.items():
        assert "fairtix-lab-" in resource or resource.endswith("/*") or resource.endswith(":*"), action
    tag_scoped = {a for a, r in {**reaper.MUTATING_ACTIONS, **reaper.TEST_MODE_ACTIONS}.items()
                  if "fairtix-lab-" not in r}
    assert tag_scoped == set(reaper.TAG_SCOPED_ACTIONS) | {"rds:DeleteDBInstanceAutomatedBackup"}


# --- The Lambda handler and the CLI -------------------------------------------------------

def test_handler_reads_its_settings_from_the_environment(monkeypatch):
    seen = {}

    def fake_run(clients, ssm, sns, **kwargs):
        seen.update(kwargs)
        return {"decision": "noop"}

    monkeypatch.setattr(reaper, "run", fake_run)
    monkeypatch.setattr(reaper, "make_clients", lambda: (None, None, None))
    monkeypatch.setenv("REAPER_SNS_TOPIC_ARN", TOPIC)
    monkeypatch.setenv("REAPER_GRACE_MINUTES", "5")
    monkeypatch.setenv("REAPER_TEST_MODE", "true")

    assert reaper.handler({}, None) == {"decision": "noop"}
    assert seen["topic_arn"] == TOPIC
    assert seen["grace"] == timedelta(minutes=5)
    assert seen["test_mode"] is True
    assert seen["dry_run"] is False


def test_handler_defaults_to_a_two_hour_grace_outside_test_mode(monkeypatch):
    seen = {}
    monkeypatch.setattr(reaper, "run", lambda *a, **kw: seen.update(kw) or {"decision": "noop"})
    monkeypatch.setattr(reaper, "make_clients", lambda: (None, None, None))
    monkeypatch.setenv("REAPER_SNS_TOPIC_ARN", TOPIC)
    monkeypatch.delenv("REAPER_GRACE_MINUTES", raising=False)
    monkeypatch.delenv("REAPER_TEST_MODE", raising=False)

    reaper.handler({"dry_run": True}, None)

    assert seen["grace"] == timedelta(hours=2)
    assert seen["test_mode"] is False
    assert seen["dry_run"] is True


@pytest.mark.parametrize("grace", ["0", "-5", "two", ""])
def test_handler_rejects_a_bad_grace(monkeypatch, grace):
    monkeypatch.setattr(reaper, "make_clients", lambda: (None, None, None))
    monkeypatch.setenv("REAPER_SNS_TOPIC_ARN", TOPIC)
    monkeypatch.setenv("REAPER_GRACE_MINUTES", grace)

    with pytest.raises(reaper.ReaperError):
        reaper.handler({}, None)


def test_handler_without_a_topic_raises(monkeypatch):
    monkeypatch.delenv("REAPER_SNS_TOPIC_ARN", raising=False)
    with pytest.raises(reaper.ReaperError, match="REAPER_SNS_TOPIC_ARN"):
        reaper.handler({}, None)


def test_cli_refuses_to_run_without_dry_run(capsys):
    with pytest.raises(SystemExit) as exc:
        reaper.main([])
    assert exc.value.code == 2
    assert "--dry-run" in capsys.readouterr().err


def test_cli_dry_run_exits_0(stubs, aws, capsys):
    stub_expiry(aws, "none")
    stub_account(stubs, billable_only=True)

    code = reaper.main(["--dry-run"], clients=aws.clients, ssm=aws.ssm, now=NOW)

    assert code == 0
    assert "decision noop" in capsys.readouterr().out


def test_cli_census_error_exits_2(stubs, aws, capsys):
    stub_expiry(aws)
    stubs.error("ecs", "list_clusters", "AccessDeniedException")

    code = reaper.main(["--dry-run"], clients=aws.clients, ssm=aws.ssm, now=NOW)

    assert code == 2
    assert "AccessDeniedException" in capsys.readouterr().err


def test_census_module_exposes_the_lab_prefix_contract():
    assert census.BOOTSTRAP_PREFIX.startswith(reaper.LAB_PREFIX)
