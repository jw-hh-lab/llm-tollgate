output "role_arn" {
  description = "Actions 워크플로의 role-to-assume에 넣을 값"
  value       = aws_iam_role.github_actions.arn
}
