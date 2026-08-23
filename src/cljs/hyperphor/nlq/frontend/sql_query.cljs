(ns hyperphor.nlq.frontend.sql-query
  (:require [re-frame.core :as rf]
            [reagent.core :as reagent]
            [clojure.string :as str]
            [hyperphor.way.aggrid :as ag]
            [hyperphor.way.api :as api]
            [hyperphor.way.cards :as cards]
            [hyperphor.way.markdown :as md]
            [hyperphor.way.ui.config :as config]
            [hyperphor.multitool.core :as u]
            [hyperphor.nlq.frontend.qbox :as qbox]
            [hyperphor.nlq.frontend.nlq-viz :as nlqv]
            [hyperphor.nlq.frontend.utils :as wu]))

(defn project-config
  "This project's :nlq config entry (config.edn), as shipped to the client."
  [project]
  (->> (config/config :nlq)
       (filter #(= (:name %) project)) first))

;;; ── Semantic column rendering ─────────────────────────────────────────────
;;; Uses the :columns metadata the backend derives from the Alzabo schema
;;; (schema/columns-info, keyed off the kind_field naming convention every
;;; generated SQL column follows) to add header hover-help and light
;;; type-aware rendering. See okc's design/semantic-columns.md. Icons and
;;; external-link templates are not defined here — they're :icon/
;;; :external-link-template attributes on the kind in the project's own
;;; Alzabo schema, alongside :doc/:reference?; the backend just resolves
;;; which kind applies to a given column. No domain-specific code (eg
;;; "genes link to genecards.org") lives here — that's schema data.

(defn external-link
  "Generic external-link renderer, driven by a column's schema-supplied
   :external-link-template. Distinct styling (.ent-ext) from in-app object-
   inspector links (.ent), so it's visually clear a click leaves the app."
  [template value]
  [:a.ent-ext {:href (u/expand-template template {:value value} :allow-missing? true)
               :target "_ext"}
   (str value)])

(defn external-link-renderer
  [template]
  (fn [params]
    (reagent/as-element
     [:span.ag-cell-wrap-text (external-link template (.-value params))])))

(defn id-column?
  "True for a group's own identifier column (Alzabo :field :id, or by name
   for unresolved columns) — not a foreign key pointing at that kind."
  [col info]
  (or (= (:field info) :id)
      (str/ends-with? (name col) "_id")))

;;; :inspectable? is set live by the backend (generate.clj's endpoint
;;; calling inspect/annotate-inspectable) off the same table lookup the
;;; inspector itself uses at click-time, since table coverage varies by
;;; project and can grow over time — not something to hardcode here.
(defn inspectable-kind
  "The kind this column's value identifies an entity of, if any — its own
   :kind for that kind's own id column, or its :ref-kind for a foreign key.
   nil otherwise, including a kind with no queryable table right now."
  [col info]
  (when (:inspectable? info)
    (cond
      (and (:kind info) (id-column? col info)) (:kind info)
      (:ref-kind info) (:ref-kind info))))

(defn inspect-cell-renderer
  [project kind]
  (fn [params]
    (reagent/as-element
     [:span.ag-cell-wrap-text
      [:a.ent {:href "#"
               :on-click (fn [e]
                           (.preventDefault e)
                           (rf/dispatch [:sql-inspect project (name kind) (.-value params)]))}
       (str (.-value params))]])))

(defn id-col-for-kind
  "The column (in this result set) carrying kind's own id/FK value — needed
   when a :label column (see label-inspect-cell-renderer) is clicked instead,
   since the inspector always operates on the id. nil if no column in this
   result actually resolved to kind's id/FK."
  [kind columns-info]
  (ffirst (filter (fn [[col info]] (= kind (inspectable-kind col info))) columns-info)))

;;; Always an in-app link, even for a kind with an :external-link-template —
;;; a study's title is how a user finds/recognizes it in-app; its id/FK
;;; columns are what carry the external link instead (see column-def /
;;; schema/column-info's :label? gating).
(defn label-inspect-cell-renderer
  "Like inspect-cell-renderer, but for a kind's :label field (not its
   id/FK): shows the label's own value, using id-col (elsewhere in the same
   row) for the inspector lookup."
  [project kind id-col]
  (fn [params]
    (reagent/as-element
     [:span.ag-cell-wrap-text
      [:a.ent {:href "#"
               :on-click (fn [e]
                           (.preventDefault e)
                           (rf/dispatch [:sql-inspect project (name kind)
                                         (aget (.-data params) (name id-col))]))}
       (str (.-value params))]])))

(defn column-def
  [project col columns-info]
  (let [info  (get columns-info col)
        link-template (:external-link-template info)
        inspect-kind (inspectable-kind col info)
        ;; A :label? column (eg studies' brief_title) isn't itself an id/FK
        ;; — schema/column-info never puts an :external-link-template on it
        ;; — but should still open its owning kind's inspector, driven off a
        ;; sibling id/FK column in the same row rather than its own value.
        ;; Only usable if this particular result set actually has that
        ;; sibling id/FK column (id-col-for-kind can return nil — eg a query
        ;; that selects a label field without also selecting its id, or
        ;; whose kind has no queryable backing table right now): without a
        ;; real id-col the click handler would dispatch a nil id, so treat
        ;; this the same as "no renderer" rather than building a broken one.
        label-kind (when (:label? info) (:kind info))
        label-id-col (when label-kind (id-col-for-kind label-kind columns-info))
        icon  (:icon info)
        ;; A resolved column always sits under a group header naming its
        ;; :kind (see ag-column-defs), so the kind part of the raw column
        ;; name (eg subject_sex's "subject") is redundant there — show just
        ;; the field. Unresolved columns have no group header for context,
        ;; so keep the full raw name.
        label (if-let [field (:field info)] (name field) (name col))
        renderer (cond
                   link-template (external-link-renderer link-template)
                   inspect-kind  (inspect-cell-renderer project inspect-kind)
                   (and label-kind label-id-col)
                   (label-inspect-cell-renderer project label-kind label-id-col))]
    (cond-> {:field col
             :headerName (str (when icon (str icon " ")) label)}
      (:doc info) (assoc :headerTooltip (:doc info))
      renderer    (assoc :cellRenderer renderer))))

(defn column-group-key
  "Columns with the same owning kind share a key (to be grouped together); an
   unresolved column gets a key unique to itself, so it doesn't merge or move."
  [col columns-info]
  (or (:kind (get columns-info col)) col))

;;; A stable group-by, not a resort, so unrelated/unresolved columns stay
;;; roughly where the query put them. `group-priority` is optional (eg a
;;; project's own :column-group-priority config) — nil means natural
;;; first-seen order, no domain-specific default baked in here.
(defn column-groups
  "[[group-key members] ...] in final left-to-right order: `group-priority`
   groups first, then every other group in first-appearance order. Members
   within a group are sorted id-first."
  [cols columns-info & [group-priority]]
  (let [group-key   (fn [col] (column-group-key col columns-info))
        groups      (group-by group-key cols)
        first-seen  (distinct (map group-key cols))
        present     (set first-seen)
        prioritized (filter present group-priority)
        rest-order  (remove (set prioritized) first-seen)]
    (for [gk (concat prioritized rest-order)]
      [gk (sort-by #(if (id-column? % (get columns-info %)) 0 1) (get groups gk))])))

(defn group-label
  [kind]
  (some-> (name kind) (str/replace "-" " ") str/capitalize))

;;; ag-grid only renders the expand/collapse toggle once some child is
;;; marked :columnGroupShow "open" — without it a group header can't be
;;; collapsed, hence marking every member but the first that way below.
(defn ag-column-defs
  "ag-grid columnDefs from `column-groups`: a real semantic-type group
   renders as a spanning, collapsible ag-grid column group; a column with no
   resolved type renders as a plain top-level column, not wrapped."
  [project cols columns-info & [group-priority]]
  (mapv (fn [[gk members]]
          (let [real-kind? (= gk (:kind (get columns-info (first members))))
                child-defs (map-indexed
                            (fn [i col]
                              (cond-> (column-def project col columns-info)
                                (and real-kind? (pos? i)) (assoc :columnGroupShow "open")))
                            members)]
            (if real-kind?
              {:groupId (name gk)
               :headerName (group-label gk)
               :openByDefault true
               :children (vec child-defs)}
              (first child-defs))))
        (column-groups cols columns-info group-priority)))

(defn sql-grid-view
  [project results columns-info]
  (if (seq results)                     ;TODO should be in way
    [ag/ag-table results
     :autosize? true
     :ag-grid-options (merge wu/copyable-grid-options
                             {:columnDefs (ag-column-defs project (keys (first results)) columns-info
                                                          (:column-group-priority (project-config project)))})]
    [:div {:style {:display "flex"
                   :justify-content "center"
                   :align-items "center"}}
     [:i "No results"]]
    ))

;;; ── Object inspector ─────────────────────────────────────────────────────
;;; Clicking an id/FK cell in the results grid opens a new card in this same
;;; left-hand card stack, showing the clicked entity's full row — transposed
;;; (one field per row) since it's a single record, not a table.

(defn transpose-row
  [project [col v] columns-info]
  (let [info  (get columns-info col)
        icon  (:icon info)
        label (if-let [field (:field info)] (name field) (name col))]
    {:label (str (when icon (str icon " ")) label)
     :value v
     :doc   (:doc info)
     ;; Which kind (if any) this field's value identifies an entity of — see
     ;; `inspectable-kind` — so the value cell can render as a drill-down
     ;; link (eg a sample's row showing sample_subject linking to that
     ;; subject) exactly like the main results grid does.
     :inspect-kind (inspectable-kind col info)
     ;; Same external-link-template as the main grid's column-def, so a
     ;; field with one (eg a gene column in a cancer-genomics schema)
     ;; showing up inside an inspected row still links out rather than
     ;; rendering as plain text.
     :external-link-template (:external-link-template info)}))

(defn inspector-value-renderer
  "Cell renderer for the inspector's Value column: a plain value, a schema-
   driven external link, or an in-app drill-down link (re-inspecting in
   place) — see `transpose-row`'s :external-link-template/:inspect-kind."
  [project]
  (fn [params]
    (let [value (.-value params)
          {:keys [inspect-kind external-link-template]} (js->clj (.-data params) :keywordize-keys true)]
      (cond
        external-link-template (reagent/as-element [:span.ag-cell-wrap-text (external-link external-link-template value)])
        inspect-kind
        (reagent/as-element
         [:span.ag-cell-wrap-text
          [:a.ent {:href "#"
                   :on-click (fn [e]
                               (.preventDefault e)
                               (rf/dispatch [:sql-inspect project (name inspect-kind) value]))}
           (str value)]])
        :else (str value)))))

(defn inspector-grid
  [project row columns-info]
  [ag/ag-table (->> row (map #(transpose-row project % columns-info)) (sort-by :label))
   :autosize? true
   :class "aggrid-inspector"
   ;; This is a single transposed record, not a browsable table — the
   ;; columns/filters tool panel has nothing useful to offer here.
   :ag-grid-options (merge wu/copyable-grid-options
                           {:sideBar false
                            :columnDefs [{:field :label :headerName "Field" :tooltipField "doc"
                                          :cellClass "fw-bold"}
                                         {:field :value :headerName "Value"
                                          :cellRenderer (inspector-value-renderer project)}]})])

;;; Must come from one of that kind's own (non-FK) columns — a column that
;;; IS a foreign key carries the *referenced* kind's icon instead, so
;;; matching on :kind alone without also requiring a nil :ref-kind could
;;; show the wrong icon.
(defn inspected-kind-icon
  "The Alzabo :icon for the kind currently shown in the inspector, if any."
  [kind columns-info]
  (some (fn [[_ info]] (when (and (= (:kind info) (keyword kind)) (nil? (:ref-kind info)))
                         (:icon info)))
        columns-info))

(defn inspector-pane
  []
  (let [{:keys [project kind id row columns loading?]} @(rf/subscribe [:sql-inspect])]
    (cond
      (not id)  [:div "Click an id to inspect it"]
      ;; Only the very first inspection ever has no prior row to fall back on;
      ;; every subsequent click keeps showing the previous entity (below) until
      ;; the new one lands, rather than blanking the grid in between.
      (not row) [qbox/spinner 2]
      :else [:div
             [:h3.inspect-head.alert.alert-primary
              (when-let [icon (inspected-kind-icon kind columns)] (str icon " "))
              kind " " id
              (when loading? [:span.ms-2 [qbox/spinner 1]])]
             [inspector-grid project row columns]])))

;;; Fetched explicitly from the click event below, rather than via way's
;;; generic :data/:fetch machinery (which triggers its refetch as a side
;;; effect of a *subscription* recomputing) — that pattern left stale data
;;; on screen indefinitely after clicking a second entity, since the fetch/
;;; invalidate dance depends on a subscription being deref'd at the right
;;; moment. An explicit fetch on the click itself, guarded against
;;; out-of-order responses by re-checking the click is still current when
;;; the response lands, is simpler to get right.
(rf/reg-event-db
 :sql-inspect
 (fn [db [_ project kind id]]
   (rf/dispatch [:open-card :sql-cards :inspector])
   (api/api-get "/data" {:params {:data-id "sql-inspect" :project project :kind kind :id id}
                         :handler (fn [response] (rf/dispatch [:sql-inspect-loaded project kind id response]))})
   ;; Deliberately keeps any prior :row/:columns in place (merge, not replace)
   ;; so the grid stays populated with the previous entity while this one loads.
   (update db :sql-inspect merge {:project project :kind kind :id id :loading? true})))

(rf/reg-event-db
 :sql-inspect-loaded
 (fn [db [_ project kind id {:keys [row columns]}]]
   (cond-> db
     (= (select-keys (:sql-inspect db) [:project :kind :id]) {:project project :kind kind :id id})
     (update :sql-inspect merge {:row row :columns columns :loading? false}))))

(rf/reg-sub
 :sql-inspect
 (fn [db _] (:sql-inspect db)))

(defn query-card
  [project]
  [qbox/ui :sql {:button-label "Query"
                 :project project
                 :examples (:examples (project-config project))}])

(defn viz-card
  [project results]
  [qbox/ui :sql-vizq {:button-label (if (empty? results) "Waiting for data" "Visualize")
                      :project project
                      :examples (:examples (project-config "Vegalite"))}])

(defn source-link
  "Hiccup link describing the data source a project's SQL runs against, entirely
   from the :db :source-label/:source-url in this project's :nlq config entry."
  [project]
  (let [{:keys [source-label source-url]} (:db (project-config project))]
    (when source-label
      [:a {:href source-url} source-label])))

(defn ui
  [project]
  (let [{:keys [results query text error columns]} @(rf/subscribe [:qbox-response :sql])]
    [:div
     [:div.alert.alert-info
      "SQL against " [source-link project]
      " — " project " tables"]
     [:div.hstack.istack.m-3.gap-3 {:style {:height "90%"}}
      [:div {:style {:max-width "600px" :min-width "600px"}}
       [cards/cards :sql-cards
        [{:name :query :view (fn [] (query-card project))}
         (when query {:name :sql :view (fn [] [qbox/query-editor :sql project "SQL"])})
         (when text {:name :plan :view (fn [] [:div.m-3 (md/render text)])})
         (when error {:name :error :open? true
                      :view (fn [] [:div.alert.alert-warning [:pre {:style {:text-wrap "auto"}} error]])})
         {:name :visualize :view (fn [] (viz-card project results))}
         {:name :inspector :view inspector-pane}]]]
      [:div.vstack {:style {:min-width "800px"}}
       ;; :flex-shrink 0 + :min-height guard against a real display bug: an
       ;; oversized viz sibling (eg an LLM-generated Vega-Lite spec with a
       ;; big :width/:height, or a wide facet grid) otherwise squeezes this
       ;; grid all the way to 0 rows. ag-grid's own internal overflow gives
       ;; it a flexbox "automatic minimum size" of 0, so an unconstrained,
       ;; unshrinkable sibling is free to claim 100% of the shared space.
       [:div {:style {:height "50%" :min-height "300px" :flex-shrink 0}}
        [sql-grid-view project results columns]]
       ;; Not gated on `results`: a visualize attempt can produce an error (e.g.
       ;; "no query results yet") even when there's no main-query data to show,
       ;; and that error still needs to render. Bounded + scrollable (rather
       ;; than the fix above's flex-shrink:0, which would just push this
       ;; div's overflow below the fold instead) so an oversized chart stays
       ;; fully reachable without blowing out the page layout.
       [:div {:style {:max-height "50%" :overflow "auto"}}
        [nlqv/ui results :sql-vizq]]]]]))
