# GitHub Actions OIDC and the two lab roles (TD:58, TD:101-105). No access keys anywhere.
#
# Each role's trust pins, all with StringEquals (no wildcards):
#   aud              = sts.amazonaws.com
#   sub              = repo:firegiant9000/FairTix:environment:<the role's environment>
#   job_workflow_ref = firegiant9000/FairTix/.github/workflows/<its workflow>@refs/heads/main
#   repository_id    = the repo's immutable numeric id
# so only that workflow file, on main, in that environment, of this repository can assume it.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

locals {
  github_roles = {
    lab_apply = {
      name        = "${local.prefix}-lab-apply"
      environment = "lab-apply"
      workflow    = "lab-apply.yml"
    }
    lab_teardown = {
      name        = "${local.prefix}-lab-teardown"
      environment = "lab-teardown"
      workflow    = "lab-teardown.yml"
    }
  }
}

data "aws_iam_policy_document" "github_trust" {
  for_each = local.github_roles

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${local.repository}:environment:${each.value.environment}"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:job_workflow_ref"
      values   = ["${local.repository}/.github/workflows/${each.value.workflow}@refs/heads/main"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:repository_id"
      values   = [local.repository_id]
    }
  }
}

resource "aws_iam_role" "lab_apply" {
  name                 = local.github_roles.lab_apply.name
  description          = "lab-apply.yml on main, environment lab-apply (FairTix L3)"
  assume_role_policy   = data.aws_iam_policy_document.github_trust["lab_apply"].json
  max_session_duration = 3600
}

resource "aws_iam_role" "lab_teardown" {
  name                 = local.github_roles.lab_teardown.name
  description          = "lab-teardown.yml on main, environment lab-teardown (FairTix L3)"
  assume_role_policy   = data.aws_iam_policy_document.github_trust["lab_teardown"].json
  max_session_duration = 3600
}
