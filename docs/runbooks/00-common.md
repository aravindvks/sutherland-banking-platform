# Sutherland Banking Platform – Provisioning Runbook: Common Steps

**Status: not yet run in a real cloud account.** Written from the Terraform modules, the Helm chart and the deploy workflow in this repository (10 November 2026). Each step will be confirmed or corrected on the first dry run.

## 1. Who this is for and how to use it

This runbook is for a bank's cloud team, or the Sutherland team working with them, standing up one environment of the Sutherland Banking Platform. It has three parts:

1. **This common runbook**: what every cloud needs, the decisions to make first, the environment manifest, the one-click deploy, checks and tear-down.
2. **One runbook per cloud**: how to provision each item on that cloud. OCI is first; AWS and Azure follow; GCP is design only.
3. **The environment manifest**: one file the cloud team fills in as they provision. The deploy reads it; nothing else needs to be typed into the platform.

Each step has two paths: provision it your own way (console, CLI, the bank's own Terraform), or apply the module in `infra/terraform/<cloud>`, which creates the same things. Either way, the result goes into the manifest.

**Order:** decide (section 2) → provision (cloud runbook) → fill the manifest (section 5) → cluster add-ons (section 6) → deploy (section 7) → check (section 8).

## 2. Decisions before you start

| Decision | Options | Where it is used |
| --- | --- | --- |
| Cloud and region | OCI, AWS or Azure; a UK region for UK customers (data residency) | Cloud runbook, Terraform `region` |
| Environment name | `dev`, `test`, `demo` or `prod` | Names of every resource (`pbp-<env>-…`), Temporal namespace `pbp-<env>`, Helm release `pbp-<env>` |
| Modules | Always: onboarding, payments, compliance. Optional: cards, creditcards, recon | Build, deploy and screens (one list) |
| Vendors | Simulator or sandbox per vendor (Finastra, Thredd, Atfinity, credit bureau) | `ESSENCE_MODE`, `THREDD_MODE`, `ATFINITY_MODE`, `CREDIT_MODE` |
| Identity | The cloud's identity service (OCI Identity Domains, Cognito, Entra ID) or Keycloak brokering to it | Login for customers and staff |
| Network address range | A /16 that does not clash with the bank's networks | Terraform `vcn_cidr` / `vpc_cidr` / `vnet_cidr` |
| Observability | Grafana stack in the cluster, plus the cloud's own monitoring if wanted | `observability.enabled` |

**Modules decide what to provision.** Without `creditcards` there is no credit-adapter. Without `recon` there is no recon-service. Without either `cards` or `creditcards` (for example a corporate bank), there is no thredd-adapter, no ehi-responder and no EHI load balancer, so nothing needs to be set up for Thredd.

## 3. What every environment needs

| # | Item | Why | Sizing for a dev or demo environment |
| --- | --- | --- | --- |
| 1 | Private network with four subnets (public, Kubernetes API, workers, data) | Only the load balancers are public | One /16; /24 per subnet |
| 2 | Kubernetes cluster, private API endpoint | Runs every service, Temporal, Kafka Connect, the Grafana stack | 3 worker nodes across zones or fault domains, **4 vCPU / 16 GB each** (minimum 3 for the EHI responder's spread); production: section 3a |
| 3 | PostgreSQL 16, private, with logical replication | One database, a schema per service; logical replication is needed by the outbox relay | 2 vCPU / 32 GB, primary plus a read replica |
| 4 | Kafka-compatible event service, private | Commands and events between services | 9 topics plus a dead-letter topic each (section 4) |
| 5 | Redis-compatible cache, TLS | Card cache for authorisations | 3 nodes, 2 GB |
| 6 | Secrets store | Database, Kafka and webhook secrets; vendor credentials later | 4 secrets to start (section 5) |
| 7 | Object storage: documents, settlement files, recon reports, archive | Files and the 7-year archive | 4 buckets plus one with a 7-year retention lock |
| 8 | Container registry | Images built by the deploy workflow | One repository per image (section 7) |
| 9 | Fixed outbound IP (NAT) | Finastra, Thredd and Atfinity allow-list one address | One reserved IP |
| 10 | Public web entry with WAF | Customers and staff reach the web app and API | HTTPS only |
| 11 | Card authorisation entry (only with a card module) | Thredd's real-time calls, separate from customer traffic | TLS, Thredd's IP ranges only |
| 12 | DNS names and TLS certificates | Web host name; EHI host name registered with Thredd | Two names |
| 13 | Identity provider | Customer and staff login, roles `staff` and `compliance` | One application, two groups |
| 14 | Monitoring (optional) | The cloud's logs and tracing, beside the in-cluster Grafana stack | As the bank prefers |

Production also adds the in-country standby region (phase D, not yet written).

## 3a. Sizing by environment

The cluster runs the platform's own pods, Temporal, Kafka Connect and the Grafana stack. PostgreSQL, Kafka, Redis and identity are managed services outside it. What the pods ask for depends on the Helm values file:

| Values file | Use for | Platform pods (minimum) | CPU requested | Memory requested |
| --- | --- | --- | --- | --- |
| `values-demo.yaml` sizing | dev, test, demo | 12 | about 3.6 vCPU | about 10 GB |
| `values.yaml` (LLD v2 section 13) | production, pre-production | 22, plus the simulator if on | about 14.5 vCPU | about 29 GB |

Add about 4 vCPU and 9 GB for Temporal (frontend, history, matching, worker, UI), Kafka Connect, the Grafana stack and the External Secrets Operator, and about 10% per node for Kubernetes itself. Production pods also scale out under load: at their maximum replicas the platform asks for about 40 vCPU.

| Environment | Node pool | Why |
| --- | --- | --- |
| dev, test, demo | **3 nodes × 4 vCPU / 16 GB** (12 vCPU, 48 GB): OCI E4.Flex 2 OCPU / 16 GB, AWS m6i.xlarge, Azure Standard_D4s_v5 (the Terraform defaults) | Demo sizing plus add-ons is about 8 vCPU and 19 GB, with room for rolling updates |
| production | **6 nodes × 4 vCPU / 16 GB to start** (24 vCPU, 96 GB), across 3 zones, with the cluster autoscaler allowed to 12 | Production sizing plus add-ons is about 19 vCPU and 38 GB at minimum replicas; the autoscaler covers scale-out |

**Production sizing does not fit the 3-node pool**: pods would stay Pending. For dev and test, start `values-<env>.yaml` from the `services:` block of `values-demo.yaml` (the deploy workflow reads `values-<env>.yaml` when it exists, otherwise the production defaults in `values.yaml`).

Two more points:
- **Units**: OCI sizes nodes in OCPUs; one OCPU is two vCPUs, so 2 OCPU is 4 vCPU. Everything in these runbooks is in vCPUs.
- **Fewer modules, fewer pods**: without credit cards there is no credit-adapter, without reconciliation no recon-service, and without any card module no thredd-adapter or ehi-responder (the largest service: 3 to 10 pods of 1 vCPU in production).

## 4. Kafka topics

Create these if the event service does not create them on first use (OCI and Azure: Terraform creates them; AWS: a Helm job does).

| Topic | Partitions | Retention |
| --- | --- | --- |
| payment-commands | 12 | 7 days |
| onboarding-commands | 6 | 7 days |
| card-commands | 6 | 7 days |
| customer-commands | 6 | 7 days |
| kyc-events | 6 | 7 days (30 in production) |
| card-events | 12 | 7 days (30 in production) |
| payment-events | 12 | 7 days (30 in production) |
| onboarding-events | 6 | 7 days (30 in production) |
| mapping-events | 6 | Compacted |
| `<topic>.DLT` for each | 3 | 7 days |
| pbp-connect-configs, pbp-connect-offsets, pbp-connect-statuses | 1 / 25 / 5 | Compacted (Kafka Connect's own topics; on OCI a Connect configuration provides them instead) |

## 5. The environment manifest

The cloud team fills this in as they provision; each line names where the value comes from. The values go into `deploy/helm/pbp/values-<env>.yaml` (non-secret) and the secrets store (secret).

**Non-secret values** (`values-<env>.yaml`)

| Key | Example | From |
| --- | --- | --- |
| `cloud` | `oci` | Decision |
| `env` | `dev` | Decision |
| `modules` | `[onboarding, payments, compliance, cards, recon]` | Decision |
| `image.registry` | `uk-london-1.ocir.io/<namespace>/pbp` | Registry |
| `config.DB_HOST` | `10.20.20.10` | PostgreSQL private endpoint |
| `config.KAFKA_BOOTSTRAP` | `cell-1.streaming.uk-london-1.oci.oraclecloud.com:9092` | Event service bootstrap |
| `config.SPRING_KAFKA_PROPERTIES_SASL_MECHANISM` | `PLAIN` (OCI, Azure) or `SCRAM-SHA-512` (AWS) | Event service |
| `config.REDIS_HOST` | cache endpoint | Cache |
| `config.KEYCLOAK_JWKS` | identity provider's JWKS URL | Identity |
| `config.ESSENCE_MODE`, `THREDD_MODE`, `ATFINITY_MODE`, `CREDIT_MODE` | `simulator` | Decision |
| `ehiLoadBalancer.sourceRanges` | Thredd's published egress ranges | Thredd |
| Cloud-specific (certificate ARNs, fixed private IPs, WAF ID) | | Cloud runbook |

**Secrets** (one secret each, named `pbp-<env>-<key>`; the External Secrets Operator copies them into the cluster)

| Secret | Content |
| --- | --- |
| `pbp-<env>-db-user` | Database user for the services |
| `pbp-<env>-db-password` | Its password |
| `pbp-<env>-webhook-secret` | Shared secret that signs vendor webhooks (HMAC) |
| `pbp-<env>-spring-kafka-properties-sasl-jaas-config` | The full SASL JAAS line for the event service |

**For the vendors**: the fixed outbound IP (for their allow-lists) and the EHI host name (for Thredd).

## 6. Cluster add-ons (once per cluster)

1. **Namespaces**: `pbp-<env>` (the platform), `temporal`, `observability` (if used).
2. **Temporal**: install the official Temporal Helm chart in `temporal`, with persistence on the same PostgreSQL (its own databases, `temporal` and `temporal_visibility`). Register the namespace `pbp-<env>`. The services reach it at `temporal-frontend.temporal:7233`.
3. **External Secrets Operator** with a `ClusterSecretStore` pointing at the cloud's secrets store (name it as in `externalSecrets.secretStore`), and set `externalSecrets.enabled: true`.
4. **Image pull secret** `ocir` (OCI) or node or workload identity for the registry (AWS, Azure).
5. **TLS secret** `ehi-tls` for the EHI host: OCI only, and only with a card module (AWS keeps the certificate in ACM; Azure in Key Vault, for the Application Gateway).
6. **Observability** (optional): kube-prometheus-stack, Tempo, Loki and the OpenTelemetry Collector in `observability`; then `observability.enabled: true`.

## 7. Deploy in one click

Run the **deploy** workflow in GitHub Actions: *Actions → deploy → Run workflow*, choose the environment and the module list. It:

1. builds and tests the code with only those modules,
2. builds and pushes the images: bff, orchestrator, mapping-service, ehi-responder, essence-adapter, thredd-adapter, atfinity-adapter, credit-adapter, recon-service, vendor-simulator and web,
3. runs `helm upgrade --install pbp-<env>` with `values-<env>.yaml` and the module list. Without a `values-<env>.yaml` it uses production sizing, which needs the production node pool (section 3a).

Repository settings it needs: secrets `OCIR_REGISTRY`, `OCIR_USERNAME`, `OCIR_TOKEN`, `KUBECONFIG_B64`; variables `IDENTITY_URL`, `GRAFANA_URL`.

**Today the workflow pushes to OCIR only.** On AWS or Azure, push the images and run the same `helm upgrade` by hand with `-f values-aws.yaml` or `-f values-azure.yaml` until the workflow takes a cloud input (phase B).

The deploy also registers the Debezium outbox connector on Kafka Connect (a Helm hook Job, `register-outbox-connector`, after every install or upgrade), using the database host from the manifest and the service user from the secrets. The service user needs the REPLICATION privilege and the database `wal_level = logical` (cloud runbook).

**After the first deploy**

1. Wire the public entry and WAF to the load balancers the chart created (cloud runbook: on OCI, set `bff_backend_url` and `ehi_load_balancer_id` and apply Terraform again).
2. Point the DNS names at the entries, and give Thredd the EHI host name.

## 8. Check the environment

1. `kubectl get pods -n pbp-<env>`: every service Running and Ready; Kafka Connect's `pbp-outbox` connector RUNNING.
2. `GET /v1/modules` on the BFF lists exactly the bank's modules.
3. Run the smoke tests against the environment with the bank's module list (`PBP_MODULES=<list> scripts/smoke.sh`, with the service URLs pointed at the environment). With simulators, every step must pass.
4. Grafana: the Overview dashboard shows the smoke test's payment, and its correlation ID opens one trace.
5. Vendors confirm they see calls from the fixed outbound IP (when sandboxes are connected).

## 9. Tear down

1. `helm uninstall pbp-<env> -n pbp-<env>` (and the add-ons, if the cluster goes too).
2. `terraform destroy` in the cloud folder, or delete in reverse order of provisioning.
3. **The 7-year archive bucket cannot be emptied or deleted early**, by design. In dev and demo, create it without the lock, or accept that it stays.
4. Release the fixed outbound IP only after the vendors have removed it from their allow-lists.

## 10. Known gaps (to close in phase B)

- The deploy workflow pushes to OCIR only; it needs a cloud input and a registry login per cloud.
- On OCI, whether the managed PostgreSQL accepts `wal_level = logical` is confirmed only on the first apply (OCI runbook, section 11).
- Saudi Arabia and UAE deployments and the in-country standby region: phases C and D, after approval.
