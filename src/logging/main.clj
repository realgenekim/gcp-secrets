(ns logging.main
  (:require
    [clojure.string :as str]
    [clojure.pprint :refer [pprint]]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))


; https://stackoverflow.com/questions/2976308/how-do-i-turn-off-logging-in-java-c3p0-connection-pooling-lib
;(System/setProperties
;  (doto (java.util.Properties. (System/getProperties))
;    (.put "com.mchange.v2.log.MLog" "com.mchange.v2.log.FallbackMLog")
;    (.put "com.mchange.v2.log.FallbackMLog.DEFAULT_CUTOFF_LEVEL" "OFF")
;    (.put "org.eclipse.jetty.util.log.class",
;          "org.eclipse.jetty.util.log.JavaUtilLog")
;    (.put "org.eclipse.jetty.util.log.class.LEVEL", "INFO")))


(def config
  {:taoensso.timbre/logging-config
   {
    :ns-filter
    {:deny #{
             "com.mchange.v2.c3p0.*"
             "com.mchange.v2.resourcepool.*"
             "com.mchange.v2.log.*"
             "com.zaxxer.hikari.pool.HikariPool"
             "com.zaxxer.*"
             "com.mchange.v2.c3p0.impl.AbstractPoolBackedDataSource"
             "com.mchange.v2.c3p0.impl.NewPooledConnection"
             "datomic.common"
             "datomic.connector"
             "datomic.coordination"
             "datomic.db"
             "datomic.index"
             "datomic.kv-cluster"
             "datomic.log"
             "datomic.peer"
             "datomic.process-monitor"
             "datomic.reconnector2"
             "datomic.slf4j"
             "org.flywaydb.*"
             "io.netty.buffer.PoolThreadCache"
             "io.grpc.netty.shaded.io.grpc.netty"
             "io.grpc.netty.shaded.*"
             "org.apache.http.impl.conn.PoolingHttpClientConnectionManager"
             "org.mongodb.driver.*"
             "org.projectodd.wunderboss.web.Web"
             "org.quartz.core.JobRunShell"
             "org.quartz.core.QuartzScheduler"
             "org.quartz.core.QuartzSchedulerThread"
             "org.quartz.impl.StdSchedulerFactory"
             "org.quartz.impl.jdbcjobstore.JobStoreTX"
             "org.quartz.impl.jdbcjobstore.SimpleSemaphore"
             "org.quartz.impl.jdbcjobstore.StdRowLockSemaphore"
             "org.quartz.plugins.history.LoggingJobHistoryPlugin"
             "org.quartz.plugins.history.LoggingTriggerHistoryPlugin"
             "org.quartz.utils.UpdateChecker"
             "shadow.cljs.devtools.server.worker.impl"}
     :allow #{"*"}}
    :min-level
    [[#{"taoensso.*"} :info]
     ["org.mongodb.driver.*" :error]
     ["com.example.*" :info]
     ["*" :info]]}})

(defmacro p
  "Convert a data structure to a visually-delimited pretty-printed string block."
  [v]
  `(str
     ~(str "\n" v "\n================================================================================\n")
     (with-out-str (clojure.pprint/pprint ~v))
     "================================================================================"))

(defn pretty
  "Marks a data item for pretty formatting when logging it (requires installing logging middleware)."
  [v]
  (with-meta v {:pretty true}))

(defn pretty-middleware [data->string]
  "Returns timbre logging middleware that will reformat items marked with `pretty` as pretty-printed strings using `data->string`."
  (fn [data]
    (update data :vargs (fn [args]
                          (mapv
                            (fn [v]
                              (if (and (coll? v) (-> v meta :pretty))
                                (data->string v)
                                v))
                            args)))))

(defn custom-output-fn
  "Derived from Timbre's default output function. Used server-side."
  ([data] (custom-output-fn nil data))
  ([opts data]
   (let [{:keys [no-stacktrace?]} opts
         {:keys [level ?err msg_ ?ns-str ?file timestamp_ ?line]} data]
     (format "%1.1S %s %20s:-%3s - %s%s"
       (name level)
       (force timestamp_)
       (str/replace-first (or ?ns-str ?file "?") "com.fulcrologic." "_")
       (or ?line "?")
       (force msg_)
       (enc/if-let [_   (not no-stacktrace?)
                    err ?err]
         (str "\n" (log/stacktrace err opts))
         "")))))

(defn configure-logging!
  "Configure clojure logging for this project. `config` is the global config map that should contain
  `:taoensso.timbre/logging-config` as a key."
  [config]
  (let [{:keys [taoensso.timbre/logging-config]} config]
    (log/merge-config! (assoc logging-config
                         :middleware [(pretty-middleware #(with-out-str (pprint %)))]
                         :output-fn custom-output-fn))
    (log/debug "Configured Timbre with " (p logging-config))))

; System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog");
;System.setProperty("org.eclipse.jetty.LEVEL", "OFF");

(configure-logging! config)
