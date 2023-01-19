(ns gcp-secrets.env)

(defn get-env!
  " queries environment variables, and returns :circleci, :googlecloudrun

    https://circleci.com/docs/variables/#built-in-environment-variables
      --> CIRCLECI
    https://cloud.google.com/run/docs/container-contract#env-vars
      --> K_SERVICE
    "
  []
  (cond
    (System/getenv "CIRCLECI")
    :circleci
    (System/getenv "K_SERVICE")
    :googlecloudrun
    (System/getenv "CLOUD_RUN_JOB")
    :googlecloudrun
    :else
    nil))


(comment
  (System/getenv "HOME")
  (System/getenv "USER")
  (System/getenv)

  (get-env!)
  0)