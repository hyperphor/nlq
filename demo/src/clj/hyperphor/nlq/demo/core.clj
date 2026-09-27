(ns hyperphor.nlq.demo.core
  (:gen-class)
  (:require [hyperphor.way.server :as server]
            [hyperphor.way.config :as config]
            [hyperphor.nlq.demo.handler :as handler]
            [hyperphor.nlq.config :as nlqc]
            [hyperphor.nlq.schema :as schema]
            [hyperphor.multitool.cljcore :as ju]
            ;; Each sources.* ns only registers its :provider defmethods once
            ;; required (see hyperphor.nlq.sources.sql) -- require them all here so
            ;; any :provider in resources/config.edn works with no code changes.
            hyperphor.nlq.sources.sqlite
            hyperphor.nlq.sources.postgres
            hyperphor.nlq.sources.bigquery
            hyperphor.nlq.sources.cirro
            hyperphor.nlq.sources.sparql
            [taoensso.timbre :as log]
            [environ.core :as env]))

(defn -main
  [& args]
  (config/read-config "config.edn")
  (let [port (or (first args) (env/env :port))]
    (log/info "Starting nlq-demo server on port" port)
    (server/start (Integer. port) (handler/app))
    ;; Smart enough to be a no-op on a real server (Heroku etc).
    (ju/open-url (format "http://localhost:%s" port))))

;;; Called at build/deploy time -- generates the HTML schema doc for every
;;; configured project that declares a :schema (see hyperphor.nlq.schema/init).
(defn build-schema
  []
  (config/read-config "config.edn")
  (schema/init (map nlqc/project-named (nlqc/projects))))
