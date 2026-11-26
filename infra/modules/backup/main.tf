# MySQL 백업 보관용 S3 버킷 + EC2 역할에 "이 버킷만" 읽고 쓰는 권한
# 백업 흐름: k3s CronJob(mysqldump) → Pod가 EC2 역할로 S3에 업로드 (키 없음, IMDSv2 hop 2 덕분)
# 비용: 덤프가 수십 KB 수준 + 14일 뒤 자동 삭제라 사실상 $0

resource "aws_s3_bucket" "backup" {
  bucket = "${var.name}-backup-${var.bucket_suffix}"
}

# 공개 접근 전부 차단 (DB 덤프는 고객 데이터)
resource "aws_s3_bucket_public_access_block" "backup" {
  bucket                  = aws_s3_bucket.backup.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 저장할 때 자동 암호화 (S3 관리 키, 무료)
resource "aws_s3_bucket_server_side_encryption_configuration" "backup" {
  bucket = aws_s3_bucket.backup.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 오래된 백업은 자동 삭제 → 저장 요금이 계속 쌓이지 않게
resource "aws_s3_bucket_lifecycle_configuration" "backup" {
  bucket = aws_s3_bucket.backup.id
  rule {
    id     = "expire-old-dumps"
    status = "Enabled"
    filter {
      prefix = "mysql/"
    }
    expiration {
      days = var.retention_days
    }
  }
}

# EC2 역할(=k3s Pod들이 빌려 쓰는 신분증)에 이 버킷 권한만 추가. 지우기 권한은 안 줌(실수·공격으로 백업이 지워지지 않게)
resource "aws_iam_role_policy" "backup" {
  name = "${var.name}-backup-s3"
  role = var.ec2_role_name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ListBackupBucket"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.backup.arn
      },
      {
        Sid      = "ReadWriteDumps"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject"]
        Resource = "${aws_s3_bucket.backup.arn}/mysql/*"
      }
    ]
  })
}
