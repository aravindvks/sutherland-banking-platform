# Sutherland Banking Platform – Provisioning Runbook: Google Cloud (GCP)

**Status: new, and not yet run in a real project.** Written with `infra/terraform/gcp`, `deploy/helm/pbp/values-gcp.yaml` and the chart's GKE Ingress (27 November 2026). The Terraform has been written but not yet applied; CI checks its syntax with `terraform validate`. Read the common runbook first: it has the decisions, the environment manifest, the deploy and the checks. GCP is the fourth cloud, after OCI, Azure and AWS. Console menus change; each step says what to create and how to set it, with the Terraform resource that does the same.

## 1. Before you start

- **Project and region**: London, `europe-west2`, for UK customers. One project per environment, ideally inside a Google Cloud landing zone (organisation, folders, shared policies).
- **Access**: Owner on the project, or Editor plus Project IAM Admin and Security Admin (Terraform grants roles and creates the Cloud Armor policy).
- **Organisation policies to check**:
  - `iam.disableServiceAccountKeyCreation`: Managed Kafka's PLAIN login uses a service account key (section 5). If the policy is enforced, ask for an exception for this project, or switch the services to OAUTHBEARER (a code change).
  - `compute.vmExternalIpAccess` and `constraints/compute.requireOsLogin`: compatible as written (no node has an external address).
- **Quotas** in `europe-west2`: 12 vCPU of N2 for dev and demo (3 × n2-standard-4); 48 vCPU for production (6 nodes, autoscaling to 12: common runbook, section 3a). One regional static external address (NAT) and one global (web entry). Managed Kafka: 3 vCPU.
- **Tools on the operator's machine**: Google Cloud CLI (`gcloud`, with the `gke-gcloud-auth-plugin` component), `kubectl`, `helm`, Terraform 1.6+ (for the Terraform path).
- **Decisions** from the common runbook, section 2: environment name, modules, network range (default `10.50.0.0/16`, pods `10.56.0.0/14`).

**Terraform path**: in `infra/terraform/gcp`, copy `terraform.tfvars.example` and `backend.hcl.example`. Fill in `project_id`, `env`, `modules` (the same list as Helm's), `thredd_ehi_source_cidrs`, `web_hostname` and `ehi_hostname`; pass `postgres_password` and `webhook_secret` as `TF_VAR_*` environment variables. Then `terraform init -backend-config=backend.hcl` and `terraform apply`. The state lives in a Cloud Storage bucket that must exist before `init` (`backend.hcl`). Terraform switches on the APIs it needs itself.

## 2. Network (VPC)

| Create | Setting | Terraform |
| --- | --- | --- |
| APIs | Compute, Kubernetes Engine, Cloud SQL Admin, Service Networking, Memorystore for Redis, Managed Kafka, Secret Manager, Artifact Registry, Cloud KMS, IAM, Logging, Monitoring, Trace | `google_project_service.api` |
| VPC `pbp-<env>` | Custom mode (no automatic subnets), regional routing | `google_compute_network.main` |
| Subnet `pbp-<env>-workers` | 10.50.0.0/20; secondary ranges `pods` (10.56.0.0/14) and `services` (10.50.16.0/20); Private Google Access on (Secret Manager, Artifact Registry and Cloud Storage without the NAT); flow logs | `google_compute_subnetwork.workers` |
| Static external address `pbp-<env>-egress` | Regional. **This is the address the vendors allow-list** | `google_compute_address.nat` |
| Cloud Router and Cloud NAT `pbp-<env>-nat` | NAT IP allocation **manual**, using only the address above; all subnets | `google_compute_router.router`, `google_compute_router_nat.nat` |
| Private services access | Range 10.50.32.0/20 peered with Google's service network, for Cloud SQL and Memorystore | `google_compute_global_address.psa`, `google_service_networking_connection.psa` |
| Firewall `pbp-<env>-internal` | Ingress from the VPC and pod ranges only | `google_compute_firewall.internal` |
| Firewall `pbp-<env>-lb-health-checks` | Ingress from Google's load balancer and health-check ranges (35.191.0.0/16, 130.211.0.0/22): the web entry reaches the pods directly | `google_compute_firewall.health_checks` |

Write down: the egress address (manifest: vendors' allow-lists).

## 3. Kubernetes (GKE)

| Create | Setting | Terraform |
| --- | --- | --- |
| Key ring `pbp-<env>-<suffix>` and key `master` | Cloud KMS, in the region; rotated every 90 days. Encrypts Kubernetes secrets and the Cloud Storage buckets | `google_kms_key_ring.main`, `google_kms_crypto_key.master` |
| Node service account `pbp-<env>-gke-nodes` | Log writer, metric writer, monitoring viewer, resource metadata writer, **Artifact Registry reader** (no image pull secret) | `google_service_account.nodes`, `google_project_iam_member.nodes` |
| Cluster `pbp-<env>` | **Regional** (three zones); Regular release channel; VPC-native; Dataplane V2 (network policy built in); **private nodes and private control-plane endpoint** (172.16.0.32/28), reachable from the VPC only; Workload Identity; Kubernetes secrets encrypted with the KMS key; HTTP load balancing on (GKE Ingress) | `google_container_cluster.gke` |
| Node pool `workers` | n2-standard-4 (4 vCPU, 16 GB); `node_count` is the **total** and must be a multiple of 3: 3 for dev and demo (one per zone), 6 for production (two per zone); autoscaling to twice that; auto-repair and auto-upgrade; Shielded nodes with Secure Boot; GKE metadata server (Workload Identity) | `google_container_node_pool.workers` |

The control plane has no public endpoint: reach it from inside the VPC (a small VM, Identity-Aware Proxy TCP forwarding, or a VPN), then `gcloud container clusters get-credentials pbp-<env> --region europe-west2 --internal-ip`. The deploy needs this kubeconfig and a runner that can reach the endpoint.

On private clusters the control plane reaches nodes only on ports 443 and 10250. If the External Secrets Operator's admission webhook times out, add a firewall rule from 172.16.0.32/28 to the nodes on its webhook port.

## 4. PostgreSQL 16 (Cloud SQL)

| Create | Setting | Terraform |
| --- | --- | --- |
| Instance `pbp-<env>-pg-<suffix>` | PostgreSQL 16, **Enterprise** edition; `db-custom-2-8192` (2 vCPU, 8 GB) for dev and demo; **regional high availability** (synchronous standby in another zone); 100 GB SSD, growing to 500 GB; **private IP only** on the VPC; encrypted connections only; backups kept 14, point-in-time recovery 7 days; deletion protection on; Query Insights on | `google_sql_database_instance.pg` |
| Database flags | `cloudsql.logical_decoding = on` (for the outbox relay); `max_connections = 500` (pods × 10 plus Temporal) | Same resource |
| Read replica `pbp-<env>-pg-replica-<suffix>` | Same tier, zonal, private IP; the EHI responder reads it for card lookups when the cache is down, as on OCI and AWS | `google_sql_database_instance.pg_replica` |
| Databases `pbp`, `temporal`, `temporal_visibility` | Created by Terraform here (by hand on the other clouds) | `google_sql_database.db` |
| User `pbpadmin` | Built-in user with the password from `postgres_password` | `google_sql_user.pbpadmin` |

Then, connected as `postgres` or `pbpadmin` (from inside the VPC):

1. Give `pbpadmin` replication: `ALTER USER pbpadmin WITH REPLICATION;`. The services connect as `pbpadmin` and create their own schemas with Flyway on start-up.
2. Check: `SHOW wal_level;` must answer `logical`.

Because Cloud SQL accepts encrypted connections only, the JDBC driver's default (`sslmode=prefer`) negotiates TLS; nothing changes in the services.

Write down: the primary's private IP (manifest: `config.DB_HOST`).

## 5. Kafka (Google Cloud Managed Service for Apache Kafka)

| Create | Setting | Terraform |
| --- | --- | --- |
| Cluster `pbp-<env>-kafka-<suffix>` | 3 vCPU, 12 GiB; endpoints in the workers subnet; rebalances automatically when scaled up | `google_managed_kafka_cluster.kafka` |
| Topics | The 8 topics in the common runbook section 4 with their partitions; replication factor 3; retention 7 days for commands and 30 for events; a `.DLT` topic for each (3 partitions, 7 days) | `google_managed_kafka_topic.topic` |
| Compacted topics | `mapping-events` (6), `pbp-connect-configs` (1), `pbp-connect-offsets` (25), `pbp-connect-statuses` (5) | `google_managed_kafka_topic.compacted` |
| Service account `pbp-<env>-kafka` | **Managed Kafka Client** role; a key for the PLAIN login | `google_service_account.kafka`, `google_project_iam_member.kafka_client`, `google_service_account_key.kafka` |

The services and Kafka Connect log in with SASL_SSL and PLAIN: the service account's e-mail address as username and its key (base64-encoded JSON) as password. Terraform writes that JAAS line to the secret `pbp-<env>-spring-kafka-properties-sasl-jaas-config`:

`org.apache.kafka.common.security.plain.PlainLoginModule required username="pbp-<env>-kafka@<project>.iam.gserviceaccount.com" password="<base64 key>";`

The key is a long-lived credential: rotate it by tainting `google_service_account_key.kafka` and applying again (the External Secrets Operator picks up the new secret within its refresh interval of 1 hour).

Write down: the bootstrap address `bootstrap.<cluster>.europe-west2.managedkafka.<project>.cloud.goog:9092` (manifest: `config.KAFKA_BOOTSTRAP`).

## 6. Cache (Memorystore for Redis)

| Create | Setting | Terraform |
| --- | --- | --- |
| Instance `pbp-<env>-cache` | Redis 7.2, Standard tier (high availability), 5 GB, 2 read replicas; private services access; **TLS** (server authentication) on port 6378 | `google_redis_instance.cache` |

**Memorystore's certificate is signed by a Google-managed CA that Java does not trust by default.** Terraform puts the CA certificate in the secret `pbp-<env>-spring-ssl-bundle-pem-redis-truststore-certificate`. `values-gcp.yaml` brings it into the services as a Spring SSL bundle (`externalSecrets.extraKeys` and `SPRING_DATA_REDIS_SSL_BUNDLE: redis`), so the services trust it without a code change. Confirm on the first deploy that the EHI responder and the mapping service connect (section 11).

Write down: the host and port (manifest: `config.REDIS_HOST`; `values-gcp.yaml` already sets port 6378 and TLS on).

## 7. Secret Manager and identities

| Create | Setting | Terraform |
| --- | --- | --- |
| Secrets `pbp-<env>-db-user`, `-db-password`, `-webhook-secret`, `-spring-kafka-properties-sasl-jaas-config`, `-spring-ssl-bundle-pem-redis-truststore-certificate` | Replicated in `europe-west2` only (UK data residency); as in the common runbook section 5, plus the Redis CA | `google_secret_manager_secret.service`, `google_secret_manager_secret_version.service` |
| Service account `pbp-<env>-ext-secrets` | **Secret Accessor** on those secrets only; **Workload Identity User** for the Kubernetes service account `external-secrets/external-secrets` | `google_service_account.external_secrets` and the two IAM resources after it |

In the cluster, install the External Secrets Operator in the namespace `external-secrets`, annotate its service account with `iam.gke.io/gcp-service-account: <external_secrets_service_account>`, and create a `ClusterSecretStore` named `gcp-secret-manager` for the project. No keys are stored in the cluster.

## 8. Cloud Storage and the registry

| Create | Setting | Terraform |
| --- | --- | --- |
| Buckets `pbp-<env>-documents-<suffix>`, `-settlement-`, `-recon-`, `-archive-` | In the region; uniform access; public access prevented; versioned; encrypted with the KMS key | `google_storage_bucket.bucket` |
| Bucket `pbp-<env>-archive-locked-<suffix>` | Retention policy **7 years** (2,555 days), not yet locked; encrypted with the KMS key. Cloud Storage does not allow versioning together with a retention policy. Test it, then lock it (`is_locked = true`): once locked, objects cannot be deleted before the period ends, and the policy cannot be removed | `google_storage_bucket.archive_locked` |
| Artifact Registry repository `pbp` | Docker, in the region; an image per service (bff, orchestrator, mapping-service, ehi-responder, essence-adapter, thredd-adapter, atfinity-adapter, credit-adapter, recon-service, vendor-simulator, web) | `google_artifact_registry_repository.pbp` |

The deploy workflow pushes to OCIR only today (common runbook, section 10): push with `gcloud auth configure-docker europe-west2-docker.pkg.dev` and `docker push`, then run `helm upgrade` by hand with `-f values-gcp.yaml`.

## 9. Public entry, card entry and Cloud Armor

On GCP one global external HTTPS load balancer serves both entries, as the Application Gateway does on Azure: the web app and, with a card module, Thredd's EHI calls. GKE creates the load balancer from the chart's Ingress; Terraform creates what it uses.

| Create | Setting | Terraform or chart |
| --- | --- | --- |
| Global static address `pbp-<env>-web` | Point the web host name, and with cards the EHI host name, at it | `google_compute_global_address.web` |
| Google-managed certificate `pbp-<env>-web` | The web host name, plus the EHI host name with a card module. Issued once DNS points at the address above (can take up to an hour) | `google_compute_managed_ssl_certificate.web` |
| TLS policy `pbp-<env>-tls` | Modern profile, TLS 1.2 minimum | `google_compute_ssl_policy.tls` |
| Cloud Armor policy `pbp-<env>-edge` | In order: allow Thredd's ranges; refuse the EHI host to anyone else (403; everyone, if no Thredd ranges are set); refuse SQL injection and cross-site scripting (OWASP preconfigured rules); rate limit 3,000 requests a minute per client IP (50 a second, as on OCI); allow the rest. Thredd's ranges are allowed before the rate limit, so card authorisations are never throttled | `google_compute_security_policy.edge` |
| Ingress `web` | Class `gce`, HTTPS only, the static address, the certificate and a FrontendConfig with the TLS policy; host rules for the web app and, with a card module, the EHI responder | Chart, `templates/ingress-gcp.yaml` |
| BackendConfigs `web` and `ehi-responder` | Cloud Armor policy; health checks (`/` and `/actuator/health/readiness`); timeout 60 seconds for web, **5 seconds for EHI** | Chart, `templates/ingress-gcp.yaml` |
| Services `web` and `ehi-responder` | Network endpoint groups (`cloud.google.com/neg`): the load balancer reaches the pods directly, with no extra load balancer in the cluster | Chart, `templates/services.yaml` |

Keep `gcp.webHost`, `gcp.ehiHost` and the four names in `values-gcp.yaml` equal to the Terraform variables and outputs. Without a card module, Terraform leaves the EHI host out of the certificate and Cloud Armor, and the chart leaves it out of the Ingress.

**After the first deploy**: point the DNS names at `web_ip`, wait for the certificate to become Active (`gcloud compute ssl-certificates describe pbp-<env>-web`), and give Thredd the EHI host name.

## 10. Identity, observability and the manifest

**Identity**: Keycloak in the cluster, brokering to Google Cloud Identity, Workspace or the bank's own directory, keeps the web app and the BFF unchanged. Groups `staff` and `compliance`. Manifest: Keycloak's JWKS URL (`config.KEYCLOAK_JWKS`).

**Observability**

- In-cluster Grafana stack (common runbook, section 6); `observability.enabled: true`.
- GKE sends system and workload logs to Cloud Logging. The OpenTelemetry Collector can add a Google Cloud exporter (Cloud Trace) beside Tempo, using Workload Identity with the Cloud Trace Agent role.

**Fill the manifest** (common runbook, section 5, and `values-gcp.yaml`), then deploy (section 7) and check (section 8).

| Terraform output | Manifest key |
| --- | --- |
| `nat_egress_ip` | Vendors' allow-lists |
| `postgres_endpoint` | `config.DB_HOST` |
| `postgres_replica_endpoint` | EHI responder's fallback read path (if configured separately) |
| `kafka_bootstrap` | `config.KAFKA_BOOTSTRAP` |
| `cache_endpoint`, `cache_port` | `config.REDIS_HOST`, `config.SPRING_DATA_REDIS_PORT` |
| `registry` | `image.registry` |
| `external_secrets_service_account` | Annotation on the External Secrets service account |
| `web_ip` | DNS for the web and EHI host names |
| `web_ip_name`, `certificate_name`, `security_policy_name`, `ssl_policy_name` | `gcp.staticIpName`, `gcp.certificateName`, `gcp.securityPolicy`, `gcp.sslPolicy` |
| `cluster_name` | `gcloud container clusters get-credentials` |

## 11. What still needs a person or a first apply

| Item | Why |
| --- | --- |
| Terraform state bucket | Must exist before `terraform init` |
| Service account key allowed by organisation policy | Managed Kafka's PLAIN login (section 5) |
| A path into the VPC | The control plane is private (section 3) |
| REPLICATION for `pbpadmin` | Section 4 |
| External Secrets service account annotation and `ClusterSecretStore` | Section 7 |
| DNS records and the managed certificate becoming Active | Section 9 |
| Images pushed to Artifact Registry and `helm upgrade` by hand | The deploy workflow pushes to OCIR only (common runbook, section 10) |
| Retention policy locked | Lock it after testing (section 8) |
| **First apply** confirms: Managed Kafka with Kafka Connect's compacted topics; the services trusting Memorystore's CA through the Spring SSL bundle; the Cloud Armor host rule on the EHI host | New on GCP; none of these has run yet |

## 12. Production hardening (after the first environment works)

- Customer-managed keys for Cloud SQL, Managed Kafka, Memorystore and Secret Manager (each service's agent granted on the KMS key first); this module uses them for Kubernetes secrets and Cloud Storage only.
- Cloud SQL tier sized to the production load tests.
- Disaster recovery: **GCP has one UK region (London)**, so there is no in-country standby region as on OCI, Azure and AWS. Zones inside London cover a zone failure; a second region (for example `europe-west1`, Belgium) is possible only if the bank's data-residency rules allow it. Settle this with the bank before choosing GCP for a UK production bank (phase D).
- VPC Service Controls around the project's data services.
- Managed Kafka capacity sized to the production load tests.
