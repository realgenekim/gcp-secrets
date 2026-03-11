(ns gcp-secrets.main
  (:require
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
   [clojure.reflect :as r]
   [clojure.pprint :as pp]
   [clojure.data.json :as json]
   [logging.main :as glog]
   [hato.client :as http]
   [taoensso.timbre :as log])
  #_(:import (com.google.cloud.secretmanager.v1 Secret
                                                SecretManagerServiceClient
                                                ProjectName
                                                SecretVersionName
                                                AccessSecretVersionResponse)
             (org.apache.log4j Logger Level)))

(glog/configure-logging! glog/config)

(defn use-local-keys?
  " returns non-nil if USE_LOCAL_KEYFILES environment var is set "
  []
  (let [ret (System/getenv "USE_LOCAL_KEYFILES")]
    (log/warn ::use-local-keys? ret)
    ret))

(defn running-in-cloud-run?
  "Detect if running in Cloud Run by checking K_SERVICE env var.
   Returns true if in Cloud Run, false otherwise."
  []
  (some? (System/getenv "K_SERVICE")))

(defn- get-secret-local-file
  "Read secret from local file. Tries multiple paths:
   1. gcp-secrets/<name>.edn
   2. secrets/<name>.edn
   Returns parsed EDN or nil if not found.
   Private: not used in main fallback chain. Available for explicit opt-in."
  [secret-name]
  (let [paths [(str "gcp-secrets/" secret-name ".edn")
               (str "secrets/" secret-name ".edn")
               (str "gcp-secrets/" secret-name ".json")
               (str "secrets/" secret-name ".json")]]
    (some (fn [path]
            (try
              (let [content (slurp path)]
                (log/info ::get-secret-local-file :found path :secret-name secret-name)
                (if (.endsWith path ".json")
                  (json/read-str content :key-fn keyword)
                  (edn/read-string content)))
              (catch java.io.FileNotFoundException _
                nil)))
          paths)))

(comment
  (use-local-keys?)
  0)

; enable API
; https://console.cloud.google.com/apis/enableflow?apiid=cloudbuild.googleapis.com,secretmanager.googleapis.com&redirect=https:%2F%2Fcloud.google.com%2Fbuild%2Fdocs%2Fsecuring-builds%2Fuse-secrets&_ga=2.167458494.1413660334.1663728888-1943839537.1657917961&project=booktracker-1208

#_(defn get-secret!
    " input: nothing
    output: map of returned secret (parsed from edn string) "
    [secret-name]
    (let [client (SecretManagerServiceClient/create)
          sv     (SecretVersionName/of "booktracker-1208" secret-name "latest")
          response (.accessSecretVersion client sv)
          payload (-> response .getPayload .getData .toStringUtf8 read-string)]
      (log/warn ::get-secret! :secret-name secret-name)
      ;(log/warn ::get-secret! :dbname (-> payload :dbname))
      (.close client)
      payload))

(comment
  (get-secret! "mysql")
  (get-secret! "rainforest")
  (get-secret! "mysql-booktracker")
  (get-secret! "mongodb")
  ; 500ms
  (time (get-secret! "mysql-booktracker"))
  0)

(comment
  (.setLevel (Logger/getLogger "io.grpc.netty.shaded.io.netty.util.internal.PlatformDependent" Level/OFF))

  0)

(comment
  (init)

  ; Secret secret =
  ;          Secret.newBuilder()
  ;              .setReplication(
  ;                  Replication.newBuilder()
  ;                      .setAutomatic(Replication.Automatic.newBuilder().build())
  ;                      .build())
  ;              .build();
  (def secret (.build (Secret/newBuilder)))

  (bean secret)
  (pp/print-table (r/reflect client))

;(def project (ProjectName/of "booktracker-1208"))
  ;(.accessSecretVersion client "mysql")

  ; https://cloud.google.com/secret-manager/docs/samples/secretmanager-access-secret-version#secretmanager_access_secret_version-java
  (def client (SecretManagerServiceClient/create))
  (def sv (SecretVersionName/of "booktracker-1208" "mysql" "latest"))
  (bean sv)
  (def response (.accessSecretVersion client sv))
  response
  (bean response)

  ; OMG.  works in clj, but fails in repl!
  ; because of classpath issue: don't use IntelliJ classpath!!!
  ; https://clojurians.slack.com/archives/C064BA6G2/p1561995274335100

  ; #object[com.google.cloud.secretmanager.v1.AccessSecretVersionResponse 0x5593dd2b "name: \"projects/1018897188794/secrets/mysql/versions/1\"\npayload {\n  data: \"{:db \\\"abc\\\"}\"\n  data_crc32c: 3877276202\n}\n"]

  ; https://github.com/googleapis/java-secretmanager/blob/main/samples/snippets/src/main/java/secretmanager/AccessSecretVersion.java
  ;  String payload = response.getPayload().getData().toStringUtf8();
  (-> response .getPayload .getData .toStringUtf8 read-string)

  0)

(defn get-auth-token-shell []
  (-> (clojure.java.shell/sh "gcloud" "auth" "print-access-token")
      :out
      clojure.string/trim))

(comment
  (get-auth-token-shell)
  0)

; https://cloud.google.com/compute/docs/access/authenticate-workloads
(defn get-access-token-gcloud-net []
  (-> (http/get "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"
                {:headers {"Metadata-Flavor" "Google"}})
      :body
      (json/read-str :key-fn keyword)
      :access_token))

(comment
  (get-access-token-gcloud-net)
  0)

; 'https://secretmanager.googleapis.com/v1/projects/YOUR_PROJECT_ID/secrets/YOUR_SECRET_NAME' \
;  -H 'Authorization: Bearer YOUR_ACCESS_TOKEN'
;
;
(defn base64-decode [s]
  (.decode (java.util.Base64/getDecoder) s))

;; ============================================================
;; Application Default Credentials (ADC) Support
;; ============================================================

(defn get-adc-credentials-path
  "Find the ADC credentials file. Checks:
   1. GOOGLE_APPLICATION_CREDENTIALS env var
   2. Default location: ~/.config/gcloud/application_default_credentials.json
   Returns path string or nil if not found."
  []
  (let [env-path (System/getenv "GOOGLE_APPLICATION_CREDENTIALS")
        default-path (str (System/getProperty "user.home")
                          "/.config/gcloud/application_default_credentials.json")]
    (cond
      ;; Check env var first
      (and env-path (.exists (java.io.File. env-path)))
      (do
        (log/info ::get-adc-credentials-path :found env-path :source "GOOGLE_APPLICATION_CREDENTIALS")
        env-path)

      ;; Check default location
      (.exists (java.io.File. default-path))
      (do
        (log/info ::get-adc-credentials-path :found default-path :source "default-location")
        default-path)

      :else
      (do
        (log/debug ::get-adc-credentials-path :not-found)
        nil))))

(defn read-adc-credentials
  "Read and parse ADC credentials from JSON file.
   Returns map with :client_id, :client_secret, :refresh_token, :type, etc."
  [path]
  (try
    (let [content (slurp path)
          creds (json/read-str content :key-fn keyword)]
      (log/info ::read-adc-credentials :type (:type creds))
      creds)
    (catch Exception e
      (log/warn ::read-adc-credentials :error (.getMessage e))
      nil)))

(defn get-access-token-from-refresh-token
  "Exchange a refresh token for an access token using Google's OAuth2 endpoint.
   Works with 'authorized_user' type credentials from `gcloud auth application-default login`."
  [{:keys [client_id client_secret refresh_token]}]
  (log/info ::get-access-token-from-refresh-token :exchanging-refresh-token)
  (let [response (http/post "https://oauth2.googleapis.com/token"
                            {:form-params {:client_id     client_id
                                           :client_secret client_secret
                                           :refresh_token refresh_token
                                           :grant_type    "refresh_token"}})
        body (json/read-str (:body response) :key-fn keyword)]
    (if-let [token (:access_token body)]
      (do
        (log/info ::get-access-token-from-refresh-token :success)
        token)
      (throw (ex-info "No access_token in response" {:body body})))))

(defn get-access-token-adc
  "Get access token using Application Default Credentials.
   Supports 'authorized_user' type credentials.
   Returns access token string or throws exception."
  []
  (if-let [path (get-adc-credentials-path)]
    (if-let [creds (read-adc-credentials path)]
      (case (:type creds)
        "authorized_user"
        (get-access-token-from-refresh-token creds)

        ;; Service account would require JWT signing - not implemented yet
        "service_account"
        (throw (ex-info "Service account ADC not yet supported"
                        {:type (:type creds)
                         :hint "Use authorized_user credentials from 'gcloud auth application-default login'"}))

        (throw (ex-info "Unknown ADC credential type"
                        {:type (:type creds)})))
      (throw (ex-info "Could not read ADC credentials" {:path path})))
    (throw (ex-info "No ADC credentials file found"
                    {:checked ["GOOGLE_APPLICATION_CREDENTIALS env var"
                               "~/.config/gcloud/application_default_credentials.json"]}))))

(comment
  ;; Test ADC
  (get-adc-credentials-path)
  (read-adc-credentials (get-adc-credentials-path))
  (get-access-token-adc)
  0)

;; ============================================================
;; Token retrieval with ADC support
;; ============================================================

(defn get-token
  "Get access token for Google Cloud APIs.
   Tries in order:
   1. ADC (Application Default Credentials) - works in Docker with mounted ~/.config/gcloud
   2. gcloud CLI (shell) - works locally with gcloud installed
   3. Metadata server (network) - works in GCP compute environments"
  []
  (try
    ;; Try ADC first (best for Docker)
    (get-access-token-adc)
    (catch Exception adc-ex
      (log/debug ::get-token :adc-failed (.getMessage adc-ex))
      (try
        ;; Try shell (gcloud CLI)
        (get-auth-token-shell)
        (catch Exception shell-ex
          (log/debug ::get-token :shell-failed (.getMessage shell-ex))
          (try
            ;; Try metadata server (GCP environments)
            (get-access-token-gcloud-net)
            (catch Exception net-ex
              (throw (ex-info "Failed to get token via all methods (ADC, shell, network)"
                              {:adc-error   (.getMessage adc-ex)
                               :shell-error (.getMessage shell-ex)
                               :net-error   (.getMessage net-ex)})))))))))

(defn- get-secret-http!
  "Fetches secret using Google Cloud Secret Manager REST API.
   Returns parsed EDN payload from secret.
   Private: use get-secret! instead."
  [secret-name project-id]
  (let [;project-id "booktracker-1208"
        url (format "https://secretmanager.googleapis.com/v1/projects/%s/secrets/%s/versions/latest:access"
                    project-id
                    secret-name)
        token (get-token)
        response (http/get url
                           {:headers {"Authorization" (str "Bearer " token)}})
        body-parsed (json/read-str (:body response) :key-fn keyword)
        raw-str (-> body-parsed
                    :payload
                    :data
                    base64-decode
                    (String. "UTF-8"))
        ;; Try EDN parsing for structured secrets (maps, vectors, etc.)
        ;; Fall back to raw string for plain-text secrets (passwords, API keys)
        payload (let [parsed (try (edn/read-string raw-str) (catch Exception _ nil))]
                  (if (coll? parsed) parsed raw-str))]
    (log/warn ::get-secret! :secret-name secret-name project-id)
    payload))

(defn- get-secret-gcloud!
  "Fetches secret using gcloud CLI command.
   Returns parsed EDN payload from secret.
   Private: use get-secret! instead."
  [secret-name]
  (log/info ::get-secret-gcloud! :attempting secret-name :method "gcloud CLI")
  (try
    (let [result (shell/sh "gcloud" "secrets" "versions" "access" "latest"
                           (str "--secret=" secret-name))
          {:keys [exit out err]} result]
      (if (zero? exit)
        (do
          (log/info ::get-secret-gcloud! :success secret-name :method "gcloud CLI")
          (let [parsed (try (edn/read-string out) (catch Exception _ nil))]
            (if (coll? parsed) parsed (clojure.string/trim out))))
        (do
          (log/error ::get-secret-gcloud! :failed secret-name
                     :method "gcloud CLI" :exit exit :error err)
          (throw (ex-info "gcloud command failed"
                          {:exit exit :error err :secret-name secret-name})))))
    (catch Exception e
      (log/error ::get-secret-gcloud! :exception secret-name
                 :method "gcloud CLI" :error (.getMessage e))
      (throw e))))

(def DEFAULT-PROJECT-ID "booktracker-1208")

(defn get-secret!
  "Fetches secret from Google Cloud Secret Manager.

   Token retrieval order (via get-token):
     1. ADC (Application Default Credentials) - mount ~/.config/gcloud in Docker
     2. gcloud CLI shell - works locally with gcloud installed
     3. Metadata server - GCP compute environments (Cloud Run, GCE)

   For Docker, mount your gcloud config:
     docker run -v ~/.config/gcloud:/root/.config/gcloud ...

   Falls back to gcloud CLI 'secrets versions access' if HTTP fails.

   Returns parsed EDN payload from secret."
  ([secret-name project-id]
   (log/info ::get-secret! :attempting secret-name
             :in-cloud-run (running-in-cloud-run?))
   (try
     (log/info ::get-secret! :trying-method "HTTP/Secret Manager API")
     (get-secret-http! secret-name project-id)
     (catch Exception http-ex
       (log/warn ::get-secret! :http-failed secret-name
                 :error (.getMessage http-ex))
       (try
         (log/info ::get-secret! :trying-method "gcloud CLI fallback")
         (get-secret-gcloud! secret-name)
         (catch Exception gcloud-ex
           (log/error ::get-secret! :all-methods-failed secret-name
                      :http-error (.getMessage http-ex)
                      :gcloud-error (.getMessage gcloud-ex))
           (throw (ex-info "Failed to get secret via all methods"
                           {:secret-name  secret-name
                            :http-error   (.getMessage http-ex)
                            :gcloud-error (.getMessage gcloud-ex)})))))))
  ([secret-name]
   (get-secret! secret-name DEFAULT-PROJECT-ID)))

(comment
  (get-token)
  (def retval
    (get-secret-http! "mysql"))

  ;; Test the new gcloud function
  (get-secret-gcloud! "mysql")
  (get-secret-gcloud! "reddit")

  ;; Test the fallback chain
  (get-secret! "mysql")
  (get-secret! "rainforest")
  (get-secret! "mysql-booktracker")
  (get-secret! "podcaster" "podcaster")
  (get-secret! "podcaster" "podcaster-468303")

  0)
