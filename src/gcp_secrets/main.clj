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

(defn get-token []
  (try
    (get-auth-token-shell)
    (catch Exception shell-ex
      (try
        (get-access-token-gcloud-net)
        (catch Exception net-ex
          (throw (ex-info "Failed to get token via shell and network"
                   {:shell-error (.getMessage shell-ex)
                    :net-error (.getMessage net-ex)})))))))

(defn get-secret-http!
  "Fetches secret using Google Cloud Secret Manager REST API
   Returns parsed EDN payload from secret"
  [secret-name project-id]
  (let [;project-id "booktracker-1208"
        url (format "https://secretmanager.googleapis.com/v1/projects/%s/secrets/%s/versions/latest:access"
              project-id
              secret-name)
        token (get-token)
        response (http/get url
                   {:headers {"Authorization" (str "Bearer " token)}
                    :as :json})
        body-parsed (if (map? (:body response))
                      (:body response)
                      (json/read-str (:body response) :key-fn keyword))
        payload (-> body-parsed
                  :payload
                  :data
                  base64-decode
                  (String. "UTF-8")
                  edn/read-string)]
    (log/warn ::get-secret! :secret-name secret-name)
    payload))

(defn get-secret-gcloud!
  "Fetches secret using gcloud CLI command
   Returns parsed EDN payload from secret"
  [secret-name]
  (log/info ::get-secret-gcloud! :attempting secret-name :method "gcloud CLI")
  (try
    (let [result (shell/sh "gcloud" "secrets" "versions" "access" "latest" 
                          (str "--secret=" secret-name))
          {:keys [exit out err]} result]
      (if (zero? exit)
        (do
          (log/info ::get-secret-gcloud! :success secret-name :method "gcloud CLI")
          (edn/read-string out))
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
  "Fetches secret with multiple fallback methods
   1. Try HTTP/network method
   2. Fallback to gcloud CLI
   Returns parsed EDN payload from secret"
  ([secret-name project-id]
   (log/info ::get-secret! :attempting secret-name)
   (try
     (log/info ::get-secret! :trying-method "HTTP/network")
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
