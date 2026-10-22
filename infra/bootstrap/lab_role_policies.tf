# What the two lab roles may do in stage A: state, the expiry, the census, and (teardown
# only) destroying the canary. Stage B adds what creating and destroying infra/lab needs.
# T5b checks every action here with simulate-principal-policy.

locals {
  # Terraform's S3 backend with use_lockfile: the state object and its .tflock next to it.
  state_objects = {
    lab    = "${aws_s3_bucket.state.arn}/lab/*"
    canary = "${aws_s3_bucket.state.arn}/canary/*"
  }

  canary_log_group_arn = "arn:aws:logs:${local.region}:${local.account}:log-group:fairtix-lab-canary"
  canary_parameter_arn = "arn:aws:ssm:${local.region}:${local.account}:parameter/fairtix-lab/canary"
}

# --- lab-teardown ------------------------------------------------------------------------

data "aws_iam_policy_document" "lab_teardown" {
  statement {
    sid       = "StateBucketList"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.state.arn]
  }

  statement {
    sid       = "StateObjects"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = [local.state_objects.lab, local.state_objects.canary]
  }

  # teardown.decide reads it; the workflow resets it to "none" after a clean teardown.
  statement {
    sid       = "Expiry"
    actions   = ["ssm:GetParameter", "ssm:PutParameter"]
    resources = [local.expires_at_arn]
  }

  statement {
    sid       = "CensusAndVerify"
    actions   = local.census_read_actions
    resources = ["*"]
  }

  # Terraform's refresh before destroy. Describe/List actions can't be scoped below "*".
  statement {
    sid       = "RefreshReads"
    actions   = ["ec2:Describe*", "ssm:DescribeParameters"]
    resources = ["*"]
  }

  statement {
    sid       = "CanaryRefresh"
    actions   = ["logs:ListTagsForResource", "ssm:GetParameter", "ssm:GetParameters", "ssm:ListTagsForResource"]
    resources = [local.canary_log_group_arn, "${local.canary_log_group_arn}:*", local.canary_parameter_arn]
  }

  # The canary (T4): only resources tagged project=fairtix-lab and role=canary.
  statement {
    sid     = "CanaryNetworkDestroy"
    actions = ["ec2:DeleteSecurityGroup", "ec2:DeleteVpc"]
    resources = [
      "arn:aws:ec2:${local.region}:${local.account}:security-group/*",
      "arn:aws:ec2:${local.region}:${local.account}:vpc/*",
    ]

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

  statement {
    sid       = "CanaryLogsAndParameterDestroy"
    actions   = ["logs:DeleteLogGroup", "ssm:DeleteParameter"]
    resources = [local.canary_log_group_arn, "${local.canary_log_group_arn}:*", local.canary_parameter_arn]
  }
}

resource "aws_iam_role_policy" "lab_teardown" {
  name   = "${local.prefix}-lab-teardown"
  role   = aws_iam_role.lab_teardown.id
  policy = data.aws_iam_policy_document.lab_teardown.json
}

# --- lab-apply ---------------------------------------------------------------------------

data "aws_iam_policy_document" "lab_apply" {
  statement {
    sid       = "StateBucketList"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.state.arn]
  }

  statement {
    sid       = "StateObjects"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = [local.state_objects.lab]
  }

  # teardown.preflight: the reaper schedule must be ENABLED, then it writes the expiry.
  statement {
    sid       = "ReaperSchedule"
    actions   = ["scheduler:GetSchedule"]
    resources = ["arn:aws:scheduler:${local.region}:${local.account}:schedule/default/${local.reaper_name}"]
  }

  statement {
    sid       = "Expiry"
    actions   = ["ssm:GetParameter", "ssm:PutParameter"]
    resources = [local.expires_at_arn]
  }

  # In case apply ever runs teardown.verify.
  statement {
    sid       = "CensusAndVerify"
    actions   = local.census_read_actions
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "lab_apply" {
  name   = "${local.prefix}-lab-apply"
  role   = aws_iam_role.lab_apply.id
  policy = data.aws_iam_policy_document.lab_apply.json
}
