# Never use hato `:as :json` — use `json/read-str` instead

## The bug

Using `:as :json` with hato causes a NullPointerException in AOT-compiled
Clojure running in distroless containers:

```
Cannot invoke "clojure.lang.IFn.applyTo(clojure.lang.ISeq)" because "f" is null
```

## Root cause

From [hato's README](https://github.com/gnarroway/hato):

> Coerces JSON strings into clojure data structure.
> **Requires optional dependency cheshire** (5.9.0 or later)

`:as :json` is not self-contained. It dynamically resolves a JSON library at
runtime via `requiring-resolve`. The resolution order is:

1. **cheshire** (preferred) — `cheshire.core/parse-string`
2. **jsonista** — as fallback
3. If neither is found, the coercion function is **nil**

This project uses `clojure.data.json`, not cheshire. So:

- hato's `:as :json` coercion resolves to nil
- After AOT compilation, this nil is baked into the .class files
- At runtime in distroless, calling `nil.applyTo(args)` throws NPE

The same code may appear to work locally because:
- The REPL doesn't AOT-compile, so dynamic resolution happens lazily
- Error handling may mask the nil in some code paths
- The classpath layout differs from the distroless container

## The fix

Never use `:as :json`. Parse JSON manually with `clojure.data.json`:

```clojure
;; BAD — requires cheshire, NPE without it
(http/get url {:headers headers :as :json})

;; GOOD — uses data.json directly, works everywhere
(let [response (http/get url {:headers headers})
      body (json/read-str (:body response) :key-fn keyword)]
  body)
```

## Why not just add cheshire?

You could, but:

1. **Hidden dependency** — `:as :json` silently fails without cheshire.
   No compile error, no warning. Just a nil that explodes at runtime.
2. **Extra dependency** — cheshire pulls in Jackson, adding ~2MB to your JAR.
   `clojure.data.json` is pure Clojure, already on your classpath.
3. **Explicit is better** — `json/read-str` is one line. You can see exactly
   what's happening. No dynamic resolution, no optional deps, no surprises.

## What about `:as :transit+json`, `:as :clojure`, etc.?

Same pattern. From hato's README:

> `:as :transit+json`, `:as :transit+msgpack` — Requires optional dependency
> `com.cognitect/transit-clj`

All coercion options beyond `:string`, `:byte-array`, and `:stream` use
dynamic resolution of optional dependencies. Avoid them in AOT-compiled
code unless you're certain the optional dep is on the classpath.

Safe options: `:string` (default), `:byte-array`, `:stream`.

## Timeline

- **2026-03-27**: Discovered in video-publisher Cloud Run Job (Firebase backup).
  `http/get` without `:as :json` worked. `http/get` with `:as :json` threw NPE.
  Fix: remove `:as :json`, parse with `json/read-str`. Cloud Run Job exit(0).
