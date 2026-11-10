# Sutherland Banking Platform – Provisioning Runbook: OCI

**Status: not yet run in a real tenancy.** Written from `infra/terraform/oci` and the Helm chart (10 November 2026). Read the common runbook first: it has the decisions, the environment manifest, the deploy and the checks. Console menus change; each step says what to create and how to set it, with the Terraform resource that does the same.

## 1. Before you start

- **Tenancy and region**: UK South (London), `uk-london-1`, for UK customers. For Saudi Arabia (Riyadh, Jeddah) and the UAE (Dubai, Abu Dhabi), see section 12.
- **Compartment**: one per environment, ideally inside a CIS Landing Zone. All resources below go in it.
- **Access**: a group with permission to manage networking, OKE, PostgreSQL, Streaming, Cache, Vault, Object Storage, OCIR, API Gateway, WAF, Logging and APM in the compartment.
- **Service limits**: check the compartment's limits for OKE nodes (3 × E4 Flex, 2 OCPU / 16 GB each for dev and demo; 6, autoscaling to 12, for production: common runbook, section 3a), PostgreSQL (2 OCPU / 32 GB, 2 instances), Streaming partitions (about 100, including dead-letter topics), Cache (3 nodes) and API Gateways.
- **Tools on the operator's machine**: OCI CLI, `kubectl`, `helm`, Terraform 1.6+ (for the Terraform path).
- **Decisions** from the common runbook, section 2: environment name, modules, network range (default `10.20.0.0/16`).

**Terraform path**: in `infra/terraform/oci`, copy `terraform.tfvars.example` and `backend.hcl.example`, fill in `tenancy_ocid`, `compartment_id`, `env`, `modules`, `node_image_id`, `postgres_password`, `webhook_secret`, `streaming_user`, `streaming_auth_token` and `thredd_ehi_source_cidrs`, then `terraform init -backend-config=backend.hcl` and `terraform apply`. It creates everything in sections 2 to 9. Before applying, create the Streaming user, its group (`streaming_group`, default `pbp-streaming`) and an auth token (section 5); Terraform does not create users.

## 2. Network (VCN)

| Create | Setting | Terraform |
| --- | --- | --- |
| VCN `pbp-<env>` | CIDR `10.20.0.0/16`, DNS label `pbp<env>` | `oci_core_vcn.main` |
| Internet gateway | For the public subnet only | `oci_core_internet_gateway.igw` |
| Reserved public IP `pbp-<env>-nat-egress` | Lifetime RESERVED. **This is the address the vendors allow-list** | `oci_core_public_ip.nat` |
| NAT gateway | Uses the reserved IP above | `oci_core_nat_gateway.nat` |
| Service gateway | All services in the Oracle Services Network (keeps Vault, Object Storage and OCIR traffic off the internet) | `oci_core_service_gateway.sgw` |
| Route table `public` | 0.0.0.0/0 → internet gateway | `oci_core_route_table.public` |
| Route table `private` | 0.0.0.0/0 → NAT gateway; Oracle services → service gateway | `oci_core_route_table.private` |
| Security list `public` | Ingress TCP 443 from anywhere; egress to the VCN | `oci_core_security_list.public` |
| Security list `private` | Ingress from the VCN; egress anywhere (through NAT) | `oci_core_security_list.private` |

| Subnet | CIDR | Public | Route table | Holds |
| --- | --- | --- | --- | --- |
| `public` | 10.20.0.0/24 | Yes | public | API Gateway, load balancers |
| `oke-api` | 10.20.1.0/24 | No | private | Kubernetes API endpoint |
| `workers` | 10.20.10.0/24 | No | private | Worker nodes and pods |
| `data` | 10.20.20.0/24 | No | private | PostgreSQL, Streaming, Cache endpoints |

Write down: the reserved IP (manifest: vendors' allow-lists).

## 3. Kubernetes (OKE)

| Create | Setting | Terraform |
| --- | --- | --- |
| Cluster `pbp-<env>` | Enhanced cluster; Kubernetes 1.30; API endpoint in `oke-api`, **no public IP**; service load balancers in `public` | `oci_containerengine_cluster.oke` |
| Node pool `pbp-<env>-workers` | VM.Standard.E4.Flex, 2 OCPU (4 vCPU), 16 GB; Oracle Linux OKE image for the region and version; **3 nodes** for dev and demo, **6** for production (`node_count`), placed across every availability domain (or fault domain) in `workers` | `oci_containerengine_node_pool.workers` |
| Cluster autoscaler (production) | OKE cluster autoscaler add-on, node pool between 6 and 12 nodes | — |

Reach the private API endpoint through the OCI Bastion service or a VPN, then `oci ce cluster create-kubeconfig`. The deploy workflow needs this kubeconfig, base64-encoded (`KUBECONFIG_B64`), and a runner that can reach the endpoint.

## 4. PostgreSQL 16 (OCI Database with PostgreSQL)

| Create | Setting | Terraform |
| --- | --- | --- |
| Configuration `pbp-<env>-pg-logical` | PostgreSQL 16, same shape; overrides `wal_level = logical`, `max_replication_slots = 10`, `max_wal_senders = 10` (for the outbox relay) | `oci_psql_configuration.pg` |
| DB system `pbp-<env>-pg` | Version 16; PostgreSQL.VM.Standard.E4.Flex, 2 OCPU, 32 GB; **2 instances** (primary plus read replica, which the EHI responder reads if the cache is down); subnet `data`; OCI-optimised, regionally durable storage; admin user `pbpadmin`; **the configuration above** | `oci_psql_db_system.pg` |

Then, connected as the admin user:

1. Create the database `pbp`. The services connect as `pbpadmin` (the secrets `pbp-<env>-db-user` and `-db-password`, as on AWS) and create their own schemas with Flyway on start-up.
2. Give `pbpadmin` REPLICATION (`ALTER ROLE pbpadmin WITH REPLICATION;`) for the outbox relay.
3. Create the databases `temporal` and `temporal_visibility` for Temporal.
4. Check logical replication: `SHOW wal_level;` must answer `logical`. Oracle documents logical replication on this service (pglogical, replication slots); whether it accepts the `wal_level` override for Debezium's `pgoutput` is confirmed on the first apply. If not, the fallback is a polling outbox relay (a code change).
5. Size `max_connections` for pods × 10 (the services' connection pool) plus Temporal.

Write down: the primary endpoint's private IP (manifest: `config.DB_HOST`).

## 5. Kafka (OCI Streaming with Kafka compatibility)

| Create | Setting | Terraform |
| --- | --- | --- |
| Stream pool `pbp-<env>-kafka` | Kafka settings on; auto-create topics **off**; private endpoint in `data` | `oci_streaming_stream_pool.kafka` |
| Streams | The 9 topics and a `.DLT` stream for each, as in the common runbook section 4 | `oci_streaming_stream.topic` |
| Connect configuration `pbp-<env>-connect` | Kafka Connect's own storage: topics `<OCID>-config`, `-offset` and `-status`. Put its OCID in the manifest as `connect.ociConnectHarnessId` | `oci_streaming_connect_harness.connect` |
| Streaming user and auth token | **By hand, before Terraform**: a user (ideally a dedicated service user) in the group `pbp-streaming`, and an auth token for it | — |
| Policy | The group may use the stream pool and the Connect configuration (`use stream-family`) | `oci_identity_policy.access` |

The services log in with SASL_SSL and PLAIN. Terraform builds the JAAS line for the secret `pbp-<env>-spring-kafka-properties-sasl-jaas-config` from `streaming_user` and `streaming_auth_token`:

`org.apache.kafka.common.security.plain.PlainLoginModule required username="<tenancy>/<user>/<stream pool OCID>" password="<auth token>";`

Production retention: raise the event topics to 30 days if the pool allows it, otherwise rely on the Object Storage archive (HLD).

Write down: the bootstrap servers (manifest: `config.KAFKA_BOOTSTRAP`) and the Connect configuration's OCID (manifest: `connect.ociConnectHarnessId`).

## 6. Cache (OCI Cache)

| Create | Setting | Terraform |
| --- | --- | --- |
| Cluster `pbp-<env>-cache` | Redis 7; 3 nodes, 2 GB; subnet `data`; TLS | `oci_redis_redis_cluster.cache` |

Only the card authorisation path uses the cache. A bank without card modules still gets it today (the mapping service writes to it); it can be made optional later.

Write down: the primary endpoint (manifest: `config.REDIS_HOST`).

## 7. Vault, secrets and keys

| Create | Setting | Terraform |
| --- | --- | --- |
| Vault `pbp-<env>-vault` | Default vault | `oci_kms_vault.vault` |
| Master key `pbp-<env>-master` | AES-256; encrypts the secrets and the buckets | `oci_kms_key.master` |
| Secret `pbp-<env>-postgres-password` | Admin password | `oci_vault_secret.postgres` |
| Secrets `pbp-<env>-db-user`, `-db-password`, `-webhook-secret`, `-spring-kafka-properties-sasl-jaas-config` | As in the common runbook section 5 | `oci_vault_secret.service` |
| Dynamic group `pbp-<env>-workers` | In the tenancy: every instance in the compartment (the OKE worker nodes) | `oci_identity_dynamic_group.workers` |
| Policy `pbp-<env>-access` | The dynamic group may read secrets in the compartment (External Secrets uses the nodes' instance principal) | `oci_identity_policy.access` |

In a tenancy with several identity domains, prefix the group and dynamic group in the policy with the domain name.

## 8. Object Storage and the registry

| Create | Setting | Terraform |
| --- | --- | --- |
| Buckets `pbp-<env>-documents`, `-settlement`, `-recon`, `-archive` | Private, versioned, encrypted with the master key | `oci_objectstorage_bucket.bucket` |
| Bucket `pbp-<env>-archive-locked` | Private, encrypted; **retention rule 7 years**. Once a retention rule is locked, objects cannot be deleted before it ends | `oci_objectstorage_bucket.archive_locked` |
| OCIR repositories `pbp/<image>` | Private, one per image: bff, orchestrator, mapping-service, ehi-responder, essence-adapter, thredd-adapter, atfinity-adapter, credit-adapter, recon-service, vendor-simulator, web | `oci_artifacts_container_repository.repo` |

For the deploy workflow: an OCIR user and auth token (`OCIR_USERNAME` = `<namespace>/<user>`, `OCIR_TOKEN`), and the registry prefix `uk-london-1.ocir.io/<namespace>/pbp` (`OCIR_REGISTRY`). In the cluster: the `ocir` image pull secret with the same login.

## 9. Public entry, card entry and WAF

| Create | Setting | Terraform |
| --- | --- | --- |
| API Gateway `pbp-<env>-api` | Public, in `public` | `oci_apigateway_gateway.api` |
| Deployment `pbp-<env>-bff` | Path `/`; route `/v1/{path*}` to the web app's private load balancer; rate limit 50 requests per second per client IP | `oci_apigateway_deployment.bff` |
| WAF policy `pbp-<env>-ehi-waf` | Only with a card module. Default block (403); allow only Thredd's ranges | `oci_waf_web_app_firewall_policy.ehi` |
| WAF on the EHI load balancer | Only with a card module. Attach the policy once the load balancer exists (after the first deploy) | `oci_waf_web_app_firewall.ehi` |

**Only with a card module**: the chart creates the EHI load balancer (`ehi-public`), with TLS from the `ehi-tls` secret and Thredd's ranges as source ranges (`ehiLoadBalancer.sourceRanges`). Terraform's `modules` variable, set to the same list as Helm's, skips the WAF for a bank without cards; skip the `ehi-tls` secret too.

**After the first deploy**: set `bff_backend_url` (the web app's private load balancer address) and `ehi_load_balancer_id` (the `ehi-public` load balancer OCID) and apply Terraform again; by hand, point the API Gateway route and the WAF at them.

DNS and TLS: a host name and certificate for the web entry (on the API Gateway) and, with cards, for the EHI host (registered with Thredd).

## 10. Identity, observability and the manifest

**Identity (OCI Identity Domains)**

1. An identity domain (or the default one) with a confidential application for the web app; redirect URI on the web host name.
2. Groups `staff` and `compliance`, carried in the `groups` claim (the BFF reads roles from there).
3. Manifest: the domain's JWKS URL (`config.KEYCLOAK_JWKS`) and its URL for the web build (`IDENTITY_URL`).

**Observability**

- In-cluster Grafana stack (common runbook, section 6); `observability.enabled: true`.
- Optional: a Logging log group `pbp-<env>-app` and an APM domain `pbp-<env>-apm` (Terraform creates both); the OpenTelemetry Collector can also export to APM with its data upload endpoint and key.

**Fill the manifest** (common runbook, section 5), then deploy (section 7) and check (section 8).

| Terraform output | Manifest key |
| --- | --- |
| `nat_egress_ip` | Vendors' allow-lists |
| `postgres_endpoint` | `config.DB_HOST` |
| `kafka_bootstrap` | `config.KAFKA_BOOTSTRAP` |
| `cache_endpoint` | `config.REDIS_HOST` |
| `registry` | `image.registry` and `OCIR_REGISTRY` |
| `api_gateway_hostname` | DNS for the web host |
| `vault_id` | External Secrets `ClusterSecretStore` |
| `apm_data_upload_endpoint` | OpenTelemetry Collector (optional) |
| `connect_harness_id` | `connect.ociConnectHarnessId` |

## 11. What still needs a person or a first apply

| Item | Why |
| --- | --- |
| Streaming user, its group and auth token | Terraform does not create users; create them before `terraform apply` (section 5) |
| `wal_level = logical` accepted by OCI Database with PostgreSQL | Confirmed only on the first apply (`SHOW wal_level;`); fallback is a polling outbox relay |
| `ehi-tls` and `ocir` secrets in the cluster | Certificates and registry logins stay out of Terraform state |
| API Gateway route and EHI WAF | Point them at the load balancers after the first deploy (section 9) |
| Identity domain application and groups | Section 10 |

The outbox connector no longer needs a manual step: the deploy registers it (common runbook, section 7).

## 12. Saudi Arabia and the UAE (placeholder, phases C and D)

Each country gets its own deployment in its own compartment and region, with the same steps: Riyadh or Jeddah in Saudi Arabia, Dubai or Abu Dhabi in the UAE, with the other city as the in-country standby. The market pack (payment schemes, identifiers, name check, identity, reporting) and the standby (cross-region database, Kafka and storage replication, failover runbook) are written after approval.
