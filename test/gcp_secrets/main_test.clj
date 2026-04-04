(ns gcp-secrets.main-test
  "Tests for gcp-secrets with mocked HTTP calls.
   Verifies the full parsing chain works for different secret formats
   WITHOUT hitting real GCP APIs."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [gcp-secrets.main :as gcpsec]
            [hato.client :as http]))

;; ============================================================
;; Test helpers
;; ============================================================

(defn- base64-encode
  "Encode string to base64 (reverse of what get-secret-http! decodes)."
  [^String s]
  (.encodeToString (java.util.Base64/getEncoder) (.getBytes s "UTF-8")))

(defn- fake-secret-manager-response
  "Build a fake Secret Manager API response body (JSON string).
   Wraps the secret value in the expected {payload: {data: base64}} envelope."
  [secret-value-str]
  (json/write-str {:name "projects/123/secrets/test/versions/1"
                   :payload {:data (base64-encode secret-value-str)}}))

(defn- fake-token-response
  "Build a fake metadata server token response body (JSON string)."
  []
  (json/write-str {:access_token "ya29.fake-token-for-testing"
                   :expires_in 3600
                   :token_type "Bearer"}))

(defn- mock-http-get
  "Returns a mock http/get that returns different responses based on URL."
  [secret-value-str]
  (fn [url & [opts]]
    (cond
      ;; Metadata server — token request
      (.contains url "metadata.google.internal")
      {:status 200 :body (fake-token-response)}

      ;; Secret Manager API — secret request
      (.contains url "secretmanager.googleapis.com")
      {:status 200 :body (fake-secret-manager-response secret-value-str)}

      :else
      (throw (ex-info "Unexpected URL in mock" {:url url})))))

;; ============================================================
;; Tests: EDN map secrets (most common — credentials, config)
;; ============================================================

(deftest edn-map-secret-test
  (testing "EDN map secret (e.g., database credentials)"
    (let [secret {:user "admin" :password "s3cret" :dbname "mydb"}
          secret-str (pr-str secret)]
      (with-redefs [http/get (mock-http-get secret-str)]
        (is (= secret (gcpsec/get-secret! "db-creds" "test-project")))))))

(deftest edn-nested-map-secret-test
  (testing "EDN nested map (e.g., Firebase service account as EDN)"
    (let [secret {:type "service_account"
                  :project_id "my-project"
                  :private_key "-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----\n"
                  :client_email "sa@my-project.iam.gserviceaccount.com"}
          secret-str (pr-str secret)]
      (with-redefs [http/get (mock-http-get secret-str)]
        (let [result (gcpsec/get-secret! "firebase-key" "test-project")]
          (is (= "service_account" (:type result)))
          (is (= "my-project" (:project_id result)))
          (is (.contains (:private_key result) "PRIVATE KEY")))))))

;; ============================================================
;; Tests: EDN vector secrets
;; ============================================================

(deftest edn-vector-secret-test
  (testing "EDN vector secret"
    (let [secret [:a :b :c]
          secret-str (pr-str secret)]
      (with-redefs [http/get (mock-http-get secret-str)]
        (is (= secret (gcpsec/get-secret! "my-list" "test-project")))))))

;; ============================================================
;; Tests: Plain string secrets (API keys, passwords)
;; ============================================================

(deftest plain-string-secret-test
  (testing "Plain string secret (API key — not valid EDN collection)"
    (let [secret-str "sk-1234567890abcdef"]
      (with-redefs [http/get (mock-http-get secret-str)]
        (is (= "sk-1234567890abcdef"
               (gcpsec/get-secret! "api-key" "test-project")))))))

(deftest plain-password-secret-test
  (testing "Plain password with special chars"
    (let [secret-str "p@$$w0rd!#%^&*()"]
      (with-redefs [http/get (mock-http-get secret-str)]
        (is (= "p@$$w0rd!#%^&*()"
               (gcpsec/get-secret! "password" "test-project")))))))

;; ============================================================
;; Tests: Token retrieval (metadata server path)
;; ============================================================

(deftest metadata-server-token-test
  (testing "Token retrieval from metadata server parses JSON correctly"
    (with-redefs [http/get (fn [url & _]
                             (when (.contains url "metadata.google.internal")
                               {:status 200
                                :body (json/write-str {:access_token "ya29.test"
                                                       :expires_in 3599
                                                       :token_type "Bearer"})}))]
      ;; get-token is private, but we can test it through get-secret!
      ;; which calls get-token internally. If token parsing fails,
      ;; get-secret! would throw before reaching Secret Manager.
      ;; Just verify no exception on the token step.
      (with-redefs [http/get (mock-http-get (pr-str {:ok true}))]
        (is (= {:ok true} (gcpsec/get-secret! "test" "proj")))))))

;; ============================================================
;; Tests: OAuth2 refresh token (ADC path)
;; ============================================================

(deftest refresh-token-exchange-test
  (testing "OAuth2 refresh token exchange parses JSON without :as :json"
    (with-redefs [http/post (fn [url opts]
                              (when (.contains url "oauth2.googleapis.com")
                                {:status 200
                                 :body (json/write-str {:access_token "ya29.refreshed"
                                                        :expires_in 3600
                                                        :token_type "Bearer"})}))
                  http/get (fn [url & _]
                             (when (.contains url "secretmanager")
                               {:status 200
                                :body (fake-secret-manager-response
                                       (pr-str {:db "test"}))}))]
      ;; Simulate ADC path: credentials file exists, exchange refresh token
      (let [fake-creds {:type "authorized_user"
                        :client_id "id"
                        :client_secret "secret"
                        :refresh_token "refresh"}]
        (with-redefs [gcp-secrets.main/get-adc-credentials-path (constantly "/fake/path")
                      gcp-secrets.main/read-adc-credentials (constantly fake-creds)]
          (is (= {:db "test"} (gcpsec/get-secret! "test" "proj"))))))))

;; ============================================================
;; Tests: Fallback chain
;; ============================================================

(deftest http-failure-falls-back-to-gcloud-test
  (testing "When HTTP fails, falls back to gcloud CLI"
    (with-redefs [http/get (fn [url & _]
                             (throw (ex-info "Connection refused" {})))
                  clojure.java.shell/sh (fn [& _]
                                          {:exit 0
                                           :out (pr-str {:fallback true})
                                           :err ""})]
      (is (= {:fallback true} (gcpsec/get-secret! "test" "proj"))))))

(deftest both-methods-fail-throws-test
  (testing "When both HTTP and gcloud fail, throws with both errors"
    (with-redefs [http/get (fn [url & _]
                             (throw (ex-info "HTTP failed" {})))
                  clojure.java.shell/sh (fn [& _]
                                          {:exit 1
                                           :out ""
                                           :err "gcloud not found"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Failed to get secret"
                            (gcpsec/get-secret! "test" "proj"))))))

;; ============================================================
;; Tests: URL construction and project ID
;; ============================================================

(deftest secret-request-uses-correct-project-id
  (testing "API call URL contains the project-id passed by caller"
    (let [captured-url (atom nil)]
      (with-redefs [http/get (fn [url & [opts]]
                               (when (.contains url "secretmanager")
                                 (reset! captured-url url))
                               (cond
                                 (.contains url "metadata.google.internal")
                                 {:status 200 :body (fake-token-response)}

                                 (.contains url "secretmanager")
                                 {:status 200 :body (fake-secret-manager-response
                                                     (pr-str {:ok true}))}))
                    gcp-secrets.main/get-adc-credentials-path (constantly nil)]
        (gcpsec/get-secret! "my-secret" "does2020")
        (is (clojure.string/includes? @captured-url "projects/does2020/"))
        (is (clojure.string/includes? @captured-url "secrets/my-secret/"))))))

(deftest wrong-project-id-visible-in-url
  (testing "If caller passes wrong project, URL shows it (would have caught our bug)"
    (let [captured-url (atom nil)]
      (with-redefs [http/get (fn [url & [opts]]
                               (when (.contains url "secretmanager")
                                 (reset! captured-url url))
                               (cond
                                 (.contains url "metadata.google.internal")
                                 {:status 200 :body (fake-token-response)}

                                 (.contains url "secretmanager")
                                 {:status 200 :body (fake-secret-manager-response
                                                     (pr-str {:ok true}))}))
                    gcp-secrets.main/get-adc-credentials-path (constantly nil)]
        (gcpsec/get-secret! "my-secret" "itrev-video-library-backups")
        ;; This URL would 403 in real life — wrong project!
        (is (clojure.string/includes? @captured-url "itrev-video-library-backups"))
        (is (not (clojure.string/includes? @captured-url "does2020")))))))

;; ============================================================
;; Tests: 403 error includes project-id and URL
;; ============================================================

(deftest error-on-403-includes-project-id-and-url
  (testing "403 error ex-data includes project-id, secret-name, and URL"
    (with-redefs [http/get (fn [url & [opts]]
                             (cond
                               (.contains url "metadata.google.internal")
                               {:status 200 :body (fake-token-response)}

                               (.contains url "secretmanager")
                               {:status 403
                                :body "{\"error\":{\"message\":\"Permission denied\"}}"}))
                  ;; ADC not available in this test
                  gcp-secrets.main/get-adc-credentials-path (constantly nil)
                  ;; gcloud fallback also fails
                  clojure.java.shell/sh (fn [& _] {:exit 1 :out "" :err "not found"})]
      (try
        (gcpsec/get-secret! "my-secret" "wrong-project")
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (clojure.string/includes? (.getMessage e) "wrong-project")
              "Error message must include project-id")
          (is (clojure.string/includes? (.getMessage e) "my-secret")
              "Error message must include secret-name")
          (is (= "wrong-project" (:project-id (ex-data e))))
          (is (= "my-secret" (:secret-name (ex-data e)))))))))
