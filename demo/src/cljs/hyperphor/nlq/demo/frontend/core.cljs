(ns hyperphor.nlq.demo.frontend.core
  "The whole app shell: a Query tab (hyperphor.nlq.frontend.universal-query/ui --
   project picker + NL query/grid/viz/inspector, already fully config-driven) and a
   Schema tab (below -- no library component covers this yet). Nothing here is
   project-specific; investigating a new db is purely a matter of editing
   resources/config.edn, not this file."
  (:require [reagent.core :as r]
            [hyperphor.way.tabs :as tabs]
            [hyperphor.way.ui.init :as init]
            [hyperphor.way.ui.config :as config]
            [hyperphor.way.web-utils :as hwu]
            [hyperphor.nlq.frontend.universal-query :as universal-query]))

;;; ── Schema tab ───────────────────────────────────────────────────────────────
;;; hyperphor.nlq.schema/init (called from core.clj's build-schema) generates
;;; resources/public/<project-name>/schema/index.html for every :nlq config entry
;;; with a :schema -- this just picks among those and iframes the result.

(defn- schema-projects
  []
  (->> (config/config :nlq)
       (filter :schema)
       (map :name)))

(defonce selected-schema-project (r/atom nil))

(defn schema
  []
  (let [projects (schema-projects)
        selected (or @selected-schema-project (first projects))]
    (if (empty? projects)
      [:div.m-3 "No configured project declares a :schema."]
      [:div
       [:div.m-3
        [:label.me-2 {:for "schema-project"} "Project: "]
        [hwu/select-widget "schema-project" selected
         #(reset! selected-schema-project %)
         projects nil false {:width "200px" :display "inline-block"}]]
       ;; tabs-nav routes via accountant (real pushState) -- src must be absolute,
       ;; a relative one would resolve against the current route instead of the
       ;; site root (same fix okc/nlq-aact's own schema tabs need).
       [:iframe {:src (str "/" selected "/schema/index.html")
                 :style {:width "100%" :height "90%" :border "none"}}]])))

(defn app-ui
  []
  ;; First key must be :home -- hyperphor.way.ui.init's :initialize-db hardcodes
  ;; the initial :route to [:home] (its own TODO notes this isn't configurable
  ;; yet), so tabs-nav's active-tab lookup misses on first render otherwise,
  ;; rendering nil in place of a component and crashing on mount.
  [tabs/tabs-nav
   :tab
   (array-map
    :home universal-query/ui
    :schema schema)
   []])

(defn ^:export init
  []
  (init/init app-ui nil))
