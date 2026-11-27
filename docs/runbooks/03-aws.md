# Sutherland Banking Platform – Provisioning Runbook: AWS

**Status: not yet run in a real account.** Written from `infra/terraform/aws`, `deploy/helm/pbp/values-aws.yaml` and the Helm chart (27 November 2026). Read the common runbook first: it has the decisions, the environment manifest, the deploy and the checks. AWS is the third cloud, after OCI and Azure. Console menus change; each step says what to create and how to set it, with the Terraform resource that does the same.

## 1. Before you start

- **Account and region**: Europe (London), `eu-west-2`, for UK customers. One account per environment is cleanest (AWS Organizations, Control Tower landing zone); one account with an `env` tag also works.
- **Access**: an IAM role with permission to manage VPC, EKS, IAM roles and policies, RDS, MSK, ElastiCache, KMS, Secrets Manager, S3, ECR, WAF and CloudWatch.
- **Service quotas** in `eu-west-2`: 12 vCPU of standard on-demand instances for dev and demo (3 × m6i.xlarge); 48 vCPU for production (6 nodes, autoscaling to 12: common runbook, section 3a). One Elastic IP for the NAT gateway. MSK: 3 brokers.
- **Tools on the operator's machine**: AWS CLI v2, `kubectl`, `helm`, Terraform 1.6+ (for the Terraform path).
- **Decisions** from the common runbook, section 2: environment name, modules, network range (default `10.30.0.0/16`).

**Terraform path**: in `infra/terraform/aws`, copy `terraform.tfvars.example` and `backend.hcl.example`. Fill in `region`, `env`, `vpc_cidr` and `thredd_ehi_source_cidrs`; pass `postgres_password`, `kafka_password` and `webhook_secret` as `TF_VAR_*` environment variables. Then `terraform init -backend-config=backend.hcl` and `terraform apply`. The state lives in an S3 bucket with a DynamoDB lock table, both created beforehand (`backend.hcl`).

## 2. Network (VPC)

| Create | Setting | Terraform |
| --- | --- | --- |
| VPC `pbp-<env>` | CIDR `10.30.0.0/16`; DNS support and host names on | `aws_vpc.main` |
| Internet gateway | For the public subnets | `aws_internet_gateway.igw` |
| Elastic IP `pbp-<env>-egress` | **This is the address the vendors allow-list** | `aws_eip.nat` |
| NAT gateway | In the first public subnet, using the Elastic IP. One NAT gives one address to register; a NAT per zone is more resilient but means three addresses | `aws_nat_gateway.nat` |
| Route tables | `public`: 0.0.0.0/0 → internet gateway. `private`: 0.0.0.0/0 → NAT gateway | `aws_route_table.public`, `aws_route_table.private` |
| S3 gateway endpoint | On the private route table: S3 traffic stays off the NAT gateway | `aws_vpc_endpoint.s3` |
| Security group `pbp-<env>-data` | Ingress from the VPC only: PostgreSQL 5432, MSK 9096 (SASL/SCRAM over TLS), Redis 6379 | `aws_security_group.data` |

| Subnets (one per zone, three zones) | CIDRs | Holds |
| --- | --- | --- |
| `public-0..2` | 10.30.0.0/24, 10.30.1.0/24, 10.30.2.0/24 | Load balancers and the NAT gateway; tagged `kubernetes.io/role/elb` |
| `workers-0..2` | 10.30.16.0/20, 10.30.32.0/20, 10.30.48.0/20 | EKS nodes and pods; tagged `kubernetes.io/role/internal-elb` |
| `data-0..2` | 10.30.200.0/24, 10.30.201.0/24, 10.30.202.0/24 | RDS, MSK and ElastiCache |

Write down: the Elastic IP (manifest: vendors' allow-lists).

## 3. Kubernetes (EKS)

| Create | Setting | Terraform |
| --- | --- | --- |
| KMS key `alias/pbp-<env>` | Rotation on; encrypts EKS secrets, RDS, MSK, ElastiCache, S3 and Secrets Manager | `aws_kms_key.master` |
| Cluster role | `AmazonEKSClusterPolicy` | `aws_iam_role.cluster` |
| Cluster `pbp-<env>` | Kubernetes 1.30; in the worker subnets; **private API endpoint only**; access entries (API authentication mode); Kubernetes secrets encrypted with the KMS key | `aws_eks_cluster.eks` |
| Node role | `AmazonEKSWorkerNodePolicy`, `AmazonEKS_CNI_Policy`, `AmazonEC2ContainerRegistryReadOnly` (nodes pull from ECR without a pull secret) | `aws_iam_role.node` |
| Managed node group `pbp-<env>-workers` | m6i.xlarge (4 vCPU, 16 GB); **3 nodes** for dev and demo, **6** for production (`node_count`); maximum twice that; across the three worker subnets | `aws_eks_node_group.workers` |
| Add-ons | `vpc-cni`, `coredns`, `kube-proxy`, `eks-pod-identity-agent` | `aws_eks_addon.addon` |
| Cluster autoscaler or Karpenter (production) | Scales the node group between 6 and 12 | — |

The API endpoint is private: reach it from inside the VPC (a bastion through Systems Manager Session Manager, or a VPN), give your role an EKS access entry, then `aws eks update-kubeconfig --name pbp-<env> --region eu-west-2`. The deploy needs this kubeconfig and a runner that can reach the endpoint.

## 4. PostgreSQL 16 (Amazon RDS)

| Create | Setting | Terraform |
| --- | --- | --- |
| Subnet group `pbp-<env>-pg` | The three data subnets | `aws_db_subnet_group.pg` |
| Parameter group `pbp-<env>-pg16` | Family `postgres16`; `rds.logical_replication = 1` (needs a reboot, so set it at creation); `max_slot_wal_keep_size = 10240` | `aws_db_parameter_group.pg` |
| Instance `pbp-<env>-pg` | PostgreSQL 16; `db.m6g.large`; 100 GB gp3, growing to 500 GB; encrypted with the KMS key; database `pbp`, admin user `pbpadmin`; **Multi-AZ**; data security group; backups kept 14 days; deletion protection on; Performance Insights on | `aws_db_instance.pg` |
| Read replica `pbp-<env>-pg-replica` | Same class; the EHI responder reads it for card lookups when the cache is down, as on OCI | `aws_db_instance.pg_replica` |

Then, connected as the admin user:

1. Give `pbpadmin` replication: `GRANT rds_replication TO pbpadmin;` (on RDS this role replaces `ALTER ROLE … WITH REPLICATION`). The services connect as `pbpadmin` and create their own schemas with Flyway on start-up.
2. Create the databases `temporal` and `temporal_visibility` for Temporal.
3. Check: `SHOW wal_level;` must answer `logical`.
4. Size `max_connections` for pods × 10 (the services' connection pool) plus Temporal.

Deletion protection and the final snapshot mean `terraform destroy` stops at the database until protection is switched off.

Write down: the primary endpoint (manifest: `config.DB_HOST`).

## 5. Kafka (Amazon MSK)

| Create | Setting | Terraform |
| --- | --- | --- |
| Configuration `pbp-<env>-kafka` | Kafka 3.7; auto-create topics off; replication factor 3; minimum in-sync replicas 2; retention 7 days | `aws_msk_configuration.kafka` |
| Cluster `pbp-<env>-kafka` | 3 brokers, `kafka.m7g.large`, 100 GB each, in the data subnets; **SASL/SCRAM only**; TLS between clients and brokers and inside the cluster; encrypted with the KMS key; broker logs to CloudWatch `/pbp/<env>/msk` | `aws_msk_cluster.kafka` |
| SCRAM secret `AmazonMSK_pbp-<env>` | User `pbp` and `kafka_password`; MSK requires the `AmazonMSK_` prefix and a customer-managed key | `aws_secretsmanager_secret.msk_scram` and the association |

**Topics**: MSK has no Terraform resource for topics. The chart's `kafka-topics` Job creates the topics in the common runbook section 4 when `kafkaTopics.enabled: true` (already set in `values-aws.yaml`), with replication factor 3.

The services log in with SASL_SSL and SCRAM-SHA-512. Terraform writes the JAAS line to the secret `pbp-<env>-spring-kafka-properties-sasl-jaas-config`:

`org.apache.kafka.common.security.scram.ScramLoginModule required username="pbp" password="<kafka_password>";`

Write down: the SASL/SCRAM bootstrap brokers, port 9096 (manifest: `config.KAFKA_BOOTSTRAP`).

## 6. Cache (Amazon ElastiCache for Redis)

| Create | Setting | Terraform |
| --- | --- | --- |
| Subnet group `pbp-<env>-cache` | The three data subnets | `aws_elasticache_subnet_group.cache` |
| Replication group `pbp-<env>-cache` | Redis 7.1; `cache.m7g.large`; 3 nodes with automatic failover across zones; encryption at rest (KMS) and in transit; port 6379; data security group | `aws_elasticache_replication_group.cache` |

Write down: the primary endpoint (manifest: `config.REDIS_HOST`; `values-aws.yaml` already turns TLS on).

## 7. Secrets and identities

| Create | Setting | Terraform |
| --- | --- | --- |
| Secrets `pbp-<env>-db-user`, `-db-password`, `-webhook-secret`, `-spring-kafka-properties-sasl-jaas-config` | In Secrets Manager, encrypted with the KMS key; as in the common runbook section 5 | `aws_secretsmanager_secret.service` |
| Role `pbp-<env>-external-secrets` | Trusted by EKS Pod Identity; may read secrets named `pbp-<env>-*` and decrypt with the KMS key | `aws_iam_role.external_secrets` |
| Pod Identity association | Service account `external-secrets` in namespace `external-secrets` → the role above | `aws_eks_pod_identity_association.external_secrets` |

In the cluster, install the External Secrets Operator in the namespace `external-secrets` (service account `external-secrets`) and create a `ClusterSecretStore` named `aws-secrets-manager` for Secrets Manager in `eu-west-2`. No keys are stored in the cluster.

## 8. S3 and the registry

| Create | Setting | Terraform |
| --- | --- | --- |
| Buckets `pbp-<env>-documents-<account>`, `-settlement-`, `-recon-`, `-archive-` | Versioned; encrypted with the KMS key; all public access blocked | `aws_s3_bucket.bucket` and the three resources after it |
| Bucket `pbp-<env>-archive-locked-<account>` | Object Lock in **compliance mode, 7 years** by default; versioned; public access blocked. In compliance mode nobody, not even the account root, can delete an object before its retention ends | `aws_s3_bucket.archive_locked` and its configuration |
| ECR repositories `pbp/<image>` | One per image (bff, orchestrator, mapping-service, ehi-responder, essence-adapter, thredd-adapter, atfinity-adapter, credit-adapter, recon-service, vendor-simulator, web); scan on push; encrypted with the KMS key | `aws_ecr_repository.repo` |

The deploy workflow pushes to OCIR only today (common runbook, section 10): push with `aws ecr get-login-password | docker login …` and `docker push`, then run `helm upgrade` by hand with `-f values-aws.yaml`.

## 9. Public entry, card entry and WAF

| Create | Setting | Terraform or chart |
| --- | --- | --- |
| WAF web ACL `pbp-<env>-web` | Regional; rate limit 15,000 requests per 5 minutes per IP (50 a second, as on OCI); AWS managed common rule set | `aws_wafv2_web_acl.web` |
| AWS Load Balancer Controller | Cluster add-on (Helm), with an IAM role through Pod Identity | — (cluster add-on) |
| TLS certificates in ACM | One for the web host, one for the EHI host, in `eu-west-2` | — (by hand; DNS validation) |
| Web entry: internet-facing ALB | Created by the controller from the chart's `web` Ingress: HTTPS 443 only, TLS 1.3 policy, the ACM web certificate and the WAF web ACL (`aws.webHost`, `aws.webCertificateArn`, `aws.wafAclArn`) | Chart, `templates/ingress-aws.yaml` |
| Card entry: internet-facing NLB `ehi-public` | **Only with a card module.** Created by the controller from the chart's Service: TLS 443 with the ACM EHI certificate; only Thredd's ranges may connect (`ehiLoadBalancer.sourceRanges`) | Chart, `templates/ehi-loadbalancer.yaml` |

Keep `ehiLoadBalancer.sourceRanges` in `values-aws.yaml` equal to the Terraform variable `thredd_ehi_source_cidrs`. Without a card module the chart creates no NLB, and nothing is needed for Thredd.

**After the first deploy**: point the web host name at the ALB's DNS name and the EHI host name at the NLB's (Route 53 alias records), and give Thredd the EHI host name.

## 10. Identity, observability and the manifest

**Identity (Amazon Cognito or the bank's directory)**

Two options, as in `values-aws.yaml`:

1. **Keycloak in the cluster, brokering to Cognito or the bank's directory** (default): the web app and the BFF are unchanged. Groups `staff` and `compliance`. Manifest: Keycloak's JWKS URL (`config.KEYCLOAK_JWKS`).
2. **Cognito tokens directly**: a user pool with groups `staff` and `compliance` and an app client for the web app; manifest: the user pool's JWKS URL and `config.PBP_AUTH_ROLES_CLAIM: cognito:groups`.

**Observability**

- In-cluster Grafana stack (common runbook, section 6); `observability.enabled: true`.
- Terraform also creates the CloudWatch log groups `/pbp/<env>/app` (90 days) and `/pbp/<env>/msk` (30 days). The OpenTelemetry Collector can add an X-Ray exporter beside Tempo.

**Fill the manifest** (common runbook, section 5, and `values-aws.yaml`), then deploy (section 7) and check (section 8).

| Terraform output | Manifest key |
| --- | --- |
| `nat_egress_ip` | Vendors' allow-lists |
| `postgres_endpoint` | `config.DB_HOST` |
| `postgres_replica_endpoint` | EHI responder's fallback read path (if configured separately) |
| `kafka_bootstrap` | `config.KAFKA_BOOTSTRAP` |
| `cache_endpoint` | `config.REDIS_HOST` |
| `registry` | `image.registry` |
| `waf_acl_arn` | `aws.wafAclArn` |
| `kms_key_arn` | Reference: every encrypted resource uses it |
| `cluster_name` | `aws eks update-kubeconfig` |

## 11. What still needs a person or a first apply

| Item | Why |
| --- | --- |
| Terraform state bucket and lock table | Must exist before `terraform init` |
| EKS access entry for your role, and a path into the VPC | The API endpoint is private (section 3) |
| `rds_replication` for `pbpadmin`; `temporal` and `temporal_visibility` databases | Section 4 |
| ACM certificates and DNS validation | Section 9 |
| AWS Load Balancer Controller and External Secrets Operator | Cluster add-ons (sections 7 and 9) |
| Identity: Keycloak realm or Cognito user pool | Section 10 |
| Images pushed to ECR and `helm upgrade` by hand | The deploy workflow pushes to OCIR only (common runbook, section 10) |
| DNS alias records for the ALB and NLB | After the first deploy (section 9) |
