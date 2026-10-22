# The lab's expiry (TD §2). Bootstrap owns the name; its value belongs to the workflows:
# teardown.preflight writes now + TTL before every apply, and lab-teardown resets it to
# "none" after a clean teardown. Terraform sets "none" once and then ignores the value.
resource "aws_ssm_parameter" "expires_at" {
  name        = local.expires_at_parameter
  description = "FairTix lab expiry (ISO-8601 UTC, or none). Written by teardown.preflight; read by teardown.decide and the reaper."
  type        = "String"
  tier        = "Standard"
  value       = "none"

  lifecycle {
    ignore_changes = [value]
  }
}
