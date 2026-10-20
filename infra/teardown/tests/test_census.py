"""Census builder tests: every AWS call is a botocore Stubber response, never the network."""
from datetime import datetime, timezone

import boto3
import pytest

from teardown import census as c
from teardown.census import CensusError, build_census, is_bootstrap
from teardown.teardown_policy import Census, Item

from .conftest import ACCOUNT_ID as ACCOUNT
from .conftest import stub_account

UTC = timezone.utc
T0 = datetime(2026, 10, 20, 9, 0, tzinfo=UTC)
T1 = datetime(2026, 10, 20, 10, 0, tzinfo=UTC)


def ids(items):
    return sorted((i.kind, i.id) for i in items)


# --- ECS ------------------------------------------------------------------------------

def test_ecs_lists_clusters_and_services_with_created_at(stubs):
    cluster = f"arn:aws:ecs:us-east-1:{ACCOUNT}:cluster/fairtix-lab"
    service = f"arn:aws:ecs:us-east-1:{ACCOUNT}:service/fairtix-lab/fairtix-lab-api"
    stubs.add("ecs", "list_clusters", {"clusterArns": [cluster]})
    stubs.add("ecs", "list_services", {"serviceArns": [service]}, {"cluster": cluster})
    stubs.add(
        "ecs",
        "describe_services",
        {"services": [{"serviceArn": service, "serviceName": "fairtix-lab-api", "createdAt": T1}], "failures": []},
        {"cluster": cluster, "services": [service]},
    )
    items = c.list_ecs(stubs.as_clients())
    assert items == [
        Item("ecs_cluster", "fairtix-lab", None),
        Item("ecs_service", "fairtix-lab/fairtix-lab-api", T1),
    ]
    stubs.assert_done()


def test_ecs_describe_services_batches_of_ten(stubs):
    cluster = f"arn:aws:ecs:us-east-1:{ACCOUNT}:cluster/fairtix-lab"
    arns = [f"arn:aws:ecs:us-east-1:{ACCOUNT}:service/fairtix-lab/s{i}" for i in range(11)]
    stubs.add("ecs", "list_clusters", {"clusterArns": [cluster]})
    stubs.add("ecs", "list_services", {"serviceArns": arns})
    for batch in (arns[:10], arns[10:]):
        stubs.add(
            "ecs",
            "describe_services",
            {"services": [{"serviceArn": a, "createdAt": T1} for a in batch], "failures": []},
            {"cluster": cluster, "services": batch},
        )
    items = c.list_ecs(stubs.as_clients())
    assert len([i for i in items if i.kind == "ecs_service"]) == 11
    stubs.assert_done()


def test_ecs_describe_services_failure_raises(stubs):
    cluster = f"arn:aws:ecs:us-east-1:{ACCOUNT}:cluster/fairtix-lab"
    service = f"arn:aws:ecs:us-east-1:{ACCOUNT}:service/fairtix-lab/x"
    stubs.add("ecs", "list_clusters", {"clusterArns": [cluster]})
    stubs.add("ecs", "list_services", {"serviceArns": [service]})
    stubs.add("ecs", "describe_services", {"services": [], "failures": [{"arn": service, "reason": "MISSING"}]})
    with pytest.raises(CensusError, match="failure"):
        build_census_only(stubs, c.list_ecs)


def test_ecs_list_services_pages_are_all_read(stubs):
    cluster = f"arn:aws:ecs:us-east-1:{ACCOUNT}:cluster/fairtix-lab"
    a = f"arn:aws:ecs:us-east-1:{ACCOUNT}:service/fairtix-lab/a"
    b = f"arn:aws:ecs:us-east-1:{ACCOUNT}:service/fairtix-lab/b"
    stubs.add("ecs", "list_clusters", {"clusterArns": [cluster]})
    stubs.add("ecs", "list_services", {"serviceArns": [a], "nextToken": "p2"})
    stubs.add("ecs", "list_services", {"serviceArns": [b]}, {"cluster": cluster, "nextToken": "p2"})
    stubs.add("ecs", "describe_services", {"services": [{"serviceArn": a}, {"serviceArn": b}], "failures": []})
    items = c.list_ecs(stubs.as_clients())
    assert ids(items) == [("ecs_cluster", "fairtix-lab"), ("ecs_service", "fairtix-lab/a"), ("ecs_service", "fairtix-lab/b")]


# --- ELBv2 -----------------------------------------------------------------------------

def test_elbv2_lists_load_balancers_and_target_groups(stubs):
    stubs.add("elbv2", "describe_load_balancers", {"LoadBalancers": [
        {"LoadBalancerArn": f"arn:aws:elasticloadbalancing:us-east-1:{ACCOUNT}:loadbalancer/app/fairtix-lab-alb/1",
         "LoadBalancerName": "fairtix-lab-alb", "CreatedTime": T1},
    ]})
    stubs.add("elbv2", "describe_target_groups", {"TargetGroups": [
        {"TargetGroupArn": f"arn:aws:elasticloadbalancing:us-east-1:{ACCOUNT}:targetgroup/fairtix-lab-tg/1",
         "TargetGroupName": "fairtix-lab-tg"},
    ]})
    assert c.list_elbv2(stubs.as_clients()) == [
        Item("elb_load_balancer", "fairtix-lab-alb", T1),
        Item("elb_target_group", "fairtix-lab-tg", None),
    ]


def test_elbv2_multi_page_load_balancers(stubs):
    stubs.add("elbv2", "describe_load_balancers",
              {"LoadBalancers": [{"LoadBalancerName": "a", "CreatedTime": T0}], "NextMarker": "m2"})
    stubs.add("elbv2", "describe_load_balancers",
              {"LoadBalancers": [{"LoadBalancerName": "b", "CreatedTime": T1}]}, {"Marker": "m2"})
    stubs.add("elbv2", "describe_target_groups", {"TargetGroups": []})
    assert ids(c.list_elbv2(stubs.as_clients())) == [("elb_load_balancer", "a"), ("elb_load_balancer", "b")]


# --- RDS ---------------------------------------------------------------------------------

def test_rds_lists_instances_manual_snapshots_and_automated_backups(stubs):
    stubs.add("rds", "describe_db_instances", {"DBInstances": [
        {"DBInstanceIdentifier": "fairtix-lab-db", "InstanceCreateTime": T0},
        {"DBInstanceIdentifier": "fairtix-lab-restore"},  # still creating: no time yet
    ]})
    stubs.add("rds", "describe_db_snapshots",
              {"DBSnapshots": [{"DBSnapshotIdentifier": "fairtix-lab-final", "SnapshotCreateTime": T1}]},
              {"SnapshotType": "manual"})
    stubs.add("rds", "describe_db_instance_automated_backups", {"DBInstanceAutomatedBackups": [
        {"DBInstanceIdentifier": "fairtix-lab-old", "DbiResourceId": "db-ABC", "InstanceCreateTime": T0},
    ]})
    assert c.list_rds(stubs.as_clients()) == [
        Item("rds_instance", "fairtix-lab-db", T0),
        Item("rds_instance", "fairtix-lab-restore", None),
        Item("rds_snapshot", "fairtix-lab-final", T1),
        Item("rds_automated_backup", "fairtix-lab-old (db-ABC)", T0),
    ]


# --- ElastiCache --------------------------------------------------------------------------

def test_elasticache_lists_clusters_groups_serverless_and_snapshots(stubs):
    stubs.add("elasticache", "describe_cache_clusters",
              {"CacheClusters": [{"CacheClusterId": "fairtix-lab-cache-001", "CacheClusterCreateTime": T0}]})
    stubs.add("elasticache", "describe_replication_groups",
              {"ReplicationGroups": [{"ReplicationGroupId": "fairtix-lab-cache", "ReplicationGroupCreateTime": T0}]})
    stubs.add("elasticache", "describe_serverless_caches",
              {"ServerlessCaches": [{"ServerlessCacheName": "fairtix-lab-sl", "CreateTime": T1}]})
    stubs.add("elasticache", "describe_snapshots", {"Snapshots": [
        {"SnapshotName": "fairtix-lab-snap", "NodeSnapshots": [
            {"SnapshotCreateTime": T1}, {"SnapshotCreateTime": T0}]},
        {"SnapshotName": "fairtix-lab-creating"},
    ]})
    stubs.add("elasticache", "describe_serverless_cache_snapshots",
              {"ServerlessCacheSnapshots": [{"ServerlessCacheSnapshotName": "fairtix-lab-sl-snap", "CreateTime": T1}]})
    assert c.list_elasticache(stubs.as_clients()) == [
        Item("elasticache_cluster", "fairtix-lab-cache-001", T0),
        Item("elasticache_replication_group", "fairtix-lab-cache", T0),
        Item("elasticache_serverless_cache", "fairtix-lab-sl", T1),
        Item("elasticache_snapshot", "fairtix-lab-snap", T0),
        Item("elasticache_snapshot", "fairtix-lab-creating", None),
        Item("elasticache_snapshot", "fairtix-lab-sl-snap", T1),
    ]


# --- EC2: EIPs, NAT, ENIs, VPCs, SGs -----------------------------------------------------------

def test_eips_are_listed_without_a_created_time(stubs):
    stubs.add("ec2", "describe_addresses", {"Addresses": [
        {"AllocationId": "eipalloc-0abc", "PublicIp": "203.0.113.10"},
        {"PublicIp": "203.0.113.11"},
    ]})
    assert c.list_eips(stubs.as_clients()) == [
        Item("eip", "eipalloc-0abc", None),
        Item("eip", "203.0.113.11", None),
    ]


def test_nat_any_state_but_deleted_counts_with_no_tag_filter(stubs):
    stubs.add("ec2", "describe_nat_gateways", {"NatGateways": [
        {"NatGatewayId": "nat-available", "State": "available", "CreateTime": T0},
        {"NatGatewayId": "nat-pending", "State": "pending", "CreateTime": T1},
        {"NatGatewayId": "nat-failed", "State": "failed", "CreateTime": T1},
        {"NatGatewayId": "nat-deleting", "State": "deleting", "CreateTime": T1},
        {"NatGatewayId": "nat-deleted", "State": "deleted", "CreateTime": T0},
        {"NatGatewayId": "nat-nostate"},
    ]}, {})  # {} = called with no Filter: the lookup must not depend on tags
    assert ids(c.list_nat_gateways(stubs.as_clients())) == [
        ("nat_gateway", "nat-available"),
        ("nat_gateway", "nat-deleting"),
        ("nat_gateway", "nat-failed"),
        ("nat_gateway", "nat-nostate"),
        ("nat_gateway", "nat-pending"),
    ]


def test_enis_are_listed(stubs):
    stubs.add("ec2", "describe_network_interfaces", {"NetworkInterfaces": [{"NetworkInterfaceId": "eni-0abc"}]})
    assert c.list_enis(stubs.as_clients()) == [Item("eni", "eni-0abc", None)]


def test_vpcs_skip_only_the_default_vpc(stubs):
    stubs.add("ec2", "describe_vpcs", {"Vpcs": [
        {"VpcId": "vpc-default", "IsDefault": True},
        {"VpcId": "vpc-lab", "IsDefault": False},
        {"VpcId": "vpc-unknown"},
    ]})
    assert ids(c.list_vpcs(stubs.as_clients())) == [("vpc", "vpc-lab"), ("vpc", "vpc-unknown")]


def test_security_groups_skip_only_default_groups(stubs):
    stubs.add("ec2", "describe_security_groups", {"SecurityGroups": [
        {"GroupId": "sg-default", "GroupName": "default"},
        {"GroupId": "sg-alb", "GroupName": "fairtix-lab-alb"},
        {"GroupId": "sg-lookalike", "GroupName": "default-copy"},
    ]})
    assert ids(c.list_security_groups(stubs.as_clients())) == [
        ("security_group", "sg-alb (fairtix-lab-alb)"),
        ("security_group", "sg-lookalike (default-copy)"),
    ]


def test_ec2_multi_page_enis(stubs):
    stubs.add("ec2", "describe_network_interfaces",
              {"NetworkInterfaces": [{"NetworkInterfaceId": "eni-1"}], "NextToken": "t2"})
    stubs.add("ec2", "describe_network_interfaces",
              {"NetworkInterfaces": [{"NetworkInterfaceId": "eni-2"}]}, {"NextToken": "t2"})
    assert ids(c.list_enis(stubs.as_clients())) == [("eni", "eni-1"), ("eni", "eni-2")]


# --- ECR, Secrets, logs, KMS ----------------------------------------------------------------

def test_ecr_repositories(stubs):
    stubs.add("ecr", "describe_repositories",
              {"repositories": [{"repositoryName": "fairtix-lab-backend", "createdAt": T0}]})
    assert c.list_ecr(stubs.as_clients()) == [Item("ecr_repository", "fairtix-lab-backend", T0)]


def test_secrets_include_pending_deletion_and_never_read_values(stubs):
    stubs.add("secretsmanager", "list_secrets", {"SecretList": [
        {"Name": "fairtix-lab/db", "CreatedDate": T0},
        {"Name": "fairtix-lab/old", "CreatedDate": T0, "DeletedDate": T1},
    ]}, {"IncludePlannedDeletion": True})
    assert c.list_secrets(stubs.as_clients()) == [
        Item("secret", "fairtix-lab/db", T0),
        Item("secret", "fairtix-lab/old (pending deletion)", T0),
    ]
    stubs.assert_done()  # no GetSecretValue was stubbed, so none could have been called


def test_secrets_multi_page(stubs):
    stubs.add("secretsmanager", "list_secrets",
              {"SecretList": [{"Name": "a"}], "NextToken": "n2"}, {"IncludePlannedDeletion": True})
    stubs.add("secretsmanager", "list_secrets",
              {"SecretList": [{"Name": "b"}]}, {"IncludePlannedDeletion": True, "NextToken": "n2"})
    assert ids(c.list_secrets(stubs.as_clients())) == [("secret", "a"), ("secret", "b")]


def test_log_groups_convert_epoch_millis(stubs):
    stubs.add("logs", "describe_log_groups", {"logGroups": [
        {"logGroupName": "/ecs/fairtix-lab", "creationTime": int(T0.timestamp() * 1000)},
        {"logGroupName": "/no/time"},
    ]})
    assert c.list_log_groups(stubs.as_clients()) == [
        Item("log_group", "/ecs/fairtix-lab", T0),
        Item("log_group", "/no/time", None),
    ]


def test_kms_counts_customer_managed_keys_in_any_state_and_skips_aws_managed(stubs):
    stubs.add("kms", "list_keys", {"Keys": [{"KeyId": "k-aws"}, {"KeyId": "k-cmk"}], "Truncated": True, "NextMarker": "m"})
    stubs.add("kms", "list_keys", {"Keys": [{"KeyId": "k-pending"}], "Truncated": False}, {"Marker": "m"})
    stubs.add("kms", "describe_key", {"KeyMetadata": {"KeyId": "k-aws", "KeyManager": "AWS", "KeyState": "Enabled"}},
              {"KeyId": "k-aws"})
    stubs.add("kms", "describe_key", {"KeyMetadata": {
        "KeyId": "k-cmk", "KeyManager": "CUSTOMER", "KeyState": "Enabled", "CreationDate": T0}}, {"KeyId": "k-cmk"})
    stubs.add("kms", "describe_key", {"KeyMetadata": {
        "KeyId": "k-pending", "KeyManager": "CUSTOMER", "KeyState": "PendingDeletion", "CreationDate": T1}},
        {"KeyId": "k-pending"})
    assert c.list_kms_keys(stubs.as_clients()) == [
        Item("kms_key", "k-cmk (Enabled)", T0),
        Item("kms_key", "k-pending (PendingDeletion)", T1),
    ]
    stubs.assert_done()


def test_kms_key_with_unknown_manager_counts(stubs):
    stubs.add("kms", "list_keys", {"Keys": [{"KeyId": "k"}], "Truncated": False})
    stubs.add("kms", "describe_key", {"KeyMetadata": {"KeyId": "k"}})
    assert c.list_kms_keys(stubs.as_clients()) == [Item("kms_key", "k (unknown)", None)]


# --- Errors, throttles and truncated pagination raise ------------------------------------------

def build_census_only(stubs, lister):
    """Run one lister through the same error wrapping build_census uses."""
    return c._run_lister(lister, stubs.as_clients())


@pytest.mark.parametrize("code", ["ThrottlingException", "Throttling", "RequestLimitExceeded", "AccessDenied",
                                  "InternalError", "UnauthorizedOperation"])
def test_client_errors_raise_census_error(stubs, code):
    stubs.error("ec2", "describe_nat_gateways", code)
    with pytest.raises(CensusError) as exc:
        build_census_only(stubs, c.list_nat_gateways)
    assert code in str(exc.value)


def test_error_on_a_later_page_raises_rather_than_returning_page_one(stubs):
    stubs.add("rds", "describe_db_instances",
              {"DBInstances": [{"DBInstanceIdentifier": "a"}], "Marker": "m2"})
    stubs.error("rds", "describe_db_instances", "Throttling")
    with pytest.raises(CensusError, match="Throttling"):
        build_census_only(stubs, c.list_rds)


def test_kms_truncated_listing_that_then_fails_raises(stubs):
    stubs.add("kms", "list_keys", {"Keys": [{"KeyId": "k"}], "Truncated": True, "NextMarker": "m"})
    stubs.error("kms", "list_keys", "KMSInternalException", 500)
    with pytest.raises(CensusError, match="KMSInternalException"):
        build_census_only(stubs, c.list_kms_keys)


def test_repeated_page_token_raises(stubs):
    stubs.add("logs", "describe_log_groups", {"logGroups": [{"logGroupName": "a"}], "nextToken": "same"})
    stubs.add("logs", "describe_log_groups", {"logGroups": [{"logGroupName": "b"}], "nextToken": "same"})
    with pytest.raises(CensusError, match="PaginationError"):
        build_census_only(stubs, c.list_log_groups)


def test_missing_list_key_in_a_response_raises_not_empty(stubs):
    stubs.add("ecr", "describe_repositories", {})
    with pytest.raises(CensusError, match="KeyError"):
        build_census_only(stubs, c.list_ecr)


def test_unexpected_exception_type_is_wrapped(stubs):
    def broken(_clients):
        raise ZeroDivisionError("boom")

    with pytest.raises(CensusError):
        c._run_lister(broken, stubs.as_clients())


def test_census_error_message_carries_no_raw_aws_message(stubs):
    stubs.stubbers["ec2"].add_client_error(
        "describe_addresses",
        service_error_code="UnauthorizedOperation",
        service_message=f"User arn:aws:sts::{ACCOUNT}:assumed-role/x is not authorized",
    )
    with pytest.raises(CensusError) as exc:
        build_census_only(stubs, c.list_eips)
    assert ACCOUNT not in str(exc.value)
    assert "UnauthorizedOperation" in str(exc.value)


def test_build_census_error_in_one_service_fails_the_whole_census(stubs):
    stubs.add("ecs", "list_clusters", {"clusterArns": []})
    stubs.error("elbv2", "describe_load_balancers")
    with pytest.raises(CensusError):
        build_census(stubs.as_clients())


# --- Full builds ------------------------------------------------------------------------------------

def test_empty_account_is_an_empty_census(stubs):
    stub_account(stubs)
    assert build_census(stubs.as_clients()) == Census()
    stubs.assert_done()


def test_billable_only_census_skips_free_listings(stubs):
    stub_account(stubs, billable_only=True)
    assert build_census(stubs.as_clients(), billable_only=True) == Census()
    stubs.assert_done()  # no free-service call was stubbed, and none was left pending


def test_full_census_splits_billable_and_free(stubs):
    stub_account(stubs, {
        ("rds", "describe_db_instances"): {"DBInstances": [{"DBInstanceIdentifier": "fairtix-lab-db",
                                                            "InstanceCreateTime": T1}]},
        ("logs", "describe_log_groups"): {"logGroups": [{"logGroupName": "/ecs/fairtix-lab",
                                                         "creationTime": int(T0.timestamp() * 1000)}]},
    })
    census = build_census(stubs.as_clients())
    assert census.billable == (Item("rds_instance", "fairtix-lab-db", T1),)
    assert census.free == (Item("log_group", "/ecs/fairtix-lab", T0),)
    assert census.oldest_created == T1


# --- Bootstrap allow-list (TD:177) --------------------------------------------------------------

def test_bootstrap_only_account_is_an_empty_census(stubs):
    stub_account(stubs, {("logs", "describe_log_groups"): {"logGroups": [
        {"logGroupName": "/aws/lambda/fairtix-lab-bootstrap-reaper", "creationTime": 0},
    ]}})
    assert build_census(stubs.as_clients()).is_empty()


@pytest.mark.parametrize(
    "item",
    [
        Item("log_group", "/aws/lambda/fairtix-lab-bootstrap-reaperx"),
        Item("log_group", "/aws/lambda/fairtix-lab-bootstrap-reaper/extra"),
        Item("log_group", "/aws/lambda/fairtix-lab-bootstrap-"),
        Item("log_group", "/aws/lambda/fairtix-lab-bootstrapper"),
        Item("log_group", "/aws/lambda/Fairtix-lab-bootstrap-reaper"),
        Item("log_group", " /aws/lambda/fairtix-lab-bootstrap-reaper"),
        Item("log_group", "/ecs/fairtix-lab-bootstrap-reaper"),
        Item("log_group", "/aws/lambda/fairtix-lab-reaper"),
        Item("rds_instance", "fairtix-lab-bootstrap-reaper"),
        Item("secret", "fairtix-lab-bootstrap-reaper"),
        Item("ecs_cluster", "fairtix-lab-bootstrap-reaper"),
        Item("security_group", "/aws/lambda/fairtix-lab-bootstrap-reaper"),
    ],
)
def test_lookalike_lab_items_are_not_excluded(item):
    assert not is_bootstrap(item)


def test_lookalike_log_group_stays_in_the_census(stubs):
    stub_account(stubs, {("logs", "describe_log_groups"): {"logGroups": [
        {"logGroupName": "/aws/lambda/fairtix-lab-bootstrap-reaper"},
        {"logGroupName": "/aws/lambda/fairtix-lab-bootstrap-reaper-v2"},
    ]}})
    assert build_census(stubs.as_clients()).free == (
        Item("log_group", "/aws/lambda/fairtix-lab-bootstrap-reaper-v2", None),
    )


# --- No network ----------------------------------------------------------------------------------

def test_an_unstubbed_real_client_cannot_reach_aws():
    client = boto3.client("sts", region_name="us-east-1")
    with pytest.raises(Exception) as exc:
        client.get_caller_identity()
    assert "network access attempted" in str(exc.value) or "Could not connect" in str(exc.value)
