(ns hyperphor.nlq.frontend.universal-query
  "A project-agnostic wrapper around sql-query/ui — same NL→SQL query UI a
   single-project tab uses with one project hardcoded, but with a selector
   across every real NLQ project in config.edn."
  (:require [re-frame.core :as rf]
            [hyperphor.way.ui.config :as config]
            [hyperphor.way.web-utils :as hwu]
            [hyperphor.nlq.frontend.sql-query :as sql-query]))

(defn queryable-projects
  "Names of :nlq config entries backed by a real, queryable SQL data source
   (has a :db) — excludes auxiliary entries like \"Vegalite\", which exists
   only to hold LLM config/examples for visualization generation, not a
   project with its own tables to query. Also excludes :sparql-provider
   entries — this UI always queries via sql-query/ui's :sql qbox id, which
   routes through sql/project-ddl and friends; those have no :sparql
   provider method and would throw if selected here. A :sparql project
   should get its own dedicated tab (see frontend.sparql-query) instead."
  []
  (->> (config/config :nlq)
       (filter :db)
       (remove #(= :sparql (get-in % [:db :provider])))
       (map :name)))

(rf/reg-sub
 :universal-query-project
 (fn [db _] (:universal-query-project db)))

(rf/reg-event-db
 :universal-query-select-project
 (fn [db [_ project]]
   (-> db
       (assoc :universal-query-project project)
       ;; Switching projects mid-view (unlike a fixed per-project tab, where
       ;; this never comes up) would otherwise leave the previous project's
       ;; query/results/inspector on screen, mislabeled under the new
       ;; selection until the user ran a fresh query.
       (update :qbox dissoc :sql :sql-vizq)
       (dissoc :sql-inspect))))

(defn ui
  []
  (let [projects (queryable-projects)
        selected (or @(rf/subscribe [:universal-query-project]) (first projects))]
    [:div
     [:div.m-3
      [:label.me-2 {:for "universal-query-project"} "Project: "]
      [hwu/select-widget "universal-query-project" selected
       #(rf/dispatch [:universal-query-select-project %])
       projects nil false {:width "200px" :display "inline-block"}]]
     (when selected
       ;; Remount on project change, not just re-render — cheap way to make
       ;; sure sql-query/ui carries no incidental local state across projects.
       ^{:key selected}
       [sql-query/ui selected])]))
