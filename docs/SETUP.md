# Setup Instructions

This covers both running the app on your own machine for development, and running it as a standalone production container. They use different profiles and different databases &mdash; see the comparison table at the start of the Production section.

# Local Setup

Matches the current H2-in-memory, no-auth prototype configuration.

## Steps

**1. Prerequisites:**
- **JDK 17+** to run Gradle. Java 21 (what the app targets) doesn't need to be pre-installed &mdash; Gradle downloads it automatically on first run (~180 MB, needs network access).
- `git`, to clone the repository.
- No database to install &mdash; H2 runs in-memory, embedded with the app.

**2. Get the code and run it:**
```
git clone <repository-url>
cd urlshortner-ai-engineering
./gradlew bootRun
```
The first run is slower than usual while Gradle downloads its own distribution and the JDK 21 toolchain. Once it's up, you'll see:
```
Started UrlshortnerAiEngineeringApplication in ... seconds
```
The app is now listening on **http://localhost:8080**.

**3. Verify it's working:**
```
curl http://localhost:8080/actuator/health
```
Expect `{"groups":["liveness","readiness"],"status":"UP"}`. Or open these in a browser:

| Check | URL |
|---|---|
| Manual test page | http://localhost:8080/ |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |

**4. Stop the app** when you're done (only needed if you started it in the background &mdash; `bootRun` runs in the foreground otherwise):
```
pkill -f bootRun
```

---

The rest of this section is reference material &mdash; inspecting the database, configuration, and troubleshooting. You don't need to read it to follow the steps above.

## Inspecting The Database

H2 console is enabled for local development:

- **URL:** http://localhost:8080/h2-console
- **JDBC URL:** `jdbc:h2:mem:urlshortener`

Data only exists while the app is running &mdash; it's in-memory, so it's gone after a restart.

## Configuration

All runtime configuration lives in `src/main/resources/application.properties` &mdash; rate limits per endpoint group, async click-counting (pool size, queue capacity, retry), and health-check exposure. Change a value there and pick it up with `./gradlew processResources` (no restart needed). Invalid rate-limit configuration fails startup on purpose, so a typo there will surface immediately rather than silently.

For tests, `src/test/resources/config/application.properties` overrides rate limits (raised) and click counting (synchronous), so the test suite stays deterministic without needing to wait on async behavior.

## Troubleshooting

- **Port 8080 already in use:** something else is already running (maybe an earlier `bootRun` you forgot about). `pkill -f bootRun` first, or find and stop whatever's on that port.
- **First run is slow / seems stuck:** likely the one-time Gradle distribution and JDK 21 toolchain download. Subsequent runs are fast.
- **A code change isn't showing up:** the running app won't notice a file change on its own &mdash; stop it and run `./gradlew bootRun` again to pick it up.

# Production Setup: Standalone Container

Running the app as one standalone Docker image with **no external database service** &mdash; a persistent, file-based H2 database on a mounted volume, migrated by Flyway.

This is one of two options considered; see ENGINEERING_EXECUTION_RECORD.md's High-Impact Change Approval table &mdash; replacing the in-memory database is listed there as needing application-owner and database-owner sign-off before a real rollout.

## Steps

**1. Prerequisites:** Docker installed and running.

**2. Choose a username and password.** These values are defined by the operator, not retrieved from any existing source. Once chosen, they should be stored for reuse: a plain environment variable is sufficient for local or simple use; a production deployment should store them in a secret manager instead (e.g. a Kubernetes `Secret`, AWS Secrets Manager, or HashiCorp Vault). These values are not used until step 4, where they are passed into the `docker run` command. They are not embedded in the image built in step 3 &mdash; the image is generic and has no knowledge of them. The database file path and volume name shown in the command below may be used as-is; Docker and the application create the required paths automatically, with no manual setup required.

**3. Build the image** (no Dockerfile needed &mdash; the Spring Boot Gradle plugin provides this):
```
./gradlew bootBuildImage --imageName=urlshortener:latest
```

**4. Run the container**, filling in the username/password from step 2 (the rest can stay as shown):
```
docker run -d \
  -p 8080:8080 \
  -v urlshortener-data:/var/lib/urlshortener \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_PATH=/var/lib/urlshortener/urlshortener \
  -e DB_USERNAME=<your-own-username> \
  -e DB_PASSWORD=<your-own-password> \
  urlshortener:latest
```

**5. Verify it's up:**
```
curl http://localhost:8080/actuator/health
```
Expect `{"groups":["liveness","readiness"],"status":"UP"}`. Then create a link (`POST /api/urls`), note its `shortCode`, and check its analytics &mdash; the same way as Local Setup's "Verify It's Working."

**6. Confirm the actual point of this setup &mdash; data surviving a restart:**
```
docker restart <container-id-or-name>
```
Check that same `shortCode`'s analytics again. The `clickCount` and timestamps should be unchanged from before the restart. If they're not, something's wrong with the volume mount, not just a config detail to shrug off.

**On every later restart or redeploy against the same volume**, use the exact same `DB_USERNAME`/`DB_PASSWORD` from step 2 &mdash; not new ones. That's the one thing that must stay consistent; everything else in the `docker run` command can change freely.

---

The rest of this section is reference material &mdash; what's different from local dev, why the database credential works the way it does, and what was and wasn't verified. You don't need to read it to follow the steps above.

## What Changes From Local Dev

| | Local dev | Production profile |
|---|---|---|
| Database | H2 in-memory, cleared on every restart | H2 **file-based**, on a mounted volume, survives restarts |
| Schema | `ddl-auto=update` (Hibernate auto-manages it) | Flyway migration (`src/main/resources/db/migration/V1__init.sql`) + `ddl-auto=validate` (Hibernate only checks) |
| H2 console | Enabled | Disabled |
| Proxy awareness | Not configured | `server.forward-headers-strategy=framework`, so `shortUrl` uses the host/scheme your reverse proxy saw |

Activated by `SPRING_PROFILES_ACTIVE=prod`, which loads `application-prod.properties` on top of the defaults.

## A Real Constraint Of This Option, Not A Bug

File-based H2 allows only one process to hold the file at a time &mdash; this setup is single-instance by design, not something to scale into. Multiple replicas need the external-database alternative instead.

# Where To Go Next

- [ARCHITECTURE.md](ARCHITECTURE.md) &mdash; full design: scope, components, request flow, data model, key decisions.
- [ENGINEERING_SCENARIOS.md](ENGINEERING_SCENARIOS.md) &mdash; how greenfield, brownfield, and ambiguous requirements were each approached.
- [ENGINEERING_EXECUTION_RECORD.md](ENGINEERING_EXECUTION_RECORD.md) &mdash; requirement traceability, AI-assisted work log, and quality gates.
