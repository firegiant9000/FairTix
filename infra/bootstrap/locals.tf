data "aws_caller_identity" "current" {}

# The admin is whoever applies bootstrap (Arlo). For an assumed-role session this resolves
# to the role, not the session, which is what aws:PrincipalArn compares against.
data "aws_iam_session_context" "admin" {
  arn = data.aws_caller_identity.current.arn
}

locals {
  region  = "us-east-1"
  account = data.aws_caller_identity.current.account_id

  # Bootstrap naming contract (infra/teardown/README.md): everything is fairtix-lab-bootstrap-*.
  prefix = "fairtix-lab-bootstrap"

  # The suffix is random, not the account id, because the name is committed in the lab and
  # canary backend blocks of a public repo.
  state_bucket = "${local.prefix}-tfstate-c2d02e4f"

  repository    = "firegiant9000/FairTix"
  repository_id = "1135223352" # immutable: survives a rename, and a re-created repo gets a new one

  expires_at_parameter = "/fairtix-lab/expires_at"
  expires_at_arn       = "arn:aws:ssm:${local.region}:${local.account}:parameter${local.expires_at_parameter}"

  reaper_name     = "${local.prefix}-reaper" # the Lambda and its schedule (teardown.preflight reads it)
  reaper_log_name = "/aws/lambda/${local.reaper_name}"
  topic_name      = "${local.prefix}-alerts"

  # Every list and describe teardown.decide (the census) and teardown.verify make. None can
  # be scoped below "*". Keep in step with infra/teardown/census.py and verify.py.
  census_read_actions = [
    "ec2:DescribeAddresses",
    "ec2:DescribeInstances",
    "ec2:DescribeNatGateways",
    "ec2:DescribeNetworkInterfaces",
    "ec2:DescribeRegions",
    "ec2:DescribeSecurityGroups",
    "ec2:DescribeVpcEndpoints",
    "ec2:DescribeVpcs",
    "ecr:DescribeRepositories",
    "ecs:DescribeServices",
    "ecs:ListClusters",
    "ecs:ListServices",
    "elasticache:DescribeCacheClusters",
    "elasticache:DescribeReplicationGroups",
    "elasticache:DescribeServerlessCacheSnapshots",
    "elasticache:DescribeServerlessCaches",
    "elasticache:DescribeSnapshots",
    "elasticloadbalancing:DescribeLoadBalancers",
    "elasticloadbalancing:DescribeTargetGroups",
    "kms:DescribeKey",
    "kms:ListKeys",
    "logs:DescribeLogGroups",
    "rds:DescribeDBInstanceAutomatedBackups",
    "rds:DescribeDBInstances",
    "rds:DescribeDBSnapshots",
    "secretsmanager:ListSecrets",
  ]
}
