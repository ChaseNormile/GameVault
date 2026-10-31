# GameVault

A portfolio video game collection application with development scaffolding, a backend connectivity check, and a read-only IGDB game search API. User authentication and collection features are deferred.

## Prerequisites

- Java **21** (set `JAVA_HOME` to your JDK installation)
- Node.js **22.12+** and npm
- Docker with Docker Compose v2 (enable Docker Desktop WSL integration if using WSL)
- Maven is downloaded automatically by the committed Maven wrapper on first use.

## Local setup

From the repository root:

```bash
cp .env.example .env
```

Edit `.env` and set `POSTGRES_PASSWORD` to your own local password. Use a simple alphanumeric value to avoid differences between Compose and Java properties parsing. Do not commit `.env`. The database name and username default to `gamevault`; the host port defaults to **5433**. The container uses port 5432 internally.

```bash
docker compose up -d --wait
```

In a second terminal, start the backend:

```bash
cd backend
./mvnw spring-boot:run
```

On Windows Command Prompt use `mvnw.cmd` instead of `./mvnw`. The backend imports the root `.env` automatically when launched from `backend/` or the repository root; exported environment variables take precedence.

In a third terminal, start the frontend:

```bash
cd frontend
cp .env.example .env
npm ci
npm run dev
```

Open **http://localhost:5173**. The page displays “GameVault” and reports whether the backend responds successfully. Refresh to check again after starting or stopping the backend.

```bash
curl http://localhost:8080/api/health
# {"status":"ok"}
```

The endpoint indicates API availability; it does not query the database. The full backend still needs PostgreSQL during startup because JPA configures a datasource.

## Verification

```bash
cd backend
./mvnw clean verify
```

This builds the executable JAR and runs MVC tests for the exact health response, the allowed development origin, and rejection of another origin. These tests use Spring's MVC slice with MockMvc, so no running database is required.

```bash
cd frontend
npm ci
npm run build
```

The frontend build checks TypeScript and creates production assets in `frontend/dist/`. There is no frontend test suite yet. `npm run preview` serves those assets locally; the backend must still be running.

## Structure and decisions

```text
backend/
  pom.xml                        Spring Boot dependencies and Java 21 target
  mvnw, mvnw.cmd, .mvn/          Pinned Maven wrapper
  src/main/java/com/gamevault/
    GameVaultApplication.java    Application entry point and component scan root
    config/WebConfig.java        Configurable development CORS
    health/HealthController.java HTTP health endpoint and response record
  src/main/resources/            Application configuration
  src/test/java/                 MVC endpoint tests
frontend/
  src/App.tsx                    Health request and connection status
  src/main.tsx                   React entry point
  src/index.css                  Minimal page styling
  vite.config.ts                React plugin and fixed development port
compose.yaml                     Local PostgreSQL and named data volume
.env.example                     Shared database configuration template
```

- Spring Boot 3.5 manages Spring Web, JPA, Validation, PostgreSQL JDBC, and test dependency versions. Maven targets Java 21. There are no entities or repositories yet.
- The health controller owns its small response record. A service or repository layer would add no value for this static response; those layers can be added with actual business logic.
- React, TypeScript, and Vite run separately from Spring Boot for quick development feedback. The npm lockfile pins resolved dependencies.
- Browser requests go directly to the backend. CORS allows `GET /api/**` from `http://localhost:5173` without credentials. Vite uses a strict port to prevent an unexpected origin change. This is local development configuration, not a production hosting setup.
- Hibernate schema generation is disabled (`ddl-auto=none`) and Open Session in View is disabled. Schema migrations can be introduced when persistent application data is added.
- PostgreSQL uses a named volume so data survives container recreation. Its published port binds only to `127.0.0.1`.

## Configuration and troubleshooting

The root `.env` supports `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `POSTGRES_PORT`. For a nonlocal database, set `DB_HOST` in the backend environment. If changing the frontend origin, set `FRONTEND_ORIGIN` for the backend and update the Vite configuration as needed. `frontend/.env` supports `VITE_API_BASE_URL`; restart Vite after editing it. Vite variables are public browser configuration: never put secrets there.

If port 5433 is busy, change `POSTGRES_PORT` in the root `.env` and recreate the container with `docker compose up -d --wait`. Both Compose and Spring read the same port value.

```bash
docker compose ps
docker compose logs postgres
docker compose down
```

`down` preserves database data. PostgreSQL initialization variables only apply to an empty volume; changing the password in `.env` does not change an existing database user's password. To intentionally reset all local database data, use `docker compose down -v` and start again. **This deletes the stored database.**


## Read-only IGDB search

Register a confidential Twitch application and obtain its client ID and secret, following the [IGDB setup instructions](https://api-docs.igdb.com/#account-creation). Add these to your ignored root `.env`, or export them into the backend process environment:

```dotenv
IGDB_CLIENT_ID=
IGDB_CLIENT_SECRET=
```

Fill in your own values and restart the backend. Keep both values on the backend; never add them to a `VITE_` variable. No credentials are needed to run the mocked tests or health endpoint. Missing or rejected credentials make search return a safe 502 response.

Start PostgreSQL and the backend as described above, then run:

```bash
curl --get 'http://localhost:8080/api/games/search' --data-urlencode 'q=Skyrim'
```

The response is an array of at most ten games. An illustrative result is:

```json
[
  {
    "id": 1942,
    "name": "The Elder Scrolls V: Skyrim",
    "platforms": ["PC (Microsoft Windows)"],
    "coverUrl": "https://images.igdb.com/igdb/image/upload/t_thumb/example.jpg",
    "firstReleaseDate": 1320969600
  }
]
```

No matches return `[]`. Missing platforms become `[]`; missing covers and release times become `null`. Release timestamps are Unix seconds, not JavaScript milliseconds. Protocol-relative IGDB cover URLs are normalized to HTTPS.

Missing, blank, overlong (more than 200 characters), or control-character queries return HTTP 400 with `{"code":"INVALID_SEARCH_QUERY","message":"..."}`. Surrounding whitespace is removed. Authentication, upstream HTTP errors, invalid responses, and network failures return HTTP 502 with `{"code":"GAME_SEARCH_UNAVAILABLE","message":"Game search is temporarily unavailable. Please try again later."}`. Upstream error bodies and credentials are never returned.

### New backend classes

- `games/GameSearchController`: validates `q`, trims surrounding whitespace, and delegates the GET request to the integration service.
- `games/GameSearchResult`: public response DTO with camelCase JSON properties and platform names suitable for React.
- `games/GameSearchExceptionHandler`: maps validation and integration failures into predictable JSON errors. Its nested `ApiError` record defines that error DTO.
- `igdb/IgdbConfig`: configures a synchronous Spring `RestClient` with a five-second connection timeout and ten-second read timeout, plus a UTC clock for token expiry.
- `igdb/TwitchTokenProvider`: sends form-encoded client credentials to Twitch and caches the access token in memory. Its nested `TokenResponse` record maps Twitch JSON. A synchronized refresh prevents simultaneous requests from acquiring duplicate tokens. Tokens refresh 60 seconds early (or halfway through very short lifetimes).
- `igdb/IgdbSearchService`: escapes backslashes and quotes in the search term, requests the five required fields with a fixed limit of ten, and maps IGDB results. A 401 invalidates the rejected token and retries once with a fresh token; other failures are not retried.
- `igdb/IgdbGame`: upstream DTO with nested `Platform` and `Cover` records; maps IGDB's snake_case release field and optional metadata into the public DTO.
- `igdb/IgdbException`: a sanitized integration failure without an upstream response body or sensitive cause.

The request flow is controller → search service → token provider / IGDB → response DTO. Tokens live only in this backend process and are reacquired after restart. No database persistence, frontend changes, additional dependencies, or user login are involved. The existing CORS configuration already permits GET requests to `/api/**` from Vite.

### Search tests

```bash
cd backend
./mvnw -Dtest=GameSearchIntegrationTest test
# Or run all tests and build the executable JAR:
./mvnw clean verify
```

`GameSearchIntegrationTest` uses MockMvc with the real controller, service, token provider, and error handler, backed by `MockRestServiceServer`. It verifies outbound HTTP requests without contacting Twitch or IGDB. A controllable clock tests expiry without sleeping. Coverage includes result mapping, missing metadata, token reuse and refresh, 401 retry, query escaping, invalid queries, missing credentials, and sanitized HTTP/network/malformed-response failures. No PostgreSQL or real IGDB credentials are required for these tests.

Protocol references: [IGDB API](https://api-docs.igdb.com/) and [Twitch client credentials](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#client-credentials-grant-flow).
