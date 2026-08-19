;;; Split out of nlq.generate so nlq.inspect (which needs it too, for the
;;; :sql-inspect endpoint's project->db lookup) doesn't have to depend on
;;; nlq.generate — which itself depends on nlq.inspect, for annotate-inspectable.
(ns hyperphor.nlq.config
  "Per-project NLQ config lookup."
  (:require [hyperphor.way.config :as config]))

(defn projects
  []
  (map :name (config/config :nlq)))

(defn project-named
  "Look up the NLQ config entry by project name."
  [project-name]
  (first (filter #(= (:name %) project-name) (config/config :nlq))))
