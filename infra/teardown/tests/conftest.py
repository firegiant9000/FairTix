"""Shared fixtures. No test may reach the network or AWS: every client is a Stubber."""
import socket

import boto3
import pytest
from botocore.stub import Stubber

from teardown.census import Clients

ACCOUNT_ID = "123456789012"  # the AWS documentation account; never a real one
SERVICES = ("ecs", "elbv2", "rds", "elasticache", "ec2", "ecr", "secretsmanager", "logs", "kms")


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    """Dummy credentials, IMDS off, and any socket connect fails loudly."""
    monkeypatch.setenv("AWS_EC2_METADATA_DISABLED", "true")
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "testing")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "testing")
    monkeypatch.setenv("AWS_DEFAULT_REGION", "us-east-1")
    for var in ("AWS_PROFILE", "AWS_SESSION_TOKEN", "AWS_ROLE_ARN", "AWS_WEB_IDENTITY_TOKEN_FILE"):
        monkeypatch.delenv(var, raising=False)

    def blocked(*_args, **_kwargs):
        raise RuntimeError("network access attempted in a unit test")

    monkeypatch.setattr(socket.socket, "connect", blocked)
    monkeypatch.setattr(socket.socket, "connect_ex", blocked)
    monkeypatch.setattr(socket, "create_connection", blocked)


def stub_client(service, region="us-east-1"):
    client = boto3.client(
        service,
        region_name=region,
        aws_access_key_id="testing",
        aws_secret_access_key="testing",
    )
    stubber = Stubber(client)
    stubber.activate()
    return client, stubber


class Stubs:
    """One stubbed client per service, all in one region."""

    def __init__(self, region="us-east-1"):
        self.clients = {}
        self.stubbers = {}
        for service in SERVICES:
            self.clients[service], self.stubbers[service] = stub_client(service, region)

    def add(self, service, operation, response, params=None):
        self.stubbers[service].add_response(operation, response, params)

    def error(self, service, operation, code="ThrottlingException", status=400):
        self.stubbers[service].add_client_error(
            operation, service_error_code=code, service_message="stubbed", http_status_code=status
        )

    def as_clients(self):
        return Clients(**{("secrets" if s == "secretsmanager" else s): c for s, c in self.clients.items()})

    def assert_done(self):
        for stubber in self.stubbers.values():
            stubber.assert_no_pending_responses()

    def close(self):
        for stubber in self.stubbers.values():
            stubber.deactivate()


@pytest.fixture
def stubs():
    s = Stubs()
    yield s
    s.close()


# Empty responses for each lister, in the order census.build_census calls them.
EMPTY_BILLABLE = [
    ("ecs", "list_clusters", {"clusterArns": []}),
    ("elbv2", "describe_load_balancers", {"LoadBalancers": []}),
    ("elbv2", "describe_target_groups", {"TargetGroups": []}),
    ("rds", "describe_db_instances", {"DBInstances": []}),
    ("rds", "describe_db_snapshots", {"DBSnapshots": []}),
    ("rds", "describe_db_instance_automated_backups", {"DBInstanceAutomatedBackups": []}),
    ("elasticache", "describe_cache_clusters", {"CacheClusters": []}),
    ("elasticache", "describe_replication_groups", {"ReplicationGroups": []}),
    ("elasticache", "describe_serverless_caches", {"ServerlessCaches": []}),
    ("elasticache", "describe_snapshots", {"Snapshots": []}),
    ("elasticache", "describe_serverless_cache_snapshots", {"ServerlessCacheSnapshots": []}),
    ("ec2", "describe_addresses", {"Addresses": []}),
    ("ec2", "describe_nat_gateways", {"NatGateways": []}),
]
EMPTY_FREE = [
    ("ec2", "describe_network_interfaces", {"NetworkInterfaces": []}),
    ("ec2", "describe_vpcs", {"Vpcs": []}),
    ("ec2", "describe_security_groups", {"SecurityGroups": []}),
    ("ecr", "describe_repositories", {"repositories": []}),
    ("secretsmanager", "list_secrets", {"SecretList": []}),
    ("logs", "describe_log_groups", {"logGroups": []}),
    ("kms", "list_keys", {"Keys": [], "Truncated": False}),
]


def stub_account(stubs, overrides=None, billable_only=False):
    """Stub every census call, empty unless `overrides[(service, op)]` gives a response."""
    overrides = overrides or {}
    plan = EMPTY_BILLABLE + ([] if billable_only else EMPTY_FREE)
    for service, operation, empty in plan:
        stubs.add(service, operation, overrides.get((service, operation), empty))
