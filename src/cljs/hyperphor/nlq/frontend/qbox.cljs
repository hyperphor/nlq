(ns hyperphor.nlq.frontend.qbox
  (:require [re-frame.core :as rf]
            [reagent.core :as reagent]
            ["@mui/material" :as m]
            ["react-dom" :refer [flushSync]]
            [hyperphor.way.form :as form]
            [hyperphor.way.api :as api]
            [hyperphor.way.web-utils :as wu]
            )
  )

;;; A generic UI component for NL queries with examples.
;;; TODO examples should be optional 
;;; TODO incorporate error handling (which logically should not be a separate card)
;;; TODO maybe incorporate code result

;;; Query input as a single MUI Autocomplete combobox (freeSolo):
;;; Local to this ns rather than promoted to way.material, since nothing
;;; else uses it yet -- promote there if a second consumer wants it.
(def autocomplete-adapter (reagent/adapt-react-class m/Autocomplete))

;;; Controlled from [id :query-text] via :inputValue/:onInputChange, same
;;; path everything else already reads/writes -- so the box always
;;; re-renders from app state and can never go blank just because it got
;;; unmounted/remounted (eg by a parent card list rebuilding its :view
;;; closures on every render -- see sql_query.cljs/sparql_query.cljs). This
;;; was briefly made fully uncontrolled instead, to chase a
;;; caret-jumps-to-end bug -- but that traded a caret bug for silently
;;; losing typed text, which is worse. Deliberately no :value here (unlike
;;; the original controlled version): for a freeSolo box the typed text
;;; isn't a "selected option", and controlling both makes Autocomplete treat
;;; every keystroke as picking a new value, which re-syncs :inputValue via
;;; its own internal effect outside Reagent's render cycle -- one more path
;;; that can reset the caret.
;;;
;;; Even with only :inputValue controlled, re-applying a string the DOM
;;; already holds can still snap the caret to the end -- the same class of
;;; bug Reagent's own docs describe and patch for (doc/ControlledInputs.md),
;;; but only for inputs Reagent creates directly via hiccup; this one is a
;;; MUI component reached through adapt-react-class/create-element, so
;;; Reagent never sees the underlying <input>/<textarea> to apply its fix.
;;;
;;; Do the same fix by hand -- capture the caret off the native input event,
;;; then restore it -- but restoring after the fact only works if the DOM
;;; already reflects the keystroke that just happened. Confirmed by logging
;;; (see git history) that it doesn't, reliably: this app mounts via
;;; react-dom/client's createRoot (a concurrent root), so a re-render
;;; triggered from outside React's own event handling (which is what a
;;; re-frame dispatch does -- it lands via goog.async.nextTick, not inside
;;; React's synthetic event) gets scheduled rather than committed
;;; immediately. Waiting via reagent/after-render, or even stacking extra
;;; requestAnimationFrame callbacks on top, did not reliably land after that
;;; commit -- the DOM was still showing the *previous* keystroke's value
;;; every time, regardless of how many frames were given.
;;;
;;; The reliable fix is to not wait at all: force the whole
;;; dispatch->app-db->reaction->re-render->DOM-commit chain to happen
;;; synchronously, inside this same event handler, via dispatch-sync +
;;; reagent/flush (which forces Reagent's pending render queue to run now
;;; instead of on the next animation frame) wrapped in React's flushSync
;;; (which is what actually forces a concurrent root to commit immediately
;;; instead of scheduling it). With that, the DOM already has the new value
;;; by the time we restore the caret, so no waiting/guessing is needed.
(defonce ^:private caret-positions (atom {}))

(defn- capture-caret!
  [id event]
  (let [node (.-target event)]
    (swap! caret-positions assoc id
           {:node node :start (.-selectionStart node) :end (.-selectionEnd node)})))

(defn- restore-caret!
  [id]
  (when-let [{:keys [node start end]} (get @caret-positions id)]
    (when (.-isConnected node)
      (.setSelectionRange node start end))))

(defn ui
  [id & {:keys [examples button-label project placeholder]
         :or {button-label "Go!" placeholder "Type a query, or choose an example"}}]
  (let [query @(rf/subscribe [:form-field-value [id :query-text]])]
    [:div.vstack
     [:div.hstack
      [autocomplete-adapter
       {:freeSolo true
        :options (map :nl examples)
        :inputValue (or query "")
        :onInputChange (fn [event value reason]
                         ;; Only a keystroke needs its caret preserved --
                         ;; picking an example (reason "reset"/"selectOption")
                         ;; has no caret position worth restoring, and its
                         ;; event target isn't necessarily the text field.
                         (when (= reason "input") (capture-caret! id event))
                         ;; flushSync+dispatch-sync+reagent/flush forces the
                         ;; dispatch -> app-db -> reaction -> re-render -> DOM
                         ;; commit chain to finish synchronously, right here,
                         ;; instead of being scheduled for later (see comment
                         ;; above caret-positions) -- so by the time we
                         ;; restore-caret! below, the DOM already has the new
                         ;; value and the caret position is meaningful.
                         (flushSync (fn []
                                      (rf/dispatch-sync [:set-form-field-value [id :query-text] value])
                                      (reagent/flush)))
                         (when (= reason "input") (restore-caret! id)))
        :disablePortal true
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
        [wu/spinner 2])]]))

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
   (if (= id :sql-vizq)
     ;; Viz spec lives entirely on the client — parse the edited JSON and
     ;; write it straight into the response atom; no server round-trip needed.
     (let [json-str (get-in db [:form id :query-code])]
       (try
         (assoc-in db [:qbox id :response :viz-spec]
                   (js->clj (js/JSON.parse json-str) :keywordize-keys true))
         (catch js/Error _
           (assoc-in db [:qbox id :response :error] "Invalid Vega-Lite JSON"))))
     (do
       (api/api-get "/data" {:params {:data-id "nlq-requery"
                                      :project project
                                      :query-type (name id)
                                      :query (get-in db [:form id :query-code])}
                             :handler (fn [response] (rf/dispatch [:qbox-query-response id response]))})
       (assoc-in db [:qbox id :spin?] true)))))

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
     ;; the query this response just ran, so edit+rerun starts from it.
     (:query response)    (assoc-in [:form id :query-code] (:query response))
     (:viz-spec response) (assoc-in [:form id :query-code]
                                    (js/JSON.stringify (clj->js (:viz-spec response)) nil 2)))))

(rf/reg-sub
 :qbox-response
 (fn [db [_ id]]
   (get-in db [:qbox id :response])))

(rf/reg-sub
 :qbox-results
 (fn [db [_ id]]
   (get-in db [:qbox id :response :results])))
