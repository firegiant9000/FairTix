# Sensitive so they stay out of the apply output. Read one with
# `terraform -chdir=infra/bootstrap output -raw <name>` and paste it straight into the
# GitHub environment secret; never into an issue, PR, log or doc (they hold the account id).
output "lab_apply_role_arn" {
  description = "The LAB_APPLY_ROLE_ARN environment secret on lab-apply"
  value       = aws_iam_role.lab_apply.arn
  sensitive   = true
}

output "lab_teardown_role_arn" {
  description = "The LAB_TEARDOWN_ROLE_ARN environment secret on lab-teardown"
  value       = aws_iam_role.lab_teardown.arn
  sensitive   = true
}

output "reaper_role_arn" {
  description = "For T5b (simulate-principal-policy)"
  value       = aws_iam_role.reaper.arn
  sensitive   = true
}

output "state_bucket" {
  description = "The lab and canary backends' bucket (committed in their backend blocks)"
  value       = aws_s3_bucket.state.bucket
}
