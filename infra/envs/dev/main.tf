locals {
  name = "llm-tollgate"
}

module "network" {
  source = "../../modules/network"
  name   = local.name
}

module "compute" {
  source            = "../../modules/compute"
  name              = local.name
  subnet_id         = module.network.public_subnet_ids["ap-northeast-2a"]
  security_group_id = module.network.app_sg_id
}

module "scheduler" {
  source      = "../../modules/scheduler"
  name        = local.name
  instance_id = module.compute.instance_id
}

module "backup" {
  source        = "../../modules/backup"
  name          = local.name
  ec2_role_name = "${local.name}-ec2-role"
}

module "cdn" {
  source        = "../../modules/cdn"
  name          = local.name
  origin_domain = module.compute.public_dns
}

module "github_actions" {
  source      = "../../modules/github-actions"
  name        = local.name
  instance_id = module.compute.instance_id
}