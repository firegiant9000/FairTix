# The bootstrap stack: applied once, by hand, by Arlo (FT-3 stage A). See README.md.
#
# State: local on the first apply (the bucket it would live in doesn't exist yet), then
# migrated into that bucket under bootstrap/ (README.md, "State"). The lab roles have no
# access to bootstrap/, so a lab workflow can't rewrite the roles that trust it.
terraform {
  required_version = "~> 1.16.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.70"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.7"
    }
  }
}

provider "aws" {
  region = local.region

  default_tags {
    tags = {
      project = "fairtix-lab"
      stack   = "bootstrap"
    }
  }
}
