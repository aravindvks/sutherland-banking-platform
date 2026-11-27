# Sutherland Banking Platform – Provisioning Runbook: Azure

**Status: not yet run in a real subscription.** Written from `infra/terraform/azure`, `deploy/helm/pbp/values-azure.yaml` and the Helm chart (27 November 2026). Read the common runbook first: it has the decisions, the environment manifest, the deploy and the checks. Azure is the second cloud after OCI. Portal menus change; each step says what to create and how to set it, with the Terraform resource that does the same.

## 1. Before you start

- **Subscription and region**: UK South (London), `uksouth`, for UK customers; UK West is the in-country standby (phase D).
- **Resource group**: one per environment, `pbp-<env>-rg`. Terraform creates it; everything below goes in it.
- **Access**: Owner, or Contributor plus User Access Administrator, on the subscription. Terraform creates role assignments (AKS on its subnet, AcrPull, Key Vault roles), which Contributor alone cannot do.
- **Resource providers registered**: `Microsoft.ContainerService`, `Microsoft.DBforPostgreSQL`, `Microsoft.EventHub`, `Microsoft.Cache`, `Microsoft.KeyVault`, `Microsoft.Storage`, `Microsoft.ContainerRegistry`, `Microsoft.Network`, `Microsoft.OperationalInsights`, `Microsoft.Insights`, `Microsoft.ManagedIdentity`.
- **Quotas**: 12 vCPU of the Dv5 family in UK South for dev and demo (3 × Standard_D4s_v5); 48 vCPU for production (6 nodes, autoscaling to 12: common runbook, section 3a). Event Hubs Premium, 1 processing unit.
- **Tools on the operator's machine**: Azure CLI, `kubectl`, `helm`, Terraform 1.6+ (for the Terraform path).
- **Decisions** from the common runbook, section 2: environment name, modules, network range (default `10.40.0.0/16`).

**Terraform path**: in `infra/terraform/azure`, copy `terraform.tfvars.example` and `backend.hcl.example`. Fill in `subscription_id`, `env`, `admin_cidrs` (the address you run Terraform from, so it can write Key Vault secrets), `thredd_ehi_source_cidrs`, `web_hostname` and `ehi_hostname`. Pass `postgres_password` and `webhook_secret` as `TF_VAR_*` environment variables. Then `terraform init -backend-config=backend.hcl` and `terraform apply`. The state lives in an Azure Storage container that must exist before `init` (`backend.hcl`).

**Two applies.** The first creates everything except the Application Gateway. Upload the TLS certificates to the new Key Vault, set `web_certificate_secret_id` and `ehi_certificate_secret_id`, and apply again (section 9).

## 2. Network (VNet)

| Create | Setting | Terraform |
| --- | --- | --- |
| Resource group `pbp-<env>-rg` | Region `uksouth` | `azurerm_resource_group.rg` |
| VNet `pbp-<env>` | Address space `10.40.0.0/16` | `azurerm_virtual_network.main` |
| Static public IP `pbp-<env>-egress` | Standard SKU. **This is the address the vendors allow-list** | `azurerm_public_ip.nat` |
| NAT gateway `pbp-<env>-nat` | Standard; uses the IP above; attached to the `aks` subnet | `azurerm_nat_gateway.nat`, `azurerm_subnet_nat_gateway_association.aks` |
| Private DNS zone `pbp-<env>.private.postgres.database.azure.com` | Linked to the VNet, for PostgreSQL | `azurerm_private_dns_zone.postgres` |

| Subnet | CIDR | Holds |
| --- | --- | --- |
| `aks` | 10.40.0.0/20 | Worker nodes, pods and the two internal load balancers (web 10.40.15.10, EHI 10.40.15.11). Service endpoints for Event Hubs, Key Vault and Storage |
| `appgw` | 10.40.32.0/24 | Application Gateway, the only public entry |
| `postgres` | 10.40.33.0/24 | PostgreSQL Flexible Server (delegated to `Microsoft.DBforPostgreSQL/flexibleServers`) |
| `redis` | 10.40.34.0/24 | Azure Cache for Redis |

Write down: the egress IP (manifest: vendors' allow-lists).

## 3. Kubernetes (AKS)

| Create | Setting | Terraform |
| --- | --- | --- |
| Cluster `pbp-<env>` | Kubernetes 1.30; **private cluster** (no public API endpoint); OIDC issuer and workload identity on; Azure CNI and Azure network policy; outbound through the NAT gateway; Key Vault secrets provider on; container logs to Log Analytics | `azurerm_kubernetes_cluster.aks` |
| Node pool `workers` | Standard_D4s_v5 (4 vCPU, 16 GB); **3 nodes** for dev and demo, **6** for production (`node_count`); zones 1, 2 and 3; in the `aks` subnet | Same resource, `default_node_pool` |
| Cluster autoscaler (production) | Enable on the node pool, 6 to 12 nodes | — |
| Role assignment | The cluster's identity is **Network Contributor** on the `aks` subnet, so it can place the internal load balancers on fixed IPs | `azurerm_role_assignment.aks_network` |

Reach the private API endpoint from inside the VNet (a jump host, Azure Bastion or a VPN), or use `az aks command invoke`. Then `az aks get-credentials -g pbp-<env>-rg -n pbp-<env>`. The deploy needs this kubeconfig and a runner that can reach the endpoint.

## 4. PostgreSQL 16 (Azure Database for PostgreSQL Flexible Server)

| Create | Setting | Terraform |
| --- | --- | --- |
| Server `pbp-<env>-pg-<suffix>` | Version 16; General Purpose `GP_Standard_D2ds_v5`; 128 GB storage; backups kept 14 days; delegated subnet `postgres`, private DNS zone above, **no public access**; admin user `pbpadmin`; **zone-redundant high availability** (zone 1, standby in zone 2) | `azurerm_postgresql_flexible_server.pg` |
| Server parameters | `wal_level = logical` (for the outbox relay); `max_slot_wal_keep_size = 10240` (a stalled replication slot cannot fill the disk) | `azurerm_postgresql_flexible_server_configuration.pg` |
| Database `pbp` | UTF8 | `azurerm_postgresql_flexible_server_database.pbp` |

Changing `wal_level` restarts the server; Terraform sets it at creation, so this matters only when changing an existing server.

Then, connected as the admin user:

1. Give `pbpadmin` REPLICATION (`ALTER ROLE pbpadmin WITH REPLICATION;`) for the outbox relay. The services connect as `pbpadmin` and create their own schemas with Flyway on start-up.
2. Create the databases `temporal` and `temporal_visibility` for Temporal.
3. Check: `SHOW wal_level;` must answer `logical`.
4. Size `max_connections` for pods × 10 (the services' connection pool) plus Temporal.

Unlike the OCI cell there is no separate read replica: zone-redundant HA keeps a synchronous standby that takes over on a zone failure. The EHI responder's read path uses the primary when the cache is down.

Write down: the server's FQDN (manifest: `config.DB_HOST`).

## 5. Kafka (Azure Event Hubs Premium, Kafka endpoint)

| Create | Setting | Terraform |
| --- | --- | --- |
| Namespace `pbp-<env>-kafka-<suffix>` | **Premium**, 1 processing unit (Premium supports the compacted hubs Kafka Connect needs); TLS 1.2; network rules: deny by default, allow the `aks` subnet and trusted Azure services | `azurerm_eventhub_namespace.kafka` |
| Event hubs (topics) | The 8 topics in the common runbook section 4 with their partitions; retention 7 days for commands and 30 for events; a `.DLT` hub for each (3 partitions) | `azurerm_eventhub.topic` |
| Compacted event hubs | `mapping-events` (6), `pbp-connect-configs` (1), `pbp-connect-offsets` (25), `pbp-connect-statuses` (5); cleanup policy Compact | `azurerm_eventhub.compacted` |
| Shared access policy `pbp-services` | Listen, send and manage (manage lets Kafka Connect check its topics) | `azurerm_eventhub_namespace_authorization_rule.services` |

The services log in with SASL_SSL and PLAIN, username `$ConnectionString` and the policy's connection string as password. Terraform writes that JAAS line to the Key Vault secret `pbp-<env>-spring-kafka-properties-sasl-jaas-config`:

`org.apache.kafka.common.security.plain.PlainLoginModule required username="$ConnectionString" password="<connection string>";`

For production, swap the network rule for a private endpoint in the VNet.

Write down: the bootstrap server `<namespace>.servicebus.windows.net:9093` (manifest: `config.KAFKA_BOOTSTRAP`).

## 6. Cache (Azure Cache for Redis)

| Create | Setting | Terraform |
| --- | --- | --- |
| Cache `pbp-<env>-cache-<suffix>` | Premium P1; TLS only on port 6380, TLS 1.2; in the `redis` subnet; no public access; zones 1, 2 and 3; 2 replicas per primary | `azurerm_redis_cache.cache` |

Azure Managed Redis is Microsoft's successor product; the services only need the Redis protocol over TLS, so either works.

Write down: the host name (manifest: `config.REDIS_HOST`; `values-azure.yaml` already sets port 6380 and TLS on).

## 7. Key Vault, secrets and identities

| Create | Setting | Terraform |
| --- | --- | --- |
| Key Vault `pbp-<env>-kv-<suffix>` | Premium; RBAC authorisation; purge protection on, soft delete 90 days; network: deny by default, allow the `aks` subnet, trusted Azure services and `admin_cidrs` | `azurerm_key_vault.kv` |
| Role for whoever runs Terraform | Key Vault Secrets Officer, to write the secrets below | `azurerm_role_assignment.deployer_secrets` |
| Secrets `pbp-<env>-db-user`, `-db-password`, `-webhook-secret`, `-spring-kafka-properties-sasl-jaas-config` | As in the common runbook section 5 | `azurerm_key_vault_secret.service` |
| Managed identity `pbp-<env>-external-secrets` | With a federated credential for the service account `external-secrets/external-secrets` on the cluster's OIDC issuer; **Key Vault Secrets User** on the vault | `azurerm_user_assigned_identity.external_secrets` and the two resources after it |

In the cluster, install the External Secrets Operator in the namespace `external-secrets`, annotate its service account with `azure.workload.identity/client-id: <external_secrets_client_id>`, and create a `ClusterSecretStore` named `azure-key-vault` pointing at the vault URI with workload identity. No keys are stored in the cluster.

Purge protection means a deleted vault keeps its name for 90 days; a re-created environment gets a new suffix.

## 8. Blob Storage and the registry

| Create | Setting | Terraform |
| --- | --- | --- |
| Storage account `pbp<env><suffix>` | Standard, zone-redundant (ZRS); TLS 1.2; no public blobs; versioning on; network: deny by default, allow the `aks` subnet and `admin_cidrs` | `azurerm_storage_account.data` |
| Containers `documents`, `settlement`, `recon`, `archive`, `archive-locked` | Private | `azurerm_storage_container.container` |
| Immutability policy on `archive-locked` | **7 years** (2,555 days), protected append writes. Test it, then lock it: once locked, objects cannot be deleted before the period ends | `azurerm_storage_container_immutability_policy.archive_locked` |
| Container registry `pbp<env><suffix>` | Premium, zone-redundant, admin user off | `azurerm_container_registry.acr` |
| Role assignment | The cluster's kubelet identity is **AcrPull** on the registry: no image pull secret | `azurerm_role_assignment.aks_acr` |

ACR creates a repository on the first push of each image (bff, orchestrator, mapping-service, ehi-responder, essence-adapter, thredd-adapter, atfinity-adapter, credit-adapter, recon-service, vendor-simulator, web). The deploy workflow pushes to OCIR only today (common runbook, section 10): push with `az acr login` and `docker push`, then run `helm upgrade` by hand with `-f values-azure.yaml`.

## 9. Public entry, card entry and WAF (Application Gateway)

On Azure one Application Gateway serves both entries: the web app and, with a card module, Thredd's EHI calls. Each host name has its own listener and certificate.

| Create | Setting | Terraform |
| --- | --- | --- |
| WAF policy `pbp-<env>-waf` | Prevention mode; OWASP 3.2 rules; rate limit 3,000 requests a minute per client IP (50 a second, as on OCI), except on the EHI host, where Thredd sends up to 500 authorisations a second; a rule that blocks the EHI host name for any address outside Thredd's ranges | `azurerm_web_application_firewall_policy.waf` |
| Managed identity `pbp-<env>-appgw` | **Key Vault Secrets User**, to read the certificates | `azurerm_user_assigned_identity.appgw`, `azurerm_role_assignment.appgw_certs` |
| Static public IP `pbp-<env>-appgw` | Standard, zones 1–3. Point both DNS names at it | `azurerm_public_ip.appgw` |
| Application Gateway `pbp-<env>-appgw` | **Second apply only.** WAF_v2, autoscaling 1 to 4, zones 1–3, TLS policy `AppGwSslPolicy20220101`; HTTPS listeners for `web_hostname` and `ehi_hostname` with their Key Vault certificates; backends: the web internal load balancer (10.40.15.10) and the EHI internal load balancer (10.40.15.11, 5-second timeout) | `azurerm_application_gateway.appgw` |

Between the applies:

1. Upload two certificates to the Key Vault (PFX, or a Key Vault certificate): one for the web host, one for the EHI host.
2. Set `web_certificate_secret_id` and `ehi_certificate_secret_id` to their secret IDs and apply again.

The chart puts the web app and the EHI responder on internal load balancers at the fixed IPs in `values-azure.yaml` (`azure.webPrivateIp`, `azure.ehiPrivateIp`); keep them equal to the Terraform variables `web_private_ip` and `ehi_private_ip`.

**Without a card module**: the Azure module has no `modules` variable yet. The EHI listener and backend are still created but receive nothing (the chart creates no EHI load balancer), and the WAF rule blocks the EHI host for everyone when no Thredd ranges are set. Use any placeholder certificate for EHI, or remove that listener before applying. Adding a `modules` variable, as on OCI, is a phase B item.

## 10. Identity, observability and the manifest

**Identity (Microsoft Entra ID)**

Two options, as in `values-azure.yaml`:

1. **Keycloak in the cluster, brokering to Entra ID** (default): the web app and the BFF are unchanged. Register Keycloak as an application in Entra ID, add Entra ID as an identity provider in the `pbp` realm, and map the groups `staff` and `compliance`. Manifest: Keycloak's JWKS URL (`config.KEYCLOAK_JWKS`).
2. **Entra ID tokens directly**: an app registration with app roles `staff` and `compliance`; manifest: Entra ID's JWKS URL and `config.PBP_AUTH_ROLES_CLAIM: roles`.

**Observability**

- In-cluster Grafana stack (common runbook, section 6); `observability.enabled: true`.
- Terraform also creates a Log Analytics workspace `pbp-<env>-logs` (90 days; AKS container logs go there) and Application Insights `pbp-<env>-apm`. The OpenTelemetry Collector can add an Azure Monitor exporter with the Application Insights connection string, beside Tempo.

**Fill the manifest** (common runbook, section 5, and `values-azure.yaml`), then deploy (section 7) and check (section 8).

| Terraform output | Manifest key |
| --- | --- |
| `nat_egress_ip` | Vendors' allow-lists |
| `postgres_endpoint` | `config.DB_HOST` |
| `kafka_bootstrap` | `config.KAFKA_BOOTSTRAP` |
| `cache_endpoint` | `config.REDIS_HOST` |
| `registry` | `image.registry` |
| `key_vault_uri` | External Secrets `ClusterSecretStore` `azure-key-vault` |
| `external_secrets_client_id` | Annotation on the External Secrets service account |
| `public_ip` | DNS for the web and EHI host names |
| `appinsights_connection_string` | OpenTelemetry Collector Azure Monitor exporter (optional) |
| `cluster_name`, `resource_group` | `az aks get-credentials` |

## 11. What still needs a person or a second apply

| Item | Why |
| --- | --- |
| Terraform state storage account | Must exist before `terraform init` |
| `admin_cidrs` | Without your address, Terraform cannot write the Key Vault secrets (the vault denies by default) |
| TLS certificates in the Key Vault, then the second apply | The Application Gateway needs them (section 9) |
| REPLICATION for `pbpadmin`; `temporal` and `temporal_visibility` databases | Section 4 |
| External Secrets service account annotation and `ClusterSecretStore` | Section 7 |
| Entra ID application and groups or app roles | Section 10 |
| Images pushed to ACR and `helm upgrade` by hand | The deploy workflow pushes to OCIR only (common runbook, section 10) |
| Immutability policy locked | Lock it after testing (section 8) |
