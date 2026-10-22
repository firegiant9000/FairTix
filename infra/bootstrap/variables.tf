variable "alert_email" {
  description = "Where the reaper's SNS email and the budget alerts go. Set it in a gitignored terraform.tfvars or TF_VAR_alert_email; never commit it."
  type        = string
  sensitive   = true

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.alert_email))
    error_message = "alert_email must be an email address."
  }
}

variable "reaper_test_mode" {
  description = "T5 only: the reaper also counts and deletes the canary VPC, and may use a short grace. Set back to false after T5."
  type        = bool
  default     = false
}

variable "reaper_grace_minutes" {
  description = "How long past expiry the reaper waits, so layer 2 acts first (TD:81). Below 120 only in test mode."
  type        = number
  default     = 120

  validation {
    condition     = var.reaper_grace_minutes == floor(var.reaper_grace_minutes) && var.reaper_grace_minutes >= 1 && (var.reaper_grace_minutes >= 120 || var.reaper_test_mode)
    error_message = "reaper_grace_minutes must be a whole number, at least 120 unless reaper_test_mode is true (T5 uses 5)."
  }
}
