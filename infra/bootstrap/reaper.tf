# Layer 3: the reaper Lambda, its schedule, its role and its log group (TD:80-97).
# The code is infra/teardown/reaper.py; the handler is teardown.reaper.handler.

# Only the top-level modules of teardown/ (no tests), under teardown/ so the package's
# relative imports work. Line endings are normalised, so a Windows checkout builds the
# same zip (and source_code_hash) as Linux.
data "archive_file" "reaper" {
  type        = "zip"
  output_path = "${path.module}/build/reaper.zip"

  dynamic "source" {
    for_each = fileset("${path.module}/../teardown", "*.py")

    content {
      content  = replace(file("${path.module}/../teardown/${source.value}"), "\r\n", "\n")
      filename = "teardown/${source.value}"
    }
  }
}

# Declared here with a retention, so Lambda never auto-creates it. The census excludes it
# by this exact name (census.BOOTSTRAP_LOG_GROUPS).
resource "aws_cloudwatch_log_group" "reaper" {
  name              = local.reaper_log_name
  retention_in_days = 14
}

resource "aws_lambda_function" "reaper" {
  function_name    = local.reaper_name
  description      = "FairTix lab layer-3 teardown (docs/infra/teardown-design.md §3)"
  role             = aws_iam_role.reaper.arn
  runtime          = "python3.12"
  handler          = "teardown.reaper.handler"
  architectures    = ["arm64"]
  filename         = data.archive_file.reaper.output_path
  source_code_hash = data.archive_file.reaper.output_base64sha256
  timeout          = 300
  memory_size      = 256

  # boto3 comes from the Lambda runtime; the zip holds only the teardown modules.
  environment {
    variables = {
      REAPER_SNS_TOPIC_ARN = aws_sns_topic.alerts.arn
      REAPER_GRACE_MINUTES = tostring(var.reaper_grace_minutes)
      REAPER_TEST_MODE     = tostring(var.reaper_test_mode)
    }
  }

  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.reaper.name
  }

  depends_on = [aws_iam_role_policy.reaper]
}

# A failed run isn't retried: the next scheduled run is 30 minutes away, and the Errors
# alarm has already emailed.
resource "aws_lambda_function_event_invoke_config" "reaper" {
  function_name          = aws_lambda_function.reaper.function_name
  maximum_retry_attempts = 0
}

# --- The reaper's role ---------------------------------------------------------------------

data "aws_iam_policy_document" "reaper_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "reaper" {
  name               = local.reaper_name
  description        = "The FairTix lab reaper Lambda (layer 3)"
  assume_role_policy = data.aws_iam_policy_document.reaper_trust.json
}

locals {
  arn = {
    ecs   = "arn:aws:ecs:${local.region}:${local.account}"
    elb   = "arn:aws:elasticloadbalancing:${local.region}:${local.account}"
    cache = "arn:aws:elasticache:${local.region}:${local.account}"
    rds   = "arn:aws:rds:${local.region}:${local.account}"
    ec2   = "arn:aws:ec2:${local.region}:${local.account}"
  }
}

# TD:90-95. Every action matches teardown.reaper's READ_ACTIONS / MUTATING_ACTIONS /
# TEST_MODE_* lists, which T5b simulates.
data "aws_iam_policy_document" "reaper" {
  statement {
    sid = "BillableCensus"
    actions = [
      "ec2:DescribeAddresses",
      "ec2:DescribeNatGateways",
      "ec2:DescribeVpcEndpoints",
      "ecs:DescribeServices",
      "ecs:ListClusters",
      "ecs:ListServices",
      "elasticache:DescribeCacheClusters",
      "elasticache:DescribeReplicationGroups",
      "elasticache:DescribeServerlessCacheSnapshots",
      "elasticache:DescribeServerlessCaches",
      "elasticache:DescribeSnapshots",
      "elasticloadbalancing:DescribeLoadBalancerAttributes",
      "elasticloadbalancing:DescribeLoadBalancers",
      "elasticloadbalancing:DescribeTargetGroups",
      "rds:DescribeDBInstanceAutomatedBackups",
      "rds:DescribeDBInstances",
      "rds:DescribeDBSnapshots",
    ]
    resources = ["*"]
  }

  statement {
    sid       = "Expiry"
    actions   = ["ssm:GetParameter"]
    resources = [local.expires_at_arn]
  }

  statement {
    sid       = "EcsServices"
    actions   = ["ecs:UpdateService", "ecs:DeleteService"]
    resources = ["${local.arn.ecs}:service/fairtix-lab-*"]
  }

  statement {
    sid       = "EcsClusters"
    actions   = ["ecs:DeleteCluster"]
    resources = ["${local.arn.ecs}:cluster/fairtix-lab-*"]
  }

  statement {
    sid       = "LoadBalancers"
    actions   = ["elasticloadbalancing:ModifyLoadBalancerAttributes", "elasticloadbalancing:DeleteLoadBalancer"]
    resources = ["${local.arn.elb}:loadbalancer/app/fairtix-lab-*"]
  }

  statement {
    sid       = "TargetGroups"
    actions   = ["elasticloadbalancing:DeleteTargetGroup"]
    resources = ["${local.arn.elb}:targetgroup/fairtix-lab-*"]
  }

  # A replication group's delete is also authorised against its member clusters.
  statement {
    sid     = "ElastiCache"
    actions = ["elasticache:DeleteReplicationGroup", "elasticache:DeleteCacheCluster", "elasticache:DeleteServerlessCache"]
    resources = [
      "${local.arn.cache}:replicationgroup:fairtix-lab-*",
      "${local.arn.cache}:cluster:fairtix-lab-*",
      "${local.arn.cache}:serverlesscache:fairtix-lab-*",
    ]
  }

  statement {
    sid       = "RdsInstances"
    actions   = ["rds:ModifyDBInstance", "rds:DeleteDBInstance"]
    resources = ["${local.arn.rds}:db:fairtix-lab-*"]
  }

  # NAT gateways, VPC endpoints and Elastic IPs have no name: the tag is the scope.
  statement {
    sid     = "TaggedNetworkBillables"
    actions = ["ec2:DeleteNatGateway", "ec2:DeleteVpcEndpoints", "ec2:ReleaseAddress"]
    resources = [
      "${local.arn.ec2}:natgateway/*",
      "${local.arn.ec2}:vpc-endpoint/*",
      "${local.arn.ec2}:elastic-ip/*",
    ]

    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/project"
      values   = ["fairtix-lab"]
    }
  }

  statement {
    sid       = "Alerts"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alerts.arn]
  }

  statement {
    sid       = "OwnLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.reaper.arn}:*"]
  }

  # T5 only: the canary VPC and its security groups, by both tags.
  dynamic "statement" {
    for_each = var.reaper_test_mode ? [1] : []

    content {
      sid       = "TestModeCanaryReads"
      actions   = ["ec2:DescribeSecurityGroups", "ec2:DescribeVpcs"]
      resources = ["*"]
    }
  }

  dynamic "statement" {
    for_each = var.reaper_test_mode ? [1] : []

    content {
      sid       = "TestModeCanaryDeletes"
      actions   = ["ec2:DeleteSecurityGroup", "ec2:DeleteVpc"]
      resources = ["${local.arn.ec2}:security-group/*", "${local.arn.ec2}:vpc/*"]

      condition {
        test     = "StringEquals"
        variable = "aws:ResourceTag/project"
        values   = ["fairtix-lab"]
      }

      condition {
        test     = "StringEquals"
        variable = "aws:ResourceTag/role"
        values   = ["canary"]
      }
    }
  }
}

resource "aws_iam_role_policy" "reaper" {
  name   = local.reaper_name
  role   = aws_iam_role.reaper.id
  policy = data.aws_iam_policy_document.reaper.json
}

# --- The schedule: every 30 minutes, independent of GitHub --------------------------------

data "aws_iam_policy_document" "scheduler_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account]
    }
  }
}

resource "aws_iam_role" "scheduler" {
  name               = "${local.reaper_name}-scheduler"
  description        = "EventBridge Scheduler invoking the FairTix lab reaper"
  assume_role_policy = data.aws_iam_policy_document.scheduler_trust.json
}

data "aws_iam_policy_document" "scheduler" {
  statement {
    actions   = ["lambda:InvokeFunction"]
    resources = [aws_lambda_function.reaper.arn, "${aws_lambda_function.reaper.arn}:*"]
  }
}

resource "aws_iam_role_policy" "scheduler" {
  name   = "${local.reaper_name}-scheduler"
  role   = aws_iam_role.scheduler.id
  policy = data.aws_iam_policy_document.scheduler.json
}

# teardown.preflight refuses to apply unless this exact schedule is ENABLED.
resource "aws_scheduler_schedule" "reaper" {
  name                = local.reaper_name
  group_name          = "default"
  description         = "Runs the FairTix lab reaper (layer 3)"
  schedule_expression = "rate(30 minutes)"
  state               = "ENABLED"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = aws_lambda_function.reaper.arn
    role_arn = aws_iam_role.scheduler.arn

    retry_policy {
      maximum_retry_attempts = 0
    }
  }
}
