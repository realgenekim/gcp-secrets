(ns gcp-secrets.main
  (:require
    [clojure.reflect :as r]
    [clojure.pprint :as pp]
    [taoensso.timbre :as log])
  (:import (com.google.cloud.secretmanager.v1 Secret
                                              SecretManagerServiceClient
                                              ProjectName
                                              SecretVersionName
                                              AccessSecretVersionResponse)))

(defn use-local-keys?
  " returns non-nil if USE_LOCAL_KEYFILES environment var is set "
  []
  (let [ret (System/getenv "USE_LOCAL_KEYFILES")]
    (log/warn ::use-local-keys? ret)
    ret))

(comment
  (gcpsec/use-local-keys?)
  0)

; enable API
; https://console.cloud.google.com/apis/enableflow?apiid=cloudbuild.googleapis.com,secretmanager.googleapis.com&redirect=https:%2F%2Fcloud.google.com%2Fbuild%2Fdocs%2Fsecuring-builds%2Fuse-secrets&_ga=2.167458494.1413660334.1663728888-1943839537.1657917961&project=booktracker-1208

(defn get-secret!
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
  ; 500ms
  (time (get-secret! "mysql-booktracker"))
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


  ;(.accessSecretVersion client "mysql")
  ; https://cloud.google.com/secret-manager/docs/samples/secretmanager-access-secret-version#secretmanager_access_secret_version-java

  ; OMG.  works in clj, but fails in repl!
  ; because of classpath issue: don't use IntelliJ classpath!!!
  ; https://clojurians.slack.com/archives/C064BA6G2/p1561995274335100

  ; #object[com.google.cloud.secretmanager.v1.AccessSecretVersionResponse 0x5593dd2b "name: \"projects/1018897188794/secrets/mysql/versions/1\"\npayload {\n  data: \"{:db \\\"abc\\\"}\"\n  data_crc32c: 3877276202\n}\n"]

  ; https://github.com/googleapis/java-secretmanager/blob/main/samples/snippets/src/main/java/secretmanager/AccessSecretVersion.java
  ;  String payload = response.getPayload().getData().toStringUtf8();
  (-> response .getPayload .getData .toStringUtf8 read-string)

  0)