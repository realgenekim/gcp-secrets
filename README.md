# gcp-secrets

Clojure library for accessing Google Cloud Secret Manager with multiple authentication methods.

## Usage

```clojure
(require '[gcp-secrets.main :as gcpsec])

;; Fetch a secret (uses default project-id "booktracker-1208")
(gcpsec/get-secret! "postgres")

;; Fetch with explicit project-id
(gcpsec/get-secret! "my-secret" "my-project-id")
```

## Authentication / Token Retrieval Order

`get-secret!` fetches secrets via the Secret Manager REST API. To call that API, it needs an auth token. There are two layers happening:

- **Layer 1 — Authenticate to GCP**: Get an OAuth2 token that proves "I am this service account"
- **Layer 2 — Fetch the secret**: Use that token to call `https://secretmanager.googleapis.com/v1/projects/.../secrets/.../versions/latest:access`

For Layer 1, it tries these methods in order:

1. **ADC (Application Default Credentials)** — Reads `~/.config/gcloud/application_default_credentials.json`
   (or path in `GOOGLE_APPLICATION_CREDENTIALS` env var) and exchanges the refresh token for an access token.
   Best for Docker containers with mounted gcloud config.

2. **gcloud CLI (shell)** — Runs `gcloud auth print-access-token`. Works locally when gcloud is installed.

3. **Metadata server** — Fetches token from `http://metadata.google.internal/...`.
   Works in GCP compute environments (Cloud Run, GCE, etc.). Returns a token in ~1ms.

If the HTTP/API approach fails entirely, falls back to:

4. **gcloud CLI `secrets versions access`** — Directly fetches the secret value via gcloud command (bypasses both layers).

**On Cloud Run, both layers are automatic.** The metadata server provides the token (#3), and the service account just needs the `roles/secretmanager.secretAccessor` IAM role. No env vars, no mounted files, no config.

**Locally**, it typically uses #2 (gcloud CLI) — whatever identity you're logged in as via `gcloud auth login`.

## When to Use gcp-secrets (and When NOT To)

gcp-secrets is for fetching **application secrets** (passwords, API keys, DB credentials) from Google Secret Manager. It is NOT needed for authenticating to GCP APIs like GCS, BigQuery, or Pub/Sub — those use ADC automatically.

| Need | Use gcp-secrets? | Instead use |
|------|-------------------|-------------|
| Dashboard password | Yes | `(gcpsec/get-secret! "my-password" "my-project")` |
| DB username/password | Yes | `(gcpsec/get-secret! "postgres" "my-project")` |
| Third-party API keys | Yes | `(gcpsec/get-secret! "stripe-key" "my-project")` |
| GCS bucket access | No | ADC — just `(storage/init {:project-id "..."})` |
| BigQuery access | No | ADC handles it automatically |
| Google Sheets access | No — use SA key file | Mount via `--set-secrets` + `GOOGLE_APPLICATION_CREDENTIALS` |

### The key insight

On Cloud Run, gcp-secrets works with **zero configuration**. Cloud Run's metadata server provides the auth token to call Secret Manager. No env vars, no mounted files, no `--set-secrets` needed in your deploy command.

```clojure
;; This just works on Cloud Run — no setup needed
(def dashboard-pass
  (try (gcpsec/get-secret! "sched-dashboard-pass" "does2020")
       (catch Exception _ nil)))
```

This is cleaner than the `--set-secrets` / `--set-env-vars` approach because the secret never appears in deploy commands, Makefiles, or environment variable listings.

## Common Patterns

### Pattern 1: Simple secret at startup (dashboard passwords, API keys)

```clojure
;; Load once at namespace load time. On Cloud Run, ADC handles auth.
;; Locally, falls back to gcloud CLI.
(def dashboard-pass
  (try (gcpsec/get-secret! "sched-dashboard-pass" "does2020")
       (catch Exception _ nil)))
```

### Pattern 2: Lazy loading with delay (secrets that might not be needed)

```clojure
;; From reddit-scraper/reddit_api.clj
;; Defers Secret Manager call until first use
(def secrets-delay
  (delay
    (if (gcpsec/use-local-keys?)
      (read-string (slurp "secrets/reddit-secrets.edn"))
      (gcpsec/get-secret! "reddit"))))

(defn get-client [] (init-client @secrets-delay))
```

### Pattern 3: Async loading with future (always-needed secrets, faster startup)

```clojure
;; From reddit-scraper/mongodb.clj
;; Starts loading immediately on background thread
(defonce config-future
  (future
    (if (gcpsec/use-local-keys?)
      (read-string (slurp "secrets/mongodb-secrets.edn"))
      (gcpsec/get-secret! "mongodb"))))

(defn get-uri [] (:uri @config-future))
```

### When to use which

| Situation | Pattern | Why |
|-----------|---------|-----|
| Password/key, always needed, fast | Direct `def` | Simplest, ~200-500ms at startup |
| Secret might not be used | `delay` | Don't pay for what you don't use |
| Always needed, slow startup matters | `future` | Loads in background, doesn't block |

## Docker Usage

Mount your local gcloud credentials into the container:

```bash
docker run -v ~/.config/gcloud:/root/.config/gcloud \
  your-image:latest [args]
```

This gives the container access to your ADC credentials without needing gcloud CLI installed in the image.

**Prerequisites:** Run `gcloud auth application-default login` once locally to create the credentials file.

## Breaking Changes (since 500a8ba)

### API Changes

| Function | Change | Risk |
|----------|--------|------|
| `get-secret!` | Added optional `project-id` arity | **None** — single-arg call unchanged, defaults to `booktracker-1208` |
| `get-secret-http!` | Now takes `[secret-name project-id]` | **None** — now private (`defn-`), not part of public API |
| `get-secret-gcloud!` | Unchanged signature | **None** — now private (`defn-`) |
| `get-secret-local-file` | New function | **None** — private (`defn-`) |

### Behavior Changes

| Behavior | Before | After | Risk |
|----------|--------|-------|------|
| Token retrieval | shell → metadata server | ADC → shell → metadata server | **Low** — ADC is tried first but falls back to old behavior if it fails |
| Local file loading | Not done | Not done (was briefly added then removed) | **None** |
| Project ID | Hardcoded `booktracker-1208` | Defaults to `booktracker-1208`, overridable | **None** |

### Why These Changes

The primary motivation was enabling Docker containers to fetch secrets without requiring
`gcloud` CLI installed in the image. Previously, running locally in Docker would fail with
`Cannot run program "gcloud": error=2, No such file or directory`.

ADC support solves this by reading the standard Google Cloud credentials file, which can
be volume-mounted into any container.

## Public API

| Function | Purpose |
|----------|---------|
| `get-secret!` | Main entry point — fetch secret from Secret Manager |
| `use-local-keys?` | Check if `USE_LOCAL_KEYFILES` env var is set |
| `running-in-cloud-run?` | Detect Cloud Run via `K_SERVICE` env var |
| `get-token` | Get access token (ADC → shell → metadata) |
| `get-access-token-adc` | Get token specifically via ADC |

## Environment Detection

```clojure
(gcpsec/running-in-cloud-run?)  ; true if K_SERVICE is set
(gcpsec/use-local-keys?)        ; truthy if USE_LOCAL_KEYFILES is set
```

Consumers typically use `use-local-keys?` to decide whether to read local files
or call `get-secret!`:

```clojure
(if (gcpsec/use-local-keys?)
  (read-string (slurp "secrets/postgres.edn"))
  (gcpsec/get-secret! "postgres"))
```
