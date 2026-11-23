# GitHub Actions가 AWS 키 없이 AWS를 쓰게 해 주는 설정 (OIDC)
# 흐름: Actions가 GitHub에서 "나는 jw-hh-lab/llm-tollgate의 main 브랜치 작업이야"라는 증명서(토큰)를 받음
#      → AWS가 그 증명서를 확인하고 아래 역할(role)을 15분~1시간짜리 임시 키로 빌려줌

data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

# 1) AWS에게 "GitHub이 발급한 증명서를 믿어도 된다"고 등록 (계정에 한 번만)
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

# 2) Actions가 빌려 쓸 역할. 누가 빌릴 수 있는지(trust)를 우리 저장소 main 브랜치로 제한
resource "aws_iam_role" "github_actions" {
  name = "${var.name}-github-actions"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
                    # GitHub가 보내는 sub 형식이 두 가지라 둘 다 허용 (둘 다 main 브랜치만)
          "token.actions.githubusercontent.com:sub" = [
            "repo:${var.github_repo}:ref:refs/heads/main",          # 예전 형식: 조직/저장소
            "repo:${var.github_repo_with_ids}:ref:refs/heads/main", # 새 형식: 조직@ID/저장소@ID (CloudTrail에서 확인)
          ]
        }
      }
    }]
  })
}

# 3) 그 역할로 할 수 있는 일: 우리 EC2 한 대에 명령 보내기(배포), 켜고 끄기(버튼)
resource "aws_iam_role_policy" "github_actions" {
  name = "deploy-and-power"
  role = aws_iam_role.github_actions.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "RunCommandOnOurServerOnly"
        Effect = "Allow"
        Action = "ssm:SendCommand"
        Resource = [
          "arn:aws:ec2:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:instance/${var.instance_id}",
          "arn:aws:ssm:${data.aws_region.current.region}::document/AWS-RunShellScript"
        ]
      },
      {
        Sid      = "ReadCommandResult"
        Effect   = "Allow"
        Action   = ["ssm:GetCommandInvocation", "ec2:DescribeInstances"]
        Resource = "*" # 이 두 동작은 자원 단위 제한을 지원하지 않음 (읽기만 가능)
      },
      {
        Sid      = "StartStopOurServerOnly"
        Effect   = "Allow"
        Action   = ["ec2:StartInstances", "ec2:StopInstances"]
        Resource = "arn:aws:ec2:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:instance/${var.instance_id}"
      }
    ]
  })
}
