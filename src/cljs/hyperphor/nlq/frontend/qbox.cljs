(ns hyperphor.nlq.frontend.qbox
  (:require [re-frame.core :as rf]
            [reagent.core :as reagent]
            ["@mui/material" :as m]
            [hyperphor.way.form :as form]
            [hyperphor.way.api :as api]
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


;;; Query input as a single MUI Autocomplete combobox (freeSolo):
;;; Local to this ns rather than promoted to way.material, since nothing
;;; else uses it yet -- promote there if a second consumer wants it.
(def autocomplete-adapter (reagent/adapt-react-class m/Autocomplete))

;;; Deliberately UNCONTROLLED: no :value/:defaultValue/:inputValue at all,
;;; just :onInputChange forwarding into [id :query-text]. Controlling it --
;;; whether from the [id :query-text] subscription (original) or a
;;; synchronously-updated local reagent atom (tried next) -- reproduced the
;;; caret-jumps-to-end bug identically either way. Per Reagent's own docs
;;; (doc/ControlledInputs.md): Reagent has a built-in fix for exactly this
;;; class of bug (its async rendering can leave a controlled DOM input a
;;; render behind, and naively re-applying `.value` then snaps the caret to
;;; the end) -- but that fix only patches inputs Reagent itself creates via
;;; hiccup (`[:input ...]`, `[:textarea ...]`). This box is a MUI Autocomplete
;;; + TextField reached through `adapt-react-class`/`create-element`; Reagent
;;; never sees the underlying <input>, so its fix can't apply, no matter how
;;; fast the value we feed it updates. Their own documented workaround for
;;; exactly this situation is to stop controlling it and let the DOM own the
;;; text, which is what this does. Picking an example from the dropdown still
;;; works: MUI manages its own internal (uncontrolled) inputValue and fires
;;; onInputChange for that too, same as for a keystroke.
(defn ui
  [id & {:keys [examples button-label project placeholder]
         :or {button-label "Go!" placeholder "Type a query, or choose an example"}}]
  (let [query @(rf/subscribe [:form-field-value [id :query-text]])
        set-query! #(rf/dispatch [:set-form-field-value [id :query-text] %])]
    [:div.vstack
     [:div.hstack
      [autocomplete-adapter
       {:freeSolo true
        :options (map :nl examples)
        :onInputChange (fn [_event value _reason] (set-query! value))
        :style {:width 600 :margin-right "5px"}
        ;; renderInput is a render prop MUI calls itself, not a plain hiccup
        ;; slot -- must return a real React element. `params` carries ref/
        ;; ARIA/event-handler props Autocomplete needs on the underlying
        ;; input; merge via raw JS (not js->clj, which would corrupt the
        ;; ref inside it) rather than converting to/from cljs.
        :renderInput (fn [params]
                       (reagent/create-element
                        m/TextField
                        (js/Object.assign #js {} params
                                           #js {:placeholder placeholder
                                               :multiline true
                                               :minRows 2})))}]
      [:button.btn.btn-primary
       {:on-click #(rf/dispatch [:qbox-query id project query])
        :style {:margin-right "3px"}} button-label]
      (when @(rf/subscribe [:qbox-spin? id])
        [spinner 2])]]))

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
