# URL Shortener

A URL shortener API built with Spring Boot, Spring Data JPA, and H2. It provides link creation with optional expiry, safe redirects, a lookup endpoint for clients that can't follow cross-origin redirects, and click analytics.

Related deliverables:
- [Architecture overview](docs/ARCHITECTURE.md)
- [Engineering scenarios](docs/ENGINEERING_SCENARIOS.md)
- [Engineering execution record](docs/ENGINEERING_EXECUTION_RECORD.md)
- [Engineering summary](docs/ENGINEERING_SUMMARY.md)

## Deliverables Checklist

| Requested deliverable | Where to find it |
|---|---|
| Working prototype, runnable end-to-end | [docs/SETUP.md](docs/SETUP.md) (Local Setup) and [End-To-End Walkthrough](#end-to-end-walkthrough) below |
| Architecture: components, tools, approach, flow, decisions | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Greenfield, brownfield, and ambiguous scenarios | [docs/ENGINEERING_SCENARIOS.md](docs/ENGINEERING_SCENARIOS.md) |
| Setup instructions | [docs/SETUP.md](docs/SETUP.md) (Local Setup and Production Setup) |
| Testing approach, limitations, and trade-offs | Testing, Limitations, And Trade-Offs below and [docs/ENGINEERING_SUMMARY.md](docs/ENGINEERING_SUMMARY.md) |

## Setup

See [docs/SETUP.md](docs/SETUP.md) for local development and standalone-container production setup, step by step.

## End-To-End Walkthrough

Start the app first (see [docs/SETUP.md](docs/SETUP.md)), then either use the built-in manual test page or Swagger UI.

**Manual test page** (the easiest way to try it): open **http://localhost:8080/**. It has three forms &mdash; shorten a URL (with an optional expiry), go to the original URL by short code or short URL, and view analytics &mdash; each showing its result inline.

**Or drive it through Swagger UI** (http://localhost:8080/swagger-ui.html) **or `curl`.** Here's an actual run against `https://example.com`, with real captured responses:

**1. Shorten it:**
```
POST /api/urls
{ "url": "https://example.com" }
```
Response:
```json
{"shortCode":"H288MoT","shortUrl":"http://localhost:8080/H288MoT","originalUrl":"https://example.com"}
```
Codes are always generated &mdash; there's no way to request a specific one.

**2. Look it up without redirecting** (`GET /api/urls/{shortCode}`) &mdash; useful because Swagger UI can't follow a cross-origin redirect itself:
```json
{"shortCode":"H288MoT","originalUrl":"https://example.com"}
```

**3. Follow the actual redirect** (`GET /{shortCode}`):
```
HTTP/1.1 302
Location: https://example.com
```
This is the only request that counts as a click.

**4. Check analytics** (`GET /api/urls/{shortCode}/analytics`), right after the redirect above:
```json
{"shortCode":"H288MoT","originalUrl":"https://example.com","clickCount":1,"createdAt":"2026-09-27T19:46:35.298917Z","expiresAt":null,"expired":false,"lastClickedAt":"2026-09-27T19:46:42.354857Z"}
```
`clickCount` is `1` and `lastClickedAt` is populated &mdash; the redirect from step 3 was counted. This can lag slightly since click counting is asynchronous.

## API

| Method | Route | Description |
|---|---|---|
| POST | `/api/urls` | Create a short link (`url`, optional `expiresAt`). Returns 201, or 400 for an invalid/unsupported URL or a missing/malformed body. |
| GET | `/{shortCode}` | Redirect to the target URL (302) and record a click. 404 unknown, 410 expired. |
| GET | `/api/urls/{shortCode}` | Same lookup and expiry rules as the redirect, but returns the original URL as JSON without redirecting or counting a click. |
| GET | `/api/urls/{shortCode}/analytics` | Click count, target URL, creation/expiration/last-click times. Expired codes still return their stats. |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Liveness and readiness. |

Every endpoint above except the health checks is rate-limited per endpoint group (service-level, not per-client); a rejected request returns `429` with `Retry-After`.

## Testing, Limitations, And Trade-Offs

- 132 tests: unit tests for code generation, URL validation, and each service's business rules in isolation; repository tests (`@DataJpaTest`) for the unique constraint and atomic click increment; `@SpringBootTest` + MockMvc integration tests for the full HTTP contract, including a real concurrent short-code collision, async click counting under retry and a full queue, expiration, and rate limiting.
- Click analytics is asynchronous and intentionally best-effort: a bounded in-memory queue absorbs temporary database failures with retry, but drops and logs a click if the queue is ever full, to keep the redirect path from ever waiting on the analytics write. There are no queue-drop metrics yet &mdash; add them before relying on click counts for anything contractual.
- The service is anonymous end to end. There's no authentication, no link ownership, and no per-client rate limiting &mdash; rate limits are global per endpoint group, not per user or per IP.
- URL validation checks only the scheme (`http`/`https`) and that a host is present. It does **not** reject internal or private addresses (`localhost`, `10.x.x.x`, cloud metadata IPs); a deployment that needs to prevent that kind of intranet navigation needs a DNS/egress policy or a destination allowlist outside this service.
- A related, still-open gap: valid hostnames that `java.net.URI` can't parse &mdash; ones with underscores, or non-ASCII/IDN hostnames like `münchen.de` &mdash; are rejected with 400. Deliberately deferred, not yet fixed.
- Local development uses H2 in-memory with `ddl-auto=update` (no migrations, since there's no persistent schema to protect). The `prod` profile uses a persistent, file-based H2 with Flyway-managed migrations instead &mdash; see [docs/SETUP.md](docs/SETUP.md).

## Design Notes

- **Codes:** cryptographically random Base62, 7 characters, generated via `SecureRandom`, independent of the database id. Uniqueness is enforced by a database unique constraint (not a check-then-insert race), with a bounded retry (5 attempts) on the rare collision. There's no way for a caller to choose a code.
- **Analytics:** redirects hand a click to a dedicated async executor with a bounded queue; a worker thread updates the count. Redirects never block on, or fail because of, the analytics write. There are no per-click event records &mdash; `clickCount` is a single aggregate counter per link, and no IP address or user agent is ever stored.
- **Expiry:** soft &mdash; an expired link returns `410 Gone` on redirect and lookup, but its row and click stats stay queryable via analytics. Nothing is ever deleted.
- **Reliability:** service-level (global, not per-client) rate limiting per endpoint group via Resilience4j, checked before the controller runs so a rejected request never reaches business logic, the redirect cache, the database, or click counting; RFC 9457 `ProblemDetail` errors for every failure case; a cached redirect lookup (immutable value, excludes `clickCount`, expiry still checked on every request); liveness/readiness health checks.
- **Safety:** only absolute `http`/`https` targets are accepted. Internal/private address targets are **not** rejected today (see Limitations above) &mdash; this is a known, documented gap, not a design choice.
- Redirects use `302 Found` (not `301`) specifically so every visit reaches the service and gets counted; there is currently no `Cache-Control` header on the response, which is a separate, already-tracked gap.

## Configuration

| Key | Default | Purpose |
|---|---|---|
| `app.rate-limit.limits.<group>.limit-for-period` / `.limit-refresh-period` / `.timeout` | `create` 10/min, `redirect`/`lookup` 60/min, `analytics` 30/min | Per-group rate limits. Missing or invalid config for any group fails startup. |
| `app.click-counting.pool-size` / `.queue-capacity` / `.retry.*` | 2 threads, queue 10,000, 5 retry attempts | Async click-counting executor and retry policy. |
| `spring.h2.console.enabled` | `true` locally, `false` in `prod` | H2 web console exposure. |
| `DB_PATH` *(prod profile only)* | none, required | Path to the file-based H2 database, inside a mounted volume. |
| `DB_USERNAME` / `DB_PASSWORD` *(prod profile only)* | none, required, no fallback | Database credential. Startup fails immediately if either is missing &mdash; see [docs/SETUP.md](docs/SETUP.md) for how this actually works. |

Flyway migrations are tracked in `src/main/resources/db/migration/` and only run under the `prod` profile; local development keeps `ddl-auto=update` against the in-memory database.
