# Platform services, one-for-one with the OCI cell:
#   OKE → AKS · OCI PostgreSQL → PostgreSQL Flexible Server 16 · OCI Streaming → Event Hubs (Kafka endpoint)
#   OCI Cache → Azure Cache for Redis · OCI Vault → Key Vault · Object Storage → Blob Storage · OCIR → ACR
#   API Gateway + WAF → Application Gateway WAF_v2 · OCI Logging/APM → Log Analytics + Application Insights

data "azurerm_client_config" "current" {}

# ---------- Observability ----------
# Prometheus, Grafana, Tempo and Loki run in-cluster exactly as on OCI (LLD section 15). Container logs also
# go to Log Analytics; the OpenTelemetry Collector can add an Azure Monitor exporter beside Tempo.

resource "azurerm_log_analytics_workspace" "logs" {
  name                = "${local.name}-logs"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  sku                 = "PerGB2018"
  retention_in_days   = 90
}

resource "azurerm_application_insights" "apm" {
  name                = "${local.name}-apm"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  workspace_id        = azurerm_log_analytics_workspace.logs.id
  application_type    = "java"
}

# ---------- AKS ----------

resource "azurerm_kubernetes_cluster" "aks" {
  name                      = local.name
  location                  = azurerm_resource_group.rg.location
  resource_group_name       = azurerm_resource_group.rg.name
  dns_prefix                = local.name
  kubernetes_version        = var.kubernetes_version
  private_cluster_enabled   = true
  oidc_issuer_enabled       = true
  workload_identity_enabled = true

  default_node_pool {
    name                        = "workers"
    vm_size                     = var.node_vm_size
    node_count                  = var.node_count
    vnet_subnet_id              = azurerm_subnet.aks.id
    zones                       = ["1", "2", "3"]
    temporary_name_for_rotation = "workerstmp"
  }

  identity {
    type = "SystemAssigned"
  }

  network_profile {
    network_plugin    = "azure"
    network_policy    = "azure"
    load_balancer_sku = "standard"
    outbound_type     = "userAssignedNATGateway"
  }

  key_vault_secrets_provider {
    secret_rotation_enabled = true
  }

  oms_agent {
    log_analytics_workspace_id = azurerm_log_analytics_workspace.logs.id
  }

  depends_on = [azurerm_subnet_nat_gateway_association.aks]
}

# The cluster places the web and EHI internal load balancers on fixed IPs in its subnet.
resource "azurerm_role_assignment" "aks_network" {
  scope                = azurerm_subnet.aks.id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_kubernetes_cluster.aks.identity[0].principal_id
}

# ---------- Container registry (ACR): nodes pull with their managed identity, no pull secret ----------

resource "azurerm_container_registry" "acr" {
  name                    = "pbp${var.env}${local.suffix}"
  location                = azurerm_resource_group.rg.location
  resource_group_name     = azurerm_resource_group.rg.name
  sku                     = "Premium"
  admin_enabled           = false
  zone_redundancy_enabled = true
}

resource "azurerm_role_assignment" "aks_acr" {
  scope                = azurerm_container_registry.acr.id
  role_definition_name = "AcrPull"
  principal_id         = azurerm_kubernetes_cluster.aks.kubelet_identity[0].object_id
}

# ---------- PostgreSQL Flexible Server 16 (one server, a schema per service) ----------

resource "azurerm_postgresql_flexible_server" "pg" {
  name                          = "${local.name}-pg-${local.suffix}"
  location                      = azurerm_resource_group.rg.location
  resource_group_name           = azurerm_resource_group.rg.name
  version                       = "16"
  delegated_subnet_id           = azurerm_subnet.postgres.id
  private_dns_zone_id           = azurerm_private_dns_zone.postgres.id
  public_network_access_enabled = false
  administrator_login           = "pbpadmin"
  administrator_password        = var.postgres_password
  sku_name                      = "GP_Standard_D2ds_v5"
  storage_mb                    = 131072
  backup_retention_days         = 14
  zone                          = "1"

  high_availability {
    mode                      = "ZoneRedundant"
    standby_availability_zone = "2"
  }

  depends_on = [azurerm_private_dns_zone_virtual_network_link.postgres]
}

# Debezium reads the outbox tables through logical replication (LLD section 9); the WAL cap stops a
# stalled replication slot from filling the disk. The service user also needs: ALTER ROLE ... WITH REPLICATION.
resource "azurerm_postgresql_flexible_server_configuration" "pg" {
  for_each  = { wal_level = "logical", max_slot_wal_keep_size = "10240" }
  name      = each.key
  server_id = azurerm_postgresql_flexible_server.pg.id
  value     = each.value
}

resource "azurerm_postgresql_flexible_server_database" "pbp" {
  name      = "pbp"
  server_id = azurerm_postgresql_flexible_server.pg.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

# ---------- Event Hubs Premium with the Kafka endpoint ----------
# The services and Kafka Connect speak Kafka to <namespace>.servicebus.windows.net:9093 (SASL_SSL, PLAIN,
# username $ConnectionString). Premium supports compacted hubs, which Kafka Connect needs for its own topics.

resource "azurerm_eventhub_namespace" "kafka" {
  name                          = "${local.name}-kafka-${local.suffix}"
  location                      = azurerm_resource_group.rg.location
  resource_group_name           = azurerm_resource_group.rg.name
  sku                           = "Premium"
  capacity                      = 1
  minimum_tls_version           = "1.2"
  local_authentication_enabled  = true
  public_network_access_enabled = true

  network_rulesets {
    default_action                 = "Deny"
    trusted_service_access_enabled = true
    virtual_network_rule {
      subnet_id = azurerm_subnet.aks.id
    }
  }
}

resource "azurerm_eventhub" "topic" {
  for_each = merge(
    var.topics,
    { for name, t in var.topics : "${name}.DLT" => { partitions = 3, retention = 30 } },
  )
  name              = each.key
  namespace_id      = azurerm_eventhub_namespace.kafka.id
  partition_count   = each.value.partitions
  message_retention = each.value.retention
}

resource "azurerm_eventhub" "compacted" {
  for_each        = var.compacted_topics
  name            = each.key
  namespace_id    = azurerm_eventhub_namespace.kafka.id
  partition_count = each.value

  retention_description {
    cleanup_policy                    = "Compact"
    tombstone_retention_time_in_hours = 168
  }
}

resource "azurerm_eventhub_namespace_authorization_rule" "services" {
  name                = "pbp-services"
  namespace_name      = azurerm_eventhub_namespace.kafka.name
  resource_group_name = azurerm_resource_group.rg.name
  listen              = true
  send                = true
  manage              = true
}

# ---------- Azure Cache for Redis (Premium, in its own subnet) for the EHI card cache ----------
# Azure Managed Redis is Microsoft's successor; the services only need the Redis protocol over TLS (port 6380).

resource "azurerm_redis_cache" "cache" {
  name                          = "${local.name}-cache-${local.suffix}"
  location                      = azurerm_resource_group.rg.location
  resource_group_name           = azurerm_resource_group.rg.name
  capacity                      = 1
  family                        = "P"
  sku_name                      = "Premium"
  non_ssl_port_enabled          = false
  minimum_tls_version           = "1.2"
  subnet_id                     = azurerm_subnet.redis.id
  public_network_access_enabled = false
  zones                         = ["1", "2", "3"]
  replicas_per_primary          = 2
}

# ---------- Key Vault: service secrets and the TLS certificates for the Application Gateway ----------

resource "azurerm_key_vault" "kv" {
  name                       = "${local.name}-kv-${local.suffix}"
  location                   = azurerm_resource_group.rg.location
  resource_group_name        = azurerm_resource_group.rg.name
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "premium"
  enable_rbac_authorization  = true
  purge_protection_enabled   = true
  soft_delete_retention_days = 90

  network_acls {
    default_action             = "Deny"
    bypass                     = "AzureServices"
    virtual_network_subnet_ids = [azurerm_subnet.aks.id]
    ip_rules                   = var.admin_cidrs
  }
}

# Whoever runs Terraform writes the service secrets below.
resource "azurerm_role_assignment" "deployer_secrets" {
  scope                = azurerm_key_vault.kv.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

# Names match deploy/helm/pbp/templates/external-secret.yaml: pbp-<env>-<key in lower-kebab-case>.
locals {
  service_secrets = {
    "db-user"                                  = "pbpadmin"
    "db-password"                              = var.postgres_password
    "webhook-secret"                           = var.webhook_secret
    "spring-kafka-properties-sasl-jaas-config" = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$ConnectionString\" password=\"${azurerm_eventhub_namespace_authorization_rule.services.primary_connection_string}\";"
  }
}

resource "azurerm_key_vault_secret" "service" {
  for_each     = local.service_secrets
  name         = "${local.name}-${each.key}"
  value        = each.value
  key_vault_id = azurerm_key_vault.kv.id
  depends_on   = [azurerm_role_assignment.deployer_secrets]
}

# External Secrets Operator reads them through AKS workload identity (no keys in the cluster).
resource "azurerm_user_assigned_identity" "external_secrets" {
  name                = "${local.name}-external-secrets"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
}

resource "azurerm_federated_identity_credential" "external_secrets" {
  name                = "external-secrets"
  resource_group_name = azurerm_resource_group.rg.name
  parent_id           = azurerm_user_assigned_identity.external_secrets.id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = azurerm_kubernetes_cluster.aks.oidc_issuer_url
  subject             = "system:serviceaccount:external-secrets:external-secrets"
}

resource "azurerm_role_assignment" "external_secrets" {
  scope                = azurerm_key_vault.kv.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.external_secrets.principal_id
}

# ---------- Blob Storage: documents, settlement files, recon reports, archive ----------

resource "azurerm_storage_account" "data" {
  name                            = "pbp${var.env}${local.suffix}"
  location                        = azurerm_resource_group.rg.location
  resource_group_name             = azurerm_resource_group.rg.name
  account_tier                    = "Standard"
  account_replication_type        = "ZRS"
  min_tls_version                 = "TLS1_2"
  allow_nested_items_to_be_public = false

  blob_properties {
    versioning_enabled = true
  }

  network_rules {
    default_action             = "Deny"
    bypass                     = ["AzureServices"]
    virtual_network_subnet_ids = [azurerm_subnet.aks.id]
    ip_rules                   = var.admin_cidrs
  }
}

resource "azurerm_storage_container" "container" {
  for_each              = toset(["documents", "settlement", "recon", "archive", "archive-locked"])
  name                  = each.key
  storage_account_id    = azurerm_storage_account.data.id
  container_access_type = "private"
}

# Archived partitions and recon reports: kept 7 years, cannot be deleted early. Lock the policy once tested.
resource "azurerm_storage_container_immutability_policy" "archive_locked" {
  storage_container_resource_manager_id = azurerm_storage_container.container["archive-locked"].id
  immutability_period_in_days           = 2555
  protected_append_writes_enabled       = true
}

# ---------- Application Gateway WAF_v2: the only public entry (web app and Thredd EHI) ----------
# Created once the TLS certificates are in the Key Vault (web_certificate_secret_id set), like the OCI
# cell's WAF attaches once the EHI load balancer exists.

resource "azurerm_web_application_firewall_policy" "waf" {
  name                = "${local.name}-waf"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name

  policy_settings {
    enabled = true
    mode    = "Prevention"
  }

  managed_rules {
    managed_rule_set {
      type    = "OWASP"
      version = "3.2"
    }
  }

  # 50 requests a second per client IP, as the OCI API Gateway's rate limit. Not on the EHI host: Thredd sends
  # up to 500 card authorisations a second from a few addresses, and the next rule admits only Thredd there.
  custom_rules {
    name                 = "ratelimit"
    priority             = 1
    rule_type            = "RateLimitRule"
    rate_limit_duration  = "OneMin"
    rate_limit_threshold = 3000
    group_rate_limit_by  = "ClientAddr"
    action               = "Block"
    match_conditions {
      match_variables {
        variable_name = "RemoteAddr"
      }
      operator     = "IPMatch"
      match_values = ["0.0.0.0/0"]
    }
    match_conditions {
      match_variables {
        variable_name = "RequestHeaders"
        selector      = "Host"
      }
      operator           = "Equal"
      negation_condition = true
      match_values       = [var.ehi_hostname]
      transforms         = ["Lowercase"]
    }
  }

  # The EHI host name answers Thredd's ranges only; with no ranges set, nobody.
  custom_rules {
    name      = "ehithreddonly"
    priority  = 2
    rule_type = "MatchRule"
    action    = "Block"
    match_conditions {
      match_variables {
        variable_name = "RequestHeaders"
        selector      = "Host"
      }
      operator     = "Equal"
      match_values = [var.ehi_hostname]
      transforms   = ["Lowercase"]
    }
    dynamic "match_conditions" {
      for_each = length(var.thredd_ehi_source_cidrs) > 0 ? [1] : []
      content {
        match_variables {
          variable_name = "RemoteAddr"
        }
        operator           = "IPMatch"
        negation_condition = true
        match_values       = var.thredd_ehi_source_cidrs
      }
    }
  }
}

resource "azurerm_user_assigned_identity" "appgw" {
  name                = "${local.name}-appgw"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
}

resource "azurerm_role_assignment" "appgw_certs" {
  scope                = azurerm_key_vault.kv.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.appgw.principal_id
}

resource "azurerm_public_ip" "appgw" {
  name                = "${local.name}-appgw"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  allocation_method   = "Static"
  sku                 = "Standard"
  zones               = ["1", "2", "3"]
}

resource "azurerm_application_gateway" "appgw" {
  count               = var.web_certificate_secret_id == "" ? 0 : 1
  name                = "${local.name}-appgw"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  zones               = ["1", "2", "3"]
  firewall_policy_id  = azurerm_web_application_firewall_policy.waf.id

  sku {
    name = "WAF_v2"
    tier = "WAF_v2"
  }

  autoscale_configuration {
    min_capacity = 1
    max_capacity = 4
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.appgw.id]
  }

  ssl_policy {
    policy_type = "Predefined"
    policy_name = "AppGwSslPolicy20220101"
  }

  gateway_ip_configuration {
    name      = "gateway"
    subnet_id = azurerm_subnet.appgw.id
  }

  frontend_ip_configuration {
    name                 = "public"
    public_ip_address_id = azurerm_public_ip.appgw.id
  }

  frontend_port {
    name = "https"
    port = 443
  }

  ssl_certificate {
    name                = "web"
    key_vault_secret_id = var.web_certificate_secret_id
  }

  ssl_certificate {
    name                = "ehi"
    key_vault_secret_id = var.ehi_certificate_secret_id
  }

  backend_address_pool {
    name         = "web"
    ip_addresses = [var.web_private_ip]
  }

  backend_address_pool {
    name         = "ehi"
    ip_addresses = [var.ehi_private_ip]
  }

  backend_http_settings {
    name                  = "web"
    port                  = 80
    protocol              = "Http"
    cookie_based_affinity = "Disabled"
    request_timeout       = 60
  }

  # Thredd's own time limit applies; the EHI responder answers within its budget (LLD section 5).
  backend_http_settings {
    name                  = "ehi"
    port                  = 80
    protocol              = "Http"
    cookie_based_affinity = "Disabled"
    request_timeout       = 5
  }

  http_listener {
    name                           = "web"
    frontend_ip_configuration_name = "public"
    frontend_port_name             = "https"
    protocol                       = "Https"
    ssl_certificate_name           = "web"
    host_name                      = var.web_hostname
  }

  http_listener {
    name                           = "ehi"
    frontend_ip_configuration_name = "public"
    frontend_port_name             = "https"
    protocol                       = "Https"
    ssl_certificate_name           = "ehi"
    host_name                      = var.ehi_hostname
  }

  request_routing_rule {
    name                       = "web"
    priority                   = 100
    rule_type                  = "Basic"
    http_listener_name         = "web"
    backend_address_pool_name  = "web"
    backend_http_settings_name = "web"
  }

  request_routing_rule {
    name                       = "ehi"
    priority                   = 110
    rule_type                  = "Basic"
    http_listener_name         = "ehi"
    backend_address_pool_name  = "ehi"
    backend_http_settings_name = "ehi"
  }

  depends_on = [azurerm_role_assignment.appgw_certs]
}
