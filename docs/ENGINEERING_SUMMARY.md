# Engineering Summary

## Outcome

Delivered a runnable Spring Boot URL shortener prototype with creation, redirect, lookup-without-redirect, soft expiry, asynchronous click analytics, per-group rate limiting, health checks, redirect caching, narrow target-URL validation, and a versioned-persistence production profile.

## Plan And Rationale

The implementation separates HTTP concerns from business rules and persistence: controllers stay thin, `ShortenerService`/`RedirectService`/`AnalyticsService` own their logical service's rules, and the three logical services never call each other, so any one of them can be extracted into its own deployable without a rewrite. A dedicated async click recorder persists click events off the redirect thread so redirects never wait on, or fail because of, the analytics write.

The solution prioritizes predictable behavior over unnecessary abstraction:

- A database unique constraint, not a check-then-insert race, establishes short-code uniqueness, with a bounded retry on the rare collision.
- `302 Found`, not a cacheable `301`, is the default because it's what keeps every visit reaching the service and being counted.
- Click ingestion is bounded and best-effort (a fixed-size queue, drop-and-log when full) to protect the redirect path from database delay.
- The one ambiguous requirement ("appropriate reliability features") was decomposed and resolved as service-level rate limiting, not retry or circuit-breaking, with the choice and its parameters made explicit before implementation.
- Flyway migrations replace implicit schema creation for the production profile; the schema is versioned instead of Hibernate silently altering it (`ddl-auto=update`, which is only ever used against the local, in-memory database).

## Artifacts

| Artifact | Purpose |
|---|---|
| `docs/SETUP.md` | Local and standalone-container production setup, step by step |
| `docs/ARCHITECTURE.md` | Components, request control flow, key decisions, and operational boundaries |
| `docs/ENGINEERING_SCENARIOS.md` | Greenfield, brownfield, and ambiguous-requirement execution examples |
| `docs/ENGINEERING_EXECUTION_RECORD.md` | Requirement traceability, AI-assisted work log, quality gates, and high-impact change approvals |
| `src/main/resources/db/migration/` | Versioned Flyway schema baseline (production profile only) |
| `src/main/resources/application-prod.properties` | Production profile: file-based H2, required credentials, disabled H2 console, proxy header trust |
| `src/test/java/com/demo/urlshortner/` | Unit, repository, and `@SpringBootTest` + MockMvc integration tests |

## Validation Evidence

- Full test suite: **132 passed, 0 failed, 0 errors** (fresh `./gradlew clean test` run).
- Focused collision-safety tests (`ShortCodeCollisionIntegrationTest`, includes a real concurrent collision): 3 passed, 0 failed.
- Focused rate-limit configuration tests (`RateLimitConfigTest`): 7 passed, 0 failed.
- Focused expiration tests (`UrlExpirationIntegrationTest`): 10 passed, 0 failed.
- Focused error-semantics tests (`ErrorSemanticsIntegrationTest`): 4 passed, 0 failed.
- Local smoke test: created a short link, verified a `302` response with the expected `Location` header, and confirmed the click count and `lastClickedAt` updated via the analytics endpoint.
- Production-profile smoke test (run directly with the `prod` profile): Flyway applied its migration cleanly; a missing `DB_USERNAME`/`DB_PASSWORD` failed startup immediately with a clear error instead of falling back to a default; valid credentials completed the same create-redirect-analytics flow; data survived a full process kill and restart against the same database file.

## Assumptions

- The product is a public, anonymous shortener; authentication, link ownership, and per-client authorization are intentionally not implemented.
- H2 (in-memory for local development, file-based for the production profile) is acceptable for local development, demonstration, and a single-node deployment &mdash; it is not a substitute for a managed relational database at real production scale.
- Click analytics is a single aggregate counter per link for operational visibility, not a per-event ledger, billing record, or compliance artifact.
- The deployment platform provides TLS termination, secret injection, and any centralized logging or monitoring; none of that is implemented in the application itself.
- Rate-limit defaults are configurable prototype values for demonstration, not numbers derived from real traffic or SLOs.

## Risks, Trade-Offs, And Limitations

| Area | Current position | Follow-up before broader production use |
|---|---|---|
| Horizontal scale | File-based H2 (production profile) allows only one process to hold the database at a time; the redirect cache and rate limiter are also per-instance, in-memory | Move to a managed external database, and a shared cache/rate-limit store (e.g. Redis), before running more than one instance &mdash; see ARCHITECTURE.md's [Path to Microservices](ARCHITECTURE.md#path-to-microservices) |
| Analytics durability | Click counting uses a bounded in-memory queue; queued clicks are lost on a crash, and new clicks are dropped and logged when the queue is full | Replace with a durable queue or event stream if click counts must never be lost (already the documented future direction for `ClickRecorder`) |
| Authorization | All endpoints are public and anonymous; there is no ownership model | Add authentication, an ownership model, and authorization policies before multi-tenant use |
| Destination protection | URL validation checks only scheme and host presence; internal/private addresses (e.g. `localhost`, `10.x.x.x`) are not rejected | Apply a DNS/egress policy or destination allowlist where intranet navigation must be prevented |
| Host validation gap | Valid hostnames that `java.net.URI` can't parse (underscores, non-ASCII/IDN) are rejected with 400 | Decide and implement broader host-validation support if these hosts must be supported (open, already tracked) |
| Database credential recovery | The production profile's `DB_USERNAME`/`DB_PASSWORD` become permanent on the first run against a fresh database file; there is no recovery path through the app if lost | Store the credential in a real secret manager with its own backup/rotation process, not a plain environment variable |
| Performance evidence | Design minimizes redirect-path work (async click counting, cached lookups) but there is no load-test result | Define SLOs and run load, soak, and failure-injection tests |
| Supply-chain assurance | No static analysis, dependency vulnerability scan, or structured logging/metrics is configured | Add these as CI gates before production release |

## Release Recommendation

Suitable as a reviewable working prototype and a foundation for a controlled single-node production deployment, now that the production profile's schema versioning and required-credential validation have been implemented and verified. Do not characterize it as horizontally scalable, fully observable, or load-tested until the items above &mdash; external persistence for multi-instance use, durable analytics, authorization, destination-address policy, and performance/dependency-scanning gates &mdash; are in place.
