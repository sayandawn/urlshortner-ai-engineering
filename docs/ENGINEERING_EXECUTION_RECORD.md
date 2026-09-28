# Engineering Execution Record

## Purpose And Ownership

This record makes the engineering process reviewable. Claude Code was used as an accelerator for local code inspection, implementation drafts, documentation drafting, and targeted validation selection. The engineer retains ownership of requirement interpretation, acceptance criteria, security posture, change approval, and release readiness.

No credentials, secrets, production connection strings, customer data, or personally identifiable production data were provided to AI during this work. The database is H2, in-memory, local-only, and uses a well-known local development default &mdash; not a secret, and never holding real user data.

## Requirement Normalization

| Requirement | Engineering interpretation | Acceptance evidence |
|---|---|---|
| Create short links | Accept a valid absolute http/https URL and return a generated Base62 code, independent of the database id | POST /api/urls returns 201 + Location; missing/blank URL returns 400 "Invalid request"; malformed or unsupported-scheme URL returns 400 "Invalid URL" |
| Redirect links | Resolve a code and redirect only while it hasn't expired | GET /{shortCode} returns 302 + Location; unknown code returns 404; expired code returns 410 |
| Resolve without redirecting | Give clients that can't follow a cross-origin redirect (e.g. Swagger UI) a way to see the target | GET /api/urls/{shortCode} returns 200 with the original URL; same 404/410 semantics as the redirect; never counts a click |
| Collision safety | Treat the database's unique constraint as the sole authority on uniqueness; no check-then-insert race | Bounded retry, up to 5 attempts, recognized by constraint name; 500 "Short code generation failed" after exhaustion; a real concurrent-collision test passes |
| Analytics | Record clicks without adding persistence latency to the redirect | Click counting runs off the redirect thread on a dedicated executor; a bounded queue and retry absorb temporary database failures; an integration test waits for eventual consistency |
| Expiration | Expired links stay auditable; nothing is deleted | A link past `expiresAt` returns 410 on redirect and lookup, while its row and click stats remain queryable via analytics |
| Consistent error contract | One shape for every error, not per-endpoint formats | A single exception handler maps every case (400/404/410/429/500) to RFC 9457 `application/problem+json` |
| Reliability (ambiguous requirement) | Protect the service from traffic volume, not per-client quota | Resilience4j rate limiting, one limiter per endpoint group, checked before the controller runs; 429 + `Retry-After`; a rejected request never increments `clickCount` |
| Redirect caching | Cut repeated database reads on the hot lookup path without caching data that changes | An immutable cached value excludes `clickCount`; the expiry check still runs on every request, cache hit or miss; a "not found" result is never cached |
| Operational health | Expose liveness/readiness without adding it to the business API surface | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`; verified live as not rate-limited and absent from `/v3/api-docs` |

## Delivery Plan And Dependencies

| Sequence | Task | Dependency | Acceptance criterion | Status |
|---|---|---|---|---|
| 1 | Define the API contract and domain invariants | Requirement normalization | Routes, DTOs, and status behavior documented | Complete |
| 2 | Build create/redirect lifecycle and initial persistence | Domain model | Short code, original URL, and creation time enforced via JPA/H2 | Complete |
| 3 | Add baseline error handling and a test baseline | Lifecycle service | Consistent 400/404 responses; unit and integration tests for creation and redirect | Complete |
| 4 | Harden collision safety, analytics, expiration, and error semantics | Components from 1&ndash;3 | Bounded retry, async click counting, soft expiry, unified ProblemDetail | Complete |
| 5 | Add redirect caching | Working redirect and lookup paths | Cache excludes `clickCount`; expiry re-checked on every hit | Complete |
| 6 | Enrich analytics and the manual test page | Analytics endpoint | `originalUrl`/`expired`/`lastClickedAt` in the response; UI sections for shorten (with optional expiry), redirect, and analytics | Complete |
| 7 | Decompose and implement the ambiguous reliability requirement | Public, unauthenticated API surface | Per-group rate limiting, startup-validated configuration, 429 contract | Complete |
| 8 | Add operational health checks | Running application | Liveness/readiness exposed, excluded from rate limiting and the OpenAPI contract | Complete |
| 9 | Complete release quality gates | All implementation work | Static analysis, dependency scanning, and load-test evidence recorded | Pending deployment environment |

## AI-Assisted Work Traceability

| Work item | AI-assisted activity | Engineer decision and rationale | Outcome |
|---|---|---|---|
| Codebase understanding | Read controllers, services, entity, and tests to apply the layering the engineer had already specified | Engineer proposed the layered structure himself in the initial prompt: thin controllers, business rules in `*Service` classes, three logical services that never call each other | Followed as directed throughout |
| Collision safety hardening | Implemented the retry-on-constraint-violation loop, the bounded 5-attempt limit, and the concurrent-collision test, to the engineer's detailed prompt | Engineer specified the exact contract: the database's unique constraint is the sole authority, matched by constraint name, never a check-then-insert race | Implemented; verified with a real concurrent-collision test, not just a mocked one |
| Async click counting (bounded queue) | Implemented the dedicated executor, the bounded queue, drop-on-full logging, graceful-shutdown draining, and retry-with-backoff, to the engineer's detailed prompt | Engineer specified the exact failure behavior: a redirect must never wait on or fail because of the click-count write; a full queue drops and logs rather than blocking; a graceful shutdown drains queued clicks first | Implemented; verified against eventual consistency, retry, and queue-full dropping |
| Redirect caching | Implemented the `@Cacheable` lookup, the immutable `RedirectTarget` value, and the cache-specific regression tests, to the engineer's detailed prompt | Engineer specified what must and must not be cached: URL and expiry are safe to cache, `clickCount` and "not found" are not, and the expiry check must still run on every request | Implemented; verified against stale-hit, click-count-on-hit, and not-found-then-created scenarios |
| Reliability implementation (rate limiting) | Implemented Resilience4j `RateLimiter` per endpoint group, the pre-controller interceptor, and startup validation, to the engineer's detailed prompt | Engineer decomposed the ambiguous "appropriate reliability features" requirement and chose rate limiting, the per-group model, and its parameters before any code was written | Implemented; verified against the 429 contract, `Retry-After`, and startup-failure cases |
| Health check implementation | Presented Actuator vs. a custom endpoint, and single vs. liveness/readiness groups | Chose Spring Boot Actuator with liveness and readiness groups, excluded from rate limiting | Implemented; verified live (70 requests all 200, absent from `/v3/api-docs`) |
| URL safety (validation, CRLF/header-injection handling) | Implemented the http/https-and-host check to the engineer's detailed prompt; separately verified `java.net.URI`'s behavior against raw vs. percent-encoded control characters before proposing a regression test | Engineer scoped validation deliberately narrow (scheme + host only, no internal/private-address blocking) as a documented boundary, and directed the CRLF test into the existing parameterized test rather than a new file | Implemented; 2 new regression cases added; the underscore/IDN host-validation gap remains open by the engineer's own choice, not fixed on the spot |
| Custom short codes | Identified that supporting them reintroduces 409 Conflict and needs an ownership model that doesn't exist | Decided not to implement; scope stays as documented | Rejected, recorded as a scope boundary |
| Soft delete / disable link | Identified that soft expiry already gives most of the same behavior, and that deletion would break the redirect cache's "no delete API" invariant | Decided not to implement | Rejected, recorded as a scope boundary |
| UI redesign | Proposed a card-based, dark-mode-aware redesign | Reviewed actual headless-browser screenshots in both themes before accepting | Accepted |
| Alternative considered: `CURRENT_TIMESTAMP` for `lastClickedAt` | Proposed relying on the database clock via JPQL | Rejected after it failed at runtime; replaced with an explicit `Instant.now()` parameter | Fixed and verified by a test run |

## Quality Gates

| Gate | Evidence | Result | Release implication |
|---|---|---|---|
| Build | `./gradlew clean build` | BUILD SUCCESSFUL | Required |
| Unit and integration tests | `./gradlew test` | 132 passed, 0 failed, 0 errors | Required |
| Targeted collision regression | `ShortCodeCollisionIntegrationTest` | 3 passed, 0 failed | Required |
| Targeted rate-limit config regression | `RateLimitConfigTest` | 7 passed, 0 failed | Required |
| Targeted expiration regression | `UrlExpirationIntegrationTest` | 10 passed, 0 failed | Required |
| Targeted error-semantics regression | `ErrorSemanticsIntegrationTest` | 4 passed, 0 failed | Required |
| Live smoke test | Create a link, follow the redirect, read analytics, check health | 201 creation; 302 redirect with correct Location; analytics shows `clickCount: 1` and a populated `lastClickedAt`; health returns UP | Required |
| Static analysis / lint | Not configured in this repository (no Checkstyle, SpotBugs, or PMD plugin) | Not run | Add before production release |
| Dependency vulnerability scan | Not run in this environment | Not run | Required in CI before production release |
| Load / performance test | Not run | Not run | Required when a traffic SLO is established |

## High-Impact Change Approval

The following changes require explicit engineer and reviewer approval before production rollout:

| Change | Why it is high impact | Required sign-off |
|---|---|---|
| Replace H2 in-memory with a persistent production database | Every link, click count, and analytics record is currently lost on restart; a persistent store changes the durability guarantee entirely | Application owner and database owner |
| Change the redirect from 302 to a cacheable status (e.g. 301) | Browser caching would stop every visit from reaching the service, breaking click counting | Product owner and application owner |
| Allow custom short codes | Reintroduces 409 Conflict and needs an ownership model that doesn't exist today; opens squatting risk without auth | Product owner and application owner |
| Relax URL validation to allow internal or private addresses | Currently a documented boundary; relaxing it opens intranet-navigation risk | Security reviewer and application owner |
| Enable the H2 console, or relax rate limiting, outside local development | Exposes a database browser, or reduces abuse protection, in a shared environment | Security reviewer and application owner |
| Add a delete or disable-link endpoint | Breaks the redirect cache's documented "no delete API" invariant; needs cache invalidation and an ownership model | Application owner and security reviewer |

## Secure AI Guardrails

- Do not include secrets, access tokens, or production data in prompts. This project's only credential-shaped value is the local H2 default, a well-known local-dev convention, not a secret, backing a database that is in-memory and never holds real user data.
- Treat AI-generated code and documentation as an untrusted draft until it compiles, passes the existing test suite, and is checked against the real code. Several documentation claims in this project were corrected only after being verified against actual test names, actual HTTP responses, and a real Hibernate runtime error.
- Require human review for scope decisions (custom codes, delete/disable, validation relaxation), reliability defaults (rate limits, async click counting), and anything that changes what data is retained or for how long.
- Record rejected alternatives and residual risks &mdash; including the still-open underscore/IDN host-validation gap &mdash; so reviewers can evaluate the decision, not only the final code.
