# Working in this repository

- Product name: **Sutherland Banking Platform**. UK market today (`markets/uk`); other markets are designed in `docs/design/market-packs.md`, not built. Country rules belong in the market pack, not in domain code. No AI layer. Saga engine: Temporal.
- Stack: Java 21, Spring Boot 3.3, Gradle (Groovy DSL), Temporal Java SDK 1.25, PostgreSQL 16, Kafka 3.8.
- Follow the LLD for names, endpoints, topics, tables and error codes. If the code must differ, record it under "Corrections to the LLD" in README.md.
- Every service depends on `libs/platform-common` for correlation IDs, problem+json errors and the event envelope. Do not re-implement these per service.
- Adapters translate; the orchestrator decides; vendors hold the rules. No business decisions in adapters.
- Money as decimal strings or BigDecimal, never double. Card data: tokens and last 4 only, never PAN.
- The cloud workspace used to write this code cannot reach Maven Central or Docker Hub, so builds and tests run on a developer laptop or in GitHub Actions. Keep changes compile-safe and say clearly what has not been run.
