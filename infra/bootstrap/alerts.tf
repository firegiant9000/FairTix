# The email topic, the reaper's error alarm and the budget alerts (TD §2, §8).

# No SSE: CloudWatch alarms can't publish to a topic encrypted with the AWS-managed key,
# and a customer-managed key would break the naming contract (no CMKs).
resource "aws_sns_topic" "alerts" {
  name = local.topic_name
}

# Arlo confirms the subscription from the email AWS sends after the apply.
resource "aws_sns_topic_subscription" "alerts_email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# TD §7 row 12: a failed reaper run (an IAM gap, a throttle, an incomplete census) emails.
resource "aws_cloudwatch_metric_alarm" "reaper_errors" {
  alarm_name          = "${local.reaper_name}-errors"
  alarm_description   = "The FairTix lab reaper failed. Read its logs (${local.reaper_log_name}); lab resources may still be billing."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.reaper.function_name }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

# The reaper should run twice an hour. No invocation in an hour (the schedule disabled,
# the scheduler role broken, throttling) means layer 3 is silently gone: email.
resource "aws_cloudwatch_metric_alarm" "reaper_not_running" {
  alarm_name          = "${local.reaper_name}-not-running"
  alarm_description   = "The FairTix lab reaper hasn't run for an hour. Check the schedule ${local.reaper_name} and its role; layer 3 is down."
  namespace           = "AWS/Lambda"
  metric_name         = "Invocations"
  dimensions          = { FunctionName = aws_lambda_function.reaper.function_name }
  statistic           = "Sum"
  period              = 3600
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

# Notifications, NOT a cap (TD §8): Budgets data updates up to three times a day, and
# budget actions can't stop Fargate, an ALB or ElastiCache. Teardown is the cost control.
# One budget with four notifications rather than three budgets. Credits and refunds are
# excluded, so the alerts track gross charges.
resource "aws_budgets_budget" "monthly" {
  name         = "${local.prefix}-monthly"
  budget_type  = "COST"
  limit_amount = "20"
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_types {
    include_credit = false
    include_refund = false
  }

  dynamic "notification" {
    for_each = [
      { type = "ACTUAL", amount = 5 },
      { type = "ACTUAL", amount = 15 },
      { type = "ACTUAL", amount = 20 },
      { type = "FORECASTED", amount = 20 },
    ]

    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value.amount
      threshold_type             = "ABSOLUTE_VALUE"
      notification_type          = notification.value.type
      subscriber_email_addresses = [var.alert_email]
    }
  }
}
