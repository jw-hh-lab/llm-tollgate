output "sns_topic_arn" {
  description = "테스트 메시지를 보낼 때 쓰는 SNS 주제 ARN"
  value       = aws_sns_topic.cost.arn
}
