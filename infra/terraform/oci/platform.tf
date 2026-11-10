# Platform services (HLD section 8): OKE, PostgreSQL, Streaming, Cache, Vault, Object Storage,
# container registry, API Gateway, WAF, logging and APM.

data "oci_identity_availability_domains" "ads" {
  compartment_id = var.compartment_id
}

data "oci_objectstorage_namespace" "ns" {
  compartment_id = var.compartment_id
}

# ---------- OKE ----------

resource "oci_containerengine_cluster" "oke" {
  compartment_id     = var.compartment_id
  name               = local.name
  kubernetes_version = var.kubernetes_version
  vcn_id             = oci_core_vcn.main.id
  type               = "ENHANCED_CLUSTER"

  endpoint_config {
    subnet_id            = oci_core_subnet.oke_api.id
    is_public_ip_enabled = false
  }

  options {
    service_lb_subnet_ids = [oci_core_subnet.public.id]
  }
}

resource "oci_containerengine_node_pool" "workers" {
  compartment_id     = var.compartment_id
  cluster_id         = oci_containerengine_cluster.oke.id
  name               = "${local.name}-workers"
  kubernetes_version = var.kubernetes_version
  node_shape         = var.node_shape

  node_shape_config {
    ocpus         = var.node_ocpus
    memory_in_gbs = var.node_memory_gbs
  }

  node_source_details {
    source_type = "IMAGE"
    image_id    = var.node_image_id
  }

  node_config_details {
    size = var.node_count
    dynamic "placement_configs" {
      for_each = data.oci_identity_availability_domains.ads.availability_domains
      content {
        availability_domain = placement_configs.value.name
        subnet_id           = oci_core_subnet.workers.id
      }
    }
  }
}

# ---------- OCI Database with PostgreSQL (one instance, a schema per service) ----------

# Logical replication for the Debezium outbox relay (LLD section 9): wal_level logical and room for replication
# slots. To confirm on the first apply that OCI accepts these overrides for wal_level (pglogical is supported).
resource "oci_psql_configuration" "pg" {
  compartment_id              = var.compartment_id
  display_name                = "${local.name}-pg-logical"
  db_version                  = "16"
  shape                       = "PostgreSQL.VM.Standard.E4.Flex"
  instance_ocpu_count         = 2
  instance_memory_size_in_gbs = 32
  db_configuration_overrides {
    items {
      config_key             = "wal_level"
      overriden_config_value = "logical"
    }
    items {
      config_key             = "max_replication_slots"
      overriden_config_value = "10"
    }
    items {
      config_key             = "max_wal_senders"
      overriden_config_value = "10"
    }
  }
}

resource "oci_psql_db_system" "pg" {
  compartment_id              = var.compartment_id
  display_name                = "${local.name}-pg"
  db_version                  = "16"
  shape                       = "PostgreSQL.VM.Standard.E4.Flex"
  instance_ocpu_count         = 2
  instance_memory_size_in_gbs = 32
  instance_count              = 2 # primary + read replica (EHI fallback reads)
  config_id                   = oci_psql_configuration.pg.id

  network_details {
    subnet_id = oci_core_subnet.data.id
  }

  storage_details {
    system_type           = "OCI_OPTIMIZED_STORAGE"
    is_regionally_durable = true
  }

  credentials {
    username = "pbpadmin"
    password_details {
      password_type = "PLAIN_TEXT"
      password      = var.postgres_password
    }
  }
}

# ---------- OCI Streaming with Kafka compatibility ----------

resource "oci_streaming_stream_pool" "kafka" {
  compartment_id = var.compartment_id
  name           = "${local.name}-kafka"

  kafka_settings {
    auto_create_topics_enable = false
    log_retention_hours       = 168
    num_partitions            = 6
  }

  private_endpoint_settings {
    subnet_id = oci_core_subnet.data.id
  }
}

locals {
  streams = merge(
    var.topics,
    { for name, t in var.topics : "${name}.DLT" => { partitions = 3, retention = 168 } },
  )
}

# Kafka Connect's own storage (configs, offsets, statuses) for the Debezium outbox relay. Its topics are
# "<this OCID>-config", "-offset" and "-status"; the Helm chart takes the OCID as connect.ociConnectHarnessId.
resource "oci_streaming_connect_harness" "connect" {
  compartment_id = var.compartment_id
  name           = "${local.name}-connect"
}

resource "oci_streaming_stream" "topic" {
  for_each           = local.streams
  name               = each.key
  partitions         = each.value.partitions
  retention_in_hours = each.value.retention
  stream_pool_id     = oci_streaming_stream_pool.kafka.id
}

# ---------- OCI Cache (Redis-compatible) for the EHI card cache ----------

resource "oci_redis_redis_cluster" "cache" {
  compartment_id     = var.compartment_id
  display_name       = "${local.name}-cache"
  node_count         = 3
  node_memory_in_gbs = 2
  software_version   = "V7_0_5"
  subnet_id          = oci_core_subnet.data.id
}

# ---------- Vault: secrets for services and vendor credentials ----------

resource "oci_kms_vault" "vault" {
  compartment_id = var.compartment_id
  display_name   = "${local.name}-vault"
  vault_type     = "DEFAULT"
}

resource "oci_kms_key" "master" {
  compartment_id      = var.compartment_id
  display_name        = "${local.name}-master"
  management_endpoint = oci_kms_vault.vault.management_endpoint
  key_shape {
    algorithm = "AES"
    length    = 32
  }
}

resource "oci_vault_secret" "postgres" {
  compartment_id = var.compartment_id
  vault_id       = oci_kms_vault.vault.id
  key_id         = oci_kms_key.master.id
  secret_name    = "${local.name}-postgres-password"
  secret_content {
    content_type = "BASE64"
    content      = base64encode(var.postgres_password)
  }
}

# The service secrets the Helm chart's ExternalSecret expects: pbp-<env>-<key in lower-kebab-case>
# (deploy/helm/pbp/templates/external-secret.yaml). Same names on every cloud.
locals {
  service_secrets = {
    "db-user"        = "pbpadmin"
    "db-password"    = var.postgres_password
    "webhook-secret" = var.webhook_secret
    # Streaming takes SASL PLAIN with <tenancy>/<user>/<stream pool OCID> and an auth token.
    "spring-kafka-properties-sasl-jaas-config" = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${var.streaming_user}/${oci_streaming_stream_pool.kafka.id}\" password=\"${var.streaming_auth_token}\";"
  }
}

resource "oci_vault_secret" "service" {
  for_each       = local.service_secrets
  compartment_id = var.compartment_id
  vault_id       = oci_kms_vault.vault.id
  key_id         = oci_kms_key.master.id
  secret_name    = "${local.name}-${each.key}"
  secret_content {
    content_type = "BASE64"
    content      = base64encode(each.value)
  }
}

# ---------- Access: External Secrets reads Vault; the Streaming user's group uses the stream pool ----------
# Worker nodes (and so the External Secrets Operator's pods) read secrets through instance principals.
resource "oci_identity_dynamic_group" "workers" {
  compartment_id = var.tenancy_ocid
  name           = "${local.name}-workers"
  description    = "OKE worker nodes of ${local.name}"
  matching_rule  = "ALL {instance.compartment.id = '${var.compartment_id}'}"
}

resource "oci_identity_policy" "access" {
  compartment_id = var.compartment_id
  name           = "${local.name}-access"
  description    = "Vault secrets for External Secrets; Streaming and Kafka Connect for the services' user"
  statements = [
    "Allow dynamic-group ${oci_identity_dynamic_group.workers.name} to read secret-family in compartment id ${var.compartment_id}",
    "Allow group ${var.streaming_group} to use stream-family in compartment id ${var.compartment_id}",
  ]
}

# ---------- Object Storage: documents, settlement files, recon reports, archive ----------

resource "oci_objectstorage_bucket" "bucket" {
  for_each       = toset(["documents", "settlement", "recon", "archive"])
  compartment_id = var.compartment_id
  namespace      = data.oci_objectstorage_namespace.ns.namespace
  name           = "${local.name}-${each.key}"
  access_type    = "NoPublicAccess"
  versioning     = "Enabled"
  kms_key_id     = oci_kms_key.master.id
}

# Archived partitions and recon reports are kept for 7 years and cannot be deleted early.
resource "oci_objectstorage_bucket" "archive_locked" {
  compartment_id = var.compartment_id
  namespace      = data.oci_objectstorage_namespace.ns.namespace
  name           = "${local.name}-archive-locked"
  access_type    = "NoPublicAccess"
  kms_key_id     = oci_kms_key.master.id

  retention_rules {
    display_name = "seven-years"
    duration {
      time_amount = 7
      time_unit   = "YEARS"
    }
  }
}

# ---------- Container registry (OCIR) ----------

resource "oci_artifacts_container_repository" "repo" {
  for_each       = toset(var.services)
  compartment_id = var.compartment_id
  display_name   = "pbp/${each.key}"
  is_public      = false
}

# ---------- API Gateway: the only public entry for the web app and BFF ----------

resource "oci_apigateway_gateway" "api" {
  compartment_id = var.compartment_id
  display_name   = "${local.name}-api"
  endpoint_type  = "PUBLIC"
  subnet_id      = oci_core_subnet.public.id
}

resource "oci_apigateway_deployment" "bff" {
  compartment_id = var.compartment_id
  gateway_id     = oci_apigateway_gateway.api.id
  display_name   = "${local.name}-bff"
  path_prefix    = "/"

  specification {
    request_policies {
      rate_limiting {
        rate_in_requests_per_second = 50
        rate_key                    = "CLIENT_IP"
      }
    }
    routes {
      path    = "/v1/{path*}"
      methods = ["ANY"]
      backend {
        type = "HTTP_BACKEND"
        url  = "${var.bff_backend_url}/v1/$${request.path[path]}"
      }
    }
  }
}

# ---------- WAF on the EHI load balancer (attached once the LB exists) ----------
# Only for a bank with a card module: without cards and creditcards there is no EHI load balancer.
locals {
  has_cards = contains(var.modules, "cards") || contains(var.modules, "creditcards")
}

resource "oci_waf_web_app_firewall_policy" "ehi" {
  count          = local.has_cards ? 1 : 0
  compartment_id = var.compartment_id
  display_name   = "${local.name}-ehi-waf"

  actions {
    name = "allow"
    type = "ALLOW"
  }
  actions {
    name = "block"
    type = "RETURN_HTTP_RESPONSE"
    code = 403
  }

  request_access_control {
    default_action_name = "block"
    dynamic "rules" {
      for_each = length(var.thredd_ehi_source_cidrs) > 0 ? [1] : []
      content {
        name               = "thredd-only"
        type               = "ACCESS_CONTROL"
        action_name        = "allow"
        condition_language = "JMESPATH"
        condition          = "i_contains(${jsonencode(var.thredd_ehi_source_cidrs)}, connection.source.address)"
      }
    }
  }
}

resource "oci_waf_web_app_firewall" "ehi" {
  count                      = local.has_cards && var.ehi_load_balancer_id != "" ? 1 : 0
  compartment_id             = var.compartment_id
  display_name               = "${local.name}-ehi-waf"
  backend_type               = "LOAD_BALANCER"
  load_balancer_id           = var.ehi_load_balancer_id
  web_app_firewall_policy_id = oci_waf_web_app_firewall_policy.ehi[0].id
}

# ---------- Observability ----------

resource "oci_logging_log_group" "app" {
  compartment_id = var.compartment_id
  display_name   = "${local.name}-app"
}

resource "oci_apm_apm_domain" "apm" {
  compartment_id = var.compartment_id
  display_name   = "${local.name}-apm"
  is_free_tier   = var.env != "prod"
}
