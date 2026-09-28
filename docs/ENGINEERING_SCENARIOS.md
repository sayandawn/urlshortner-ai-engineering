# Engineering Scenarios

These scenarios show how the same execution discipline &mdash; inspect the owning code path, establish a test baseline, make the smallest change that satisfies the requirement, then run the full regression suite &mdash; is applied when requirements and existing code differ. See [ARCHITECTURE.md](ARCHITECTURE.md) for the full design.

## Greenfield: Build A Public URL Shortener

### Decomposition

- Define the endpoint lifecycle: create a short link, redirect through it, and resolve it without redirecting.
- Identify the data entity and its invariants: a unique short code, the original URL, and a creation time.
- Choose a code strategy independent of the database id, so codes don't leak record counts or become guessable.
- Decide the shape of an error before writing the first exception: one consistent format for invalid requests and unknown codes, not ad hoc per-endpoint messages.
- Split the work into logical services (Shortener, Redirect, Analytics) that never call each other, so each can be extracted into its own deployable later without a rewrite.
- Define acceptance tests around HTTP status codes and the shape of each response, not just the happy path.

### Execution

- **Contract:** Spring Web MVC gives a compact controller-per-service contract.
- **Persistence:** Spring Data JPA over an in-memory H2 database keeps the prototype dependency-free.
- **Code generation:** `ShortCodeGenerator` produces a seven-character Base62 code from `SecureRandom`, independent of the database id.
- **URL validation:** intentionally narrow &mdash; `java.net.URI` checks for a well-formed http/https URL with a host, nothing more; rejecting internal or private addresses is explicitly out of scope.
- **Layering:** business rules live inside `ShortenerService` and `RedirectService`, so controllers stay thin and testable.

### Validation

- Unit tests cover code generation, URL validation, and each service's business rules in isolation.
- `@SpringBootTest` + MockMvc integration tests exercise the full HTTP contract end to end: create a link, follow the redirect, resolve it without redirecting, and assert the response shape and status code match the contract.

## Brownfield: Harden Collision Safety, Analytics, Expiration, And Error Semantics

### Decomposition

- Decide what "invalid" means before writing the check: reject missing, malformed, and unsupported-scheme URLs consistently.
- Locate the exact seam each hardening change lands on: collisions inside `ShortenerService`, click counting on the redirect path, expiry checked on both the redirect and lookup paths, and every error case behind one shared handler &mdash; none of them touching each other's code.
- Decide the authority for uniqueness once concurrent creates are possible: the database's unique constraint, not a check-then-insert race, with a bounded retry by constraint name.
- Decide what must never block or fail the redirect: click counting runs asynchronously off the redirect thread, so analytics can't slow down or break the one path users actually feel.
- Decide what "expired" means without deleting data: a link past its expiry returns 410 and keeps its row and its stats, so analytics and audits still work.
- Decide the one shared shape every error returns through, so four unrelated hardening changes don't produce four different error formats.
- Add regression coverage for each hardening change, and re-run the full existing suite after every one, so a fix for one doesn't silently break a feature shipped an increment earlier.

### Execution

- **Collision safety:** `ShortenerService` retries on a `uk_url_mapping_short_code` violation, up to 5 attempts, recognized by constraint name &mdash; not a check-then-insert race.
- **Analytics:** `AsyncClickRecorder` counts clicks off the redirect thread on a dedicated executor, with a bounded queue and retry for temporary failures.
- **Expiration:** `UrlMapping.isExpired()` returns 410 on both the redirect and lookup paths, without deleting the row.
- **Error semantics:** one `GlobalExceptionHandler` maps every case &mdash; validation, not-found, expired, rate-limited, generation-exhausted &mdash; to the same ProblemDetail shape.

### Validation

- `ShortCodeCollisionIntegrationTest` &mdash; a real concurrent collision, not a mocked one.
- `AsyncClickCountingIntegrationTest` / `ClickCountQueueFullIntegrationTest` &mdash; eventual consistency, retry, dropping under a full queue.
- `UrlExpirationIntegrationTest` &mdash; a 410 stops the redirect but leaves the row and its stats queryable.
- `ErrorSemanticsIntegrationTest` &mdash; every case returns the same shape.
- The full existing suite re-run after each of the five increments, not just once at the end.

## Ambiguous: Choose A Reliability Feature For The Public Endpoints

### Ambiguity

The requirement asked for "appropriate reliability features" without naming one. Retry, circuit breaking, health checks, and rate limiting all satisfy that phrase equally well on paper, and each protects against a different failure mode &mdash; choosing wrong here isn't a bug, it's solving the wrong problem.

### Decomposition

- Decompose "reliability" into concrete candidate features and what each actually protects against.
- Match the choice to this service's real exposure: an unauthenticated public endpoint has no protection from traffic volume today.
- Decide the failure mode the chosen feature must never create: a rejected request must never reach the redirect cache, the database, or click counting.
- Make the limit configurable per endpoint group rather than one global number, since create, redirect, lookup, and analytics have different expected traffic shapes.
- Make invalid configuration a startup failure, not a silent runtime gap.

### Execution

- **Mechanism:** Resilience4j `RateLimiter`, one global in-memory limiter per endpoint group (`create`, `redirect`, `lookup`, `analytics`).
- **Enforcement point:** a `HandlerInterceptor` that runs after routing and before the controller, so a rejected request (429 + `Retry-After`) never reaches business logic.
- **Configuration:** per-group and startup-validated &mdash; missing or invalid limits for any group fail the application before it serves traffic.

### Validation

- `RateLimitConfigTest` &mdash; rejects invalid startup configuration before the app ever accepts a request.
- `RateLimitIntegrationTest` &mdash; sets small limits and asserts the 429 contract, the `Retry-After` header, and that a rejected request never increments `clickCount`.
- The general test suite raises all limits so unrelated tests aren't throttled by it.
- A future change to these defaults should be reviewed as a capacity decision, not a cosmetic configuration edit.
