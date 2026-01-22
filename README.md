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

`get-secret!` fetches secrets via the Secret Manager REST API. To authenticate,
it tries these methods in order:

1. **ADC (Application Default Credentials)** — Reads `~/.config/gcloud/application_default_credentials.json`
   (or path in `GOOGLE_APPLICATION_CREDENTIALS` env var) and exchanges the refresh token for an access token.
   Best for Docker containers with mounted gcloud config.

2. **gcloud CLI (shell)** — Runs `gcloud auth print-access-token`. Works locally when gcloud is installed.

3. **Metadata server** — Fetches token from `http://metadata.google.internal/...`.
   Works in GCP compute environments (Cloud Run, GCE, etc.)

If the HTTP/API approach fails entirely, falls back to:

4. **gcloud CLI `secrets versions access`** — Directly fetches the secret value via gcloud command.

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
