# Platform services, one-for-one with the OCI cell:
#   OKE → GKE · OCI PostgreSQL → Cloud SQL for PostgreSQL 16 · OCI Streaming → Managed Service for Apache Kafka
#   OCI Cache → Memorystore for Redis · OCI Vault → Cloud KMS + Secret Manager · Object Storage → Cloud Storage
#   OCIR → Artifact Registry · API Gateway + WAF → global HTTPS load balancer (GKE Ingress) + Cloud Armor
#   OCI Logging/APM → Cloud Logging + Cloud Trace (traces through the OpenTelemetry Collector, unchanged)

locals {
  has_cards      = contains(var.modules, "cards") || contains(var.modules, "creditcards")
  nodes_per_zone = var.node_count / 3
  ehi_host       = lower(var.ehi_hostname)
}

# ---------- Cloud KMS: GKE application-layer secrets and the Cloud Storage buckets ----------
# Cloud SQL, Managed Kafka, Memorystore and Secret Manager use Google-managed encryption here; customer keys
# for them need each service's agent granted on the key first (production hardening).

resource "google_kms_key_ring" "main" {
  name       = "${local.name}-${local.suffix}"
  location   = var.region
  depends_on = [google_project_service.api]
}

resource "google_kms_crypto_key" "master" {
  name            = "master"
  key_ring        = google_kms_key_ring.main.id
  rotation_period = "7776000s" # 90 days
}

resource "google_kms_crypto_key_iam_member" "gke" {
  crypto_key_id = google_kms_crypto_key.master.id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:service-${data.google_project.current.number}@container-engine-robot.iam.gserviceaccount.com"
}

data "google_storage_project_service_account" "gcs" {
  depends_on = [google_project_service.api]
}

resource "google_kms_crypto_key_iam_member" "gcs" {
  crypto_key_id = google_kms_crypto_key.master.id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:${data.google_storage_project_service_account.gcs.email_address}"
}

# ---------- GKE ----------

resource "google_service_account" "nodes" {
  account_id   = "${local.name}-gke-nodes"
  display_name = "${local.name} GKE nodes"
}

# Logs, metrics and image pulls from Artifact Registry (no image pull secret).
resource "google_project_iam_member" "nodes" {
  for_each = toset([
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
    "roles/monitoring.viewer",
    "roles/stackdriver.resourceMetadata.writer",
    "roles/artifactregistry.reader",
  ])
  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.nodes.email}"
}

resource "google_container_cluster" "gke" {
  name                     = local.name
  location                 = var.region
  network                  = google_compute_network.main.id
  subnetwork               = google_compute_subnetwork.workers.id
  networking_mode          = "VPC_NATIVE"
  datapath_provider        = "ADVANCED_DATAPATH" # Dataplane V2: Kubernetes network policy built in
  remove_default_node_pool = true
  initial_node_count       = 1
  deletion_protection      = false

  release_channel {
    channel = "REGULAR"
  }

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  # Nodes and the control plane have no public addresses; reach the API from inside the VPC.
  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = true
    master_ipv4_cidr_block  = var.master_cidr
  }

  master_authorized_networks_config {
    cidr_blocks {
      cidr_block   = var.vpc_cidr
      display_name = "vpc"
    }
  }

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  database_encryption {
    state    = "ENCRYPTED"
    key_name = google_kms_crypto_key.master.id
  }

  addons_config {
    http_load_balancing {
      disabled = false # GKE Ingress: the global HTTPS load balancer for the web app and the EHI host
    }
  }

  logging_config {
    enable_components = ["SYSTEM_COMPONENTS", "WORKLOADS"]
  }

  depends_on = [google_kms_crypto_key_iam_member.gke]
}

resource "google_container_node_pool" "workers" {
  name       = "workers"
  cluster    = google_container_cluster.gke.id
  location   = var.region
  node_count = local.nodes_per_zone # per zone: three zones in the region

  autoscaling {
    min_node_count = local.nodes_per_zone
    max_node_count = local.nodes_per_zone * 2
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }

  node_config {
    machine_type    = var.node_machine_type
    disk_type       = "pd-balanced"
    disk_size_gb    = 100
    service_account = google_service_account.nodes.email
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]

    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }
  }
}

# ---------- Cloud SQL for PostgreSQL 16 (one instance, a schema per service) ----------

resource "google_sql_database_instance" "pg" {
  name                = "${local.name}-pg-${local.suffix}"
  database_version    = "POSTGRES_16"
  region              = var.region
  deletion_protection = true

  settings {
    edition               = "ENTERPRISE"
    tier                  = var.postgres_tier
    availability_type     = "REGIONAL" # synchronous standby in a second zone
    disk_type             = "PD_SSD"
    disk_size             = 100
    disk_autoresize       = true
    disk_autoresize_limit = 500

    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.main.id
      ssl_mode        = "ENCRYPTED_ONLY"
    }

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      transaction_log_retention_days = 7
      backup_retention_settings {
        retained_backups = 14
      }
    }

    # Debezium reads the outbox tables through logical replication (LLD section 9).
    database_flags {
      name  = "cloudsql.logical_decoding"
      value = "on"
    }

    # Pods × 10 (the services' connection pool) plus Temporal.
    database_flags {
      name  = "max_connections"
      value = "500"
    }

    insights_config {
      query_insights_enabled = true
    }
  }

  depends_on = [google_service_networking_connection.psa]
}

# Read replica for the EHI responder's fallback card lookups, as on OCI and AWS.
resource "google_sql_database_instance" "pg_replica" {
  name                 = "${local.name}-pg-replica-${local.suffix}"
  master_instance_name = google_sql_database_instance.pg.name
  database_version     = "POSTGRES_16"
  region               = var.region
  deletion_protection  = false

  settings {
    edition           = "ENTERPRISE"
    tier              = var.postgres_tier
    availability_type = "ZONAL"
    disk_type         = "PD_SSD"
    disk_autoresize   = true

    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.main.id
      ssl_mode        = "ENCRYPTED_ONLY"
    }
  }
}

resource "google_sql_database" "db" {
  for_each = toset(["pbp", "temporal", "temporal_visibility"])
  name     = each.value
  instance = google_sql_database_instance.pg.name
}

# The services connect as pbpadmin and create their own schemas with Flyway. It still needs
# ALTER USER pbpadmin WITH REPLICATION; for the outbox relay (runbook, section 4).
resource "google_sql_user" "pbpadmin" {
  name     = "pbpadmin"
  instance = google_sql_database_instance.pg.name
  password = var.postgres_password
}

# ---------- Managed Service for Apache Kafka ----------
# The services and Kafka Connect speak Kafka to bootstrap.<cluster>.<region>.managedkafka.<project>.cloud.goog:9092
# (SASL_SSL, PLAIN: a service account's e-mail as username and its key, base64-encoded, as password).

resource "google_managed_kafka_cluster" "kafka" {
  provider   = google-beta
  cluster_id = "${local.name}-kafka-${local.suffix}"
  location   = var.region

  capacity_config {
    vcpu_count   = 3
    memory_bytes = 12884901888 # 12 GiB
  }

  gcp_config {
    access_config {
      network_configs {
        subnet = google_compute_subnetwork.workers.id
      }
    }
  }

  rebalance_config {
    mode = "AUTO_REBALANCE_ON_SCALE_UP"
  }

  depends_on = [google_project_service.api]
}

resource "google_managed_kafka_topic" "topic" {
  provider = google-beta
  for_each = merge(
    var.topics,
    { for name, t in var.topics : "${name}.DLT" => { partitions = 3, retention = 7 } },
  )
  topic_id           = each.key
  cluster            = google_managed_kafka_cluster.kafka.cluster_id
  location           = var.region
  partition_count    = each.value.partitions
  replication_factor = 3
  configs = {
    "retention.ms" = tostring(each.value.retention * 86400000)
  }
}

resource "google_managed_kafka_topic" "compacted" {
  provider           = google-beta
  for_each           = var.compacted_topics
  topic_id           = each.key
  cluster            = google_managed_kafka_cluster.kafka.cluster_id
  location           = var.region
  partition_count    = each.value
  replication_factor = 3
  configs = {
    "cleanup.policy" = "compact"
  }
}

resource "google_service_account" "kafka" {
  account_id   = "${local.name}-kafka"
  display_name = "${local.name} Kafka client (services and Kafka Connect)"
}

resource "google_project_iam_member" "kafka_client" {
  project = var.project_id
  role    = "roles/managedkafka.client"
  member  = "serviceAccount:${google_service_account.kafka.email}"
}

# Fails where the organisation policy iam.disableServiceAccountKeyCreation is enforced (runbook, section 5).
resource "google_service_account_key" "kafka" {
  service_account_id = google_service_account.kafka.name
}

# ---------- Memorystore for Redis: the EHI card cache ----------
# TLS on port 6378 with a Google-managed CA that Java does not trust by default: the CA goes to Secret
# Manager and the services trust it through a Spring SSL bundle (values-gcp.yaml).

resource "google_redis_instance" "cache" {
  name                    = "${local.name}-cache"
  region                  = var.region
  tier                    = "STANDARD_HA"
  memory_size_gb          = 5
  redis_version           = "REDIS_7_2"
  authorized_network      = google_compute_network.main.id
  connect_mode            = "PRIVATE_SERVICE_ACCESS"
  transit_encryption_mode = "SERVER_AUTHENTICATION"
  replica_count           = 2
  read_replicas_mode      = "READ_REPLICAS_ENABLED"

  depends_on = [google_service_networking_connection.psa]
}

# ---------- Secret Manager: the keys the Helm chart's ExternalSecret expects ----------
# Names match deploy/helm/pbp/templates/external-secret.yaml: pbp-<env>-<key in lower-kebab-case>.
# Kept in the region (user-managed replication) for UK data residency.

locals {
  secret_names = [
    "db-user",
    "db-password",
    "webhook-secret",
    "spring-kafka-properties-sasl-jaas-config",
    "spring-ssl-bundle-pem-redis-truststore-certificate",
  ]
  service_secrets = {
    "db-user"                                            = "pbpadmin"
    "db-password"                                        = var.postgres_password
    "webhook-secret"                                     = var.webhook_secret
    "spring-kafka-properties-sasl-jaas-config"           = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${google_service_account.kafka.email}\" password=\"${google_service_account_key.kafka.private_key}\";"
    "spring-ssl-bundle-pem-redis-truststore-certificate" = google_redis_instance.cache.server_ca_certs[0].cert
  }
}

resource "google_secret_manager_secret" "service" {
  for_each  = toset(local.secret_names)
  secret_id = "${local.name}-${each.key}"

  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }

  depends_on = [google_project_service.api]
}

resource "google_secret_manager_secret_version" "service" {
  for_each    = toset(local.secret_names)
  secret      = google_secret_manager_secret.service[each.key].id
  secret_data = local.service_secrets[each.key]
}

# External Secrets Operator reads them through GKE Workload Identity (no keys in the cluster).
resource "google_service_account" "external_secrets" {
  account_id   = "${local.name}-ext-secrets"
  display_name = "${local.name} External Secrets Operator"
}

resource "google_secret_manager_secret_iam_member" "external_secrets" {
  for_each  = toset(local.secret_names)
  secret_id = google_secret_manager_secret.service[each.key].id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.external_secrets.email}"
}

resource "google_service_account_iam_member" "external_secrets" {
  service_account_id = google_service_account.external_secrets.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[external-secrets/external-secrets]"
  depends_on         = [google_container_cluster.gke] # the workload identity pool exists once the cluster does
}

# ---------- Cloud Storage: documents, settlement files, recon reports, archive ----------

resource "google_storage_bucket" "bucket" {
  for_each                    = toset(["documents", "settlement", "recon", "archive"])
  name                        = "${local.name}-${each.key}-${local.suffix}"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  versioning {
    enabled = true
  }

  encryption {
    default_kms_key_name = google_kms_crypto_key.master.id
  }

  depends_on = [google_kms_crypto_key_iam_member.gcs]
}

# Archived partitions and recon reports: kept 7 years, cannot be deleted early. Lock the retention policy
# once tested (is_locked = true cannot be undone). Cloud Storage does not allow versioning with a retention policy.
resource "google_storage_bucket" "archive_locked" {
  name                        = "${local.name}-archive-locked-${local.suffix}"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  retention_policy {
    retention_period = 220752000 # 2,555 days
    is_locked        = false
  }

  encryption {
    default_kms_key_name = google_kms_crypto_key.master.id
  }

  depends_on = [google_kms_crypto_key_iam_member.gcs]
}

# ---------- Artifact Registry: one Docker repository, an image per service ----------

resource "google_artifact_registry_repository" "pbp" {
  location      = var.region
  repository_id = "pbp"
  format        = "DOCKER"
  description   = "Sutherland Banking Platform images"
  depends_on    = [google_project_service.api]
}

# ---------- Public entry: global HTTPS load balancer (GKE Ingress) with Cloud Armor ----------
# One load balancer serves the web host and, with a card module, Thredd's EHI host, as the Azure cell's
# Application Gateway does. The chart's GKE Ingress, BackendConfig and FrontendConfig use these by name.

resource "google_compute_global_address" "web" {
  name       = "${local.name}-web"
  depends_on = [google_project_service.api]
}

resource "google_compute_managed_ssl_certificate" "web" {
  name = "${local.name}-web"
  managed {
    domains = local.has_cards ? [var.web_hostname, var.ehi_hostname] : [var.web_hostname]
  }
}

resource "google_compute_ssl_policy" "tls" {
  name            = "${local.name}-tls"
  profile         = "MODERN"
  min_tls_version = "TLS_1_2"
}

resource "google_compute_security_policy" "edge" {
  name = "${local.name}-edge"
  type = "CLOUD_ARMOR"

  # Thredd's ranges are allowed outright: they reach the EHI host and are not rate-limited.
  dynamic "rule" {
    for_each = local.has_cards ? chunklist(var.thredd_ehi_source_cidrs, 10) : []
    content {
      action      = "allow"
      priority    = 800 + rule.key
      description = "Thredd EHI ranges"
      match {
        versioned_expr = "SRC_IPS_V1"
        config {
          src_ip_ranges = rule.value
        }
      }
    }
  }

  # Anyone else asking for the EHI host is refused; with no Thredd ranges set, everyone is.
  dynamic "rule" {
    for_each = local.has_cards ? [1] : []
    content {
      action      = "deny(403)"
      priority    = 900
      description = "EHI host: Thredd only"
      match {
        expr {
          expression = "request.headers['host'].lower() == '${local.ehi_host}'"
        }
      }
    }
  }

  rule {
    action      = "deny(403)"
    priority    = 950
    description = "OWASP: SQL injection and cross-site scripting"
    match {
      expr {
        expression = "evaluatePreconfiguredWaf('sqli-v33-stable') || evaluatePreconfiguredWaf('xss-v33-stable')"
      }
    }
  }

  # 50 requests a second per client IP, as the OCI API Gateway's rate limit.
  rule {
    action      = "throttle"
    priority    = 1000
    description = "Rate limit per client IP"
    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }
    rate_limit_options {
      conform_action = "allow"
      exceed_action  = "deny(429)"
      enforce_on_key = "IP"
      rate_limit_threshold {
        count        = 3000
        interval_sec = 60
      }
    }
  }

  rule {
    action      = "allow"
    priority    = 2147483647
    description = "Default"
    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }
  }
}
