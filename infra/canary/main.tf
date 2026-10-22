# The canary (TD:153, T4/T5): free resources only, so the teardown layers can be tested
# end to end for about $0. A VPC with no subnets, no internet gateway, no NAT and no public
# IPv4; a security group with no rules; a 1-day log group; a tagged SSM parameter.
#
# Arlo applies it locally (docs/infra/teardown-tests.md, T4). lab-teardown.yml destroys it.
# Everything is tagged project=fairtix-lab and role=canary: the teardown role and the
# reaper's test mode can delete a VPC or security group only with both tags.
terraform {
  required_version = "~> 1.16.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.70"
    }
  }

  # Committed because the workflows run `init` with no -backend-config. The bucket is
  # created by infra/bootstrap; lab-teardown may read and write only lab/ and canary/.
  backend "s3" {
    bucket       = "fairtix-lab-bootstrap-tfstate-c2d02e4f"
    key          = "canary/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = "us-east-1"

  default_tags {
    tags = {
      project = "fairtix-lab"
      role    = "canary"
    }
  }
}

resource "aws_vpc" "canary" {
  cidr_block = "10.250.0.0/24"

  tags = {
    Name = "fairtix-lab-canary"
  }
}

# No ingress and no egress: Terraform removes the default allow-all egress rule.
resource "aws_security_group" "canary" {
  name        = "fairtix-lab-canary"
  description = "FairTix teardown canary (no rules)"
  vpc_id      = aws_vpc.canary.id
}

resource "aws_cloudwatch_log_group" "canary" {
  name              = "fairtix-lab-canary"
  retention_in_days = 1
}

resource "aws_ssm_parameter" "canary" {
  name  = "/fairtix-lab/canary"
  type  = "String"
  tier  = "Standard"
  value = "teardown canary"
}
