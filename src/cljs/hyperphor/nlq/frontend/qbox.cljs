(ns hyperphor.nlq.frontend.qbox
  (:require [re-frame.core :as rf]
            [hyperphor.way.form :as form]
            [hyperphor.way.web-utils :as wu]
            [hyperphor.way.api :as api]
            [hyperphor.multitool.core :as u]
            )
  )

;;; A generic UI component for NL queries with examples.
;;; TODO examples should be optional 
;;; TODO incorporate error handling (which logically should not be a separate card)
;;; TODO maybe incorporate code result

;;; Patched, already in way
(defn spinner
  "Make a spinner. Size 10 is big, size 1 or 2 is good"
  [& [size]]
  (let [size (or size 10)]
    ;; [:div.text-center
    [:div.spinner-border {:role "status"
                          :style {:width (str size "em")
                                  :height (str size "em")
                                  :flex-shrink 0
                                  :border-width (str (/ size 10.0) "em")}}
     [:span.visually-hidden "Loading..."]]))


(defn ui
  [id & {:keys [examples button-label project] :or {button-label "Go!"}}]
  (let [query  @(rf/subscribe [:form-field-value [id :query-text]])]

  [:div.vstack
   [:div.hstack
    [form/form-field {:type :textarea
                      :path [id :query-text]
                      :style {:width 600
                              :padding "5px"
                              :margin-right "5px"
                              :font-family "sans-serif"
                              }
                      }]
    [:button.btn.btn-primary
     {:on-click #(rf/dispatch [:qbox-query id project query])
      :style {:margin-right "3px"}} button-label]
    (when @(rf/subscribe [:qbox-spin? id])
      [spinner 2])]
   (wu/select-widget
    (u/keyword-conc id :example)
    nil
    #(rf/dispatch [:qbox-recall-example id %])
    (map :nl examples)
    "Choose an example or type a query above"
    false
    {:width "95%"
     :box-sizing "border-box"
     :padding "5px"
     :margin-left "20px"
     :font-style "italic"
     :font-size "15px"}
    ) 
   ]))

(rf/reg-event-db
 :qbox-query
 (fn [db [_ id project query-text]]
   (api/ajax-get "/api/qbox/query" {:params {:id id :project project :query query-text}
                                    :handler (fn [response] (rf/dispatch [:qbox-query-response id response]))})
   (assoc-in db [:qbox id :spin?] true)))

;;; ── Editable generated query (design/TODO.md) ──────────────────────────────
;;; Lets the user edit the generated SQL/SPARQL/etc and rerun it as-is,
;;; skipping NL->query generation, via the backend's generic :nlq-requery
;;; data method (see hyperphor.nlq.generate/requery-endpoint). Kept at its own
;;; form path (`:query-code`, distinct from the NL textarea's `:query-text`)
;;; and seeded only when a response lands (`:qbox-query-response` below) --
;;; never rebuilt from the response on every render -- so typing in it doesn't
;;; fight React over cursor position (the bug that sank the old nlflame version
;;; of this feature).
(defn query-editor
  [id project label]
  [:div.vstack.m-3
   [form/form-field {:type :textarea
                     :path [id :query-code]
                     :style {:width "100%"
                             :height "300px"
                             :font-family "monospace"}}]
   [:button.btn.btn-primary.mt-2
    {:style {:align-self "flex-start"}
     :on-click #(rf/dispatch [:qbox-requery id project])}
    (str "Run " label)]])

(rf/reg-event-db
 :qbox-requery
 (fn [db [_ id project]]
   (api/api-get "/data" {:params {:data-id "nlq-requery"
                                  :project project
                                  :query-type (name id)
                                  :query (get-in db [:form id :query-code])}
                         :handler (fn [response] (rf/dispatch [:qbox-query-response id response]))})
   (assoc-in db [:qbox id :spin?] true)))

(rf/reg-sub
 :qbox-results
 (fn [db [_ id]]
   (get-in db [:qbox id :response])))

(rf/reg-sub
 :qbox-spin?
 (fn [db [_ id]]
   (get-in db [:qbox id :spin?])))


(rf/reg-event-db
 :qbox-recall-example
 (fn [db [_ id text]] 
   (-> db
       (assoc-in [:form id :query-text] text)
       )))

;;; sql_query.cljs/sparql_query.cljs register their card stack as
;;; `<id>-cards` (eg :sql -> :sql-cards) -- used below to force the error
;;; card open on response, even if the user has since collapsed it (as they
;;; will have, to reach the :sql card's query-editor and hit Run in the
;;; first place) -- otherwise a requery syntax error silently vanishes into
;;; a closed card and the user just sees the spinner stop.
(defn- cards-id-for
  [id]
  (keyword (str (name id) "-cards")))

(rf/reg-event-db
 :qbox-query-response
 (fn [db [_ id response]]
   (when (:error response)
     (rf/dispatch [:open-card (cards-id-for id) :error]))
   (cond-> db
     true (assoc-in [:qbox id :spin?] false)
     true (assoc-in [:qbox id :response] response)
     ;; Seed the editable-query pane (see query-editor/:qbox-requery) with
     ;; the query this response just ran, so edit+rerun starts from it. Not
     ;; unconditional -- a vis-query response (:sql-vizq) carries :viz-spec/
     ;; :viz-text instead of :query, and would otherwise blank the field.
     (:query response) (assoc-in [:form id :query-code] (:query response)))))

(rf/reg-sub
 :qbox-response
 (fn [db [_ id]]
   (get-in db [:qbox id :response])))

(rf/reg-sub
 :qbox-results
 (fn [db [_ id]]
   (get-in db [:qbox id :response :results])))
