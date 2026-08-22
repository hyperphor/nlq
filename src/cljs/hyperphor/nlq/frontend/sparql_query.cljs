;;; A trimmed sibling of sql_query.cljs's :sql UI, using the same generic
;;; qbox/ui + /api/qbox/query plumbing (qbox id :sparql instead of :sql, see
;;; handler.clj's qbox-endpoint). Deliberately does NOT reuse sql_query.cljs's
;;; semantic-column grouping / click-to-inspect machinery -- both are keyed
;;; off the SQL/Alzabo `kind_field` result-column naming convention
;;; (schema/columns-info), which raw SPARQL variable bindings (?px,
;;; ?pxLabel, ...) never match -- so results just render as a plain grid,
;;; auto-columned from whatever the query's own SELECT vars were.
(ns hyperphor.nlq.frontend.sparql-query
  "NL->SPARQL query UI (experimental Wikidata demo, see design/sparql.md)."
  (:require [reagent.core :as reagent]
            [re-frame.core :as rf]
            [clojure.string :as str]
            [hyperphor.way.aggrid :as ag]
            [hyperphor.way.cards :as cards]
            [hyperphor.way.markdown :as md]
            [hyperphor.way.ui.config :as config]
            [hyperphor.nlq.frontend.qbox :as qbox]
            [hyperphor.nlq.frontend.utils :as wu]))

(defn project-config
  [project]
  (->> (config/config :nlq)
       (filter #(= (:name %) project)) first))

;;; ── Results grid ─────────────────────────────────────────────────────────
;;; Wikidata result values are often bare entity URIs (Q-numbers) -- link
;;; them out to wikidata.org rather than showing an unreadable full URI.

(defn wikidata-entity-uri?
  [v]
  (and (string? v) (str/starts-with? v "http://www.wikidata.org/entity/")))

(defn cell-renderer
  [params]
  (let [v (.-value params)]
    (reagent/as-element
     (if (wikidata-entity-uri? v)
       [:a.ent-ext {:href v :target "_ext"} (str/replace v #".*/" "")]
       [:span (str v)]))))

(defn column-defs
  [cols]
  (mapv (fn [col] {:field col :headerName (name col) :cellRenderer cell-renderer}) cols))

(defn grid-view
  [results]
  (if (seq results)
    [ag/ag-table results
     :autosize? true
     :ag-grid-options (merge wu/copyable-grid-options
                             {:columnDefs (column-defs (keys (first results)))})]
    [:div {:style {:display "flex" :justify-content "center" :align-items "center"}}
     [:i "No results"]]))

;;; ── Cards ────────────────────────────────────────────────────────────────

(defn query-card
  [project]
  [qbox/ui :sparql {:button-label "Query"
                    :project project
                    :examples (:examples (project-config project))}])

(defn source-link
  [project]
  (let [{:keys [source-label source-url]} (:db (project-config project))]
    (when source-label
      [:a {:href source-url} source-label])))

(defn ui
  [project]
  (let [{:keys [results query text error]} @(rf/subscribe [:qbox-response :sparql])]
    [:div
     [:div.alert.alert-info
      "Experimental: SPARQL against " [source-link project]
      " -- a small hand-picked musicians/recordings subset (see design/sparql.md)."]
     [:div.hstack.istack.m-3.gap-3 {:style {:height "90%"}}
      [:div {:style {:max-width "600px" :min-width "600px"}}
       [cards/cards :sparql-cards
        [{:name :query :view (fn [] (query-card project))}
         (when query {:name :sparql :view (fn [] [qbox/query-editor :sparql project "SPARQL"])})
         (when text {:name :plan :view (fn [] [:div.m-3 (md/render text)])})
         (when error {:name :error :open? true
                      :view (fn [] [:div.alert.alert-warning [:pre {:style {:text-wrap "auto"}} error]])})]]]
      [:div.vstack {:style {:min-width "800px"}}
       [:div {:style {:height "90%"}}
        [grid-view results]]]]]))
