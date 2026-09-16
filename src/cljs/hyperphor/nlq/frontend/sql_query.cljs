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

(defn link-field-cell-renderer
  "Cell renderer for a :link-template column (see schema/resolved-column-
   info): shows this column's own value, hyperlinked via link-template — a
   {{var}} URL template. {{self}} is this column's own value; {{project}}
   is the current NLQ project; any other var must be a key in
   link-field-cols (a resolved {template-var-kw -> sibling column} map,
   possibly empty for a field linking off only its own value)."
  [project link-field-cols link-template]
  (fn [params]
    (let [self-value (.-value params)
          ;; Every var goes into a URL — a query param today, maybe a path
          ;; segment for Cirro later — so encode each rather than assume
          ;; it's already URL-safe (a gs: path or Cirro path can carry #,
          ;; &, +, etc, any of which would otherwise truncate or corrupt
          ;; the URL).
          vars (into {:self (js/encodeURIComponent self-value)
                      :project (js/encodeURIComponent project)}
                     (map (fn [[var-name col]]
                            [var-name (js/encodeURIComponent (aget (.-data params) (name col)))]))
                     link-field-cols)
          href (u/expand-template link-template vars :allow-missing? true)
          label (str self-value)]
      (reagent/as-element
       [:span.ag-cell-wrap-text
        [:a.ent-ext
         {:href href
          ;; No :target "_ext" here, unlike external-link above — this href
          ;; always responds Content-Disposition: attachment (see
          ;; gs/download-response), which downloads in place without leaving
          ;; the current page. Forcing a new tab for it just makes the
          ;; browser flash a blank tab open-then-closed around the download.
          ;; A plain <a> download gives no feedback while the server-side
          ;; GCS fetch gets going, which reads as a dead click on anything
          ;; but a tiny file. Swap the label to a transient "Downloading…"
          ;; on click, direct DOM mutation rather than reagent state — this
          ;; is a one-off React element ag-grid mounts per cell, not part of
          ;; the normal reactive tree, so an atom deref here isn't reliably
          ;; watched. Left as native navigation (no preventDefault, no
          ;; fetch+blob) so an arbitrarily large file still streams straight
          ;; to disk instead of buffering in browser memory.
          :on-click (fn [e]
                      (let [el (.-currentTarget e)]
                        (set! (.-textContent el) "Downloading…")
                        (js/setTimeout #(set! (.-textContent el) label) 3000)))}
         label]]))))

;;; fetch + blob + a synthetic <a download>, so a caller can show progress
;;; while a slow backend fetch/stream (eg zipping several GCS blobs) is in
;;; flight — a plain <a> gives no such signal. Deliberately NOT used for
;;; individual link-template cells above: those proxy a single file
;;; straight through (gs/download-response, verified byte-exact), and
;;; fetch+blob would buffer the whole file in browser memory before saving
;;; it, which is fine for a small file but not for an arbitrarily large one
;;; (eg a multi-GB dataset file) — a regression not worth trading for a
;;; spinner. Download All (below) already has to build the zip fully before
;;; the first byte, and is bounded by what the query actually selected, so
;;; that tradeoff doesn't apply there.
(defn fetch-and-save!
  "Fetches `href`, saves the response as `filename` via a synthetic <a
   download>, and swaps `state` from :downloading back to nil/absent when
   done (success or failure). `opts` is passed through to js/fetch (eg
   {:method \"POST\" :body ...})."
  [state key href filename & [opts]]
  (swap! state assoc key :downloading)
  (-> (js/fetch href (clj->js (or opts {})))
      (.then (fn [response]
               (if (.-ok response)
                 (.blob response)
                 (throw (js/Error. (str "Download failed: " (.-status response)))))))
      (.then (fn [blob]
               (let [url (js/URL.createObjectURL blob)
                     a (.createElement js/document "a")]
                 (set! (.-href a) url)
                 (set! (.-download a) filename)
                 (.click a)
                 ;; Some browsers (notably Safari) haven't finished handing
                 ;; the blob to the download manager by the time .click()
                 ;; returns; revoking immediately can cancel the save.
                 (js/setTimeout #(js/URL.revokeObjectURL url) 1000))))
      (.catch (fn [err] (js/console.error "Download failed:" err)))
      (.finally (fn [] (swap! state dissoc key)))))

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

(defn field-col-for-kind
  "The column (in this result set) carrying kind's `field`, if any — used to
   resolve a :link-fields entry to the sibling column that actually holds
   it. nil if that field wasn't selected in this particular query. Requires
   a nil
   :ref-kind, same hazard as inspected-kind-icon below: an FK column's :kind
   is its *owning* table, not the kind it points at, so without this guard a
   same-named FK could be mistaken for the sibling field itself."
  [kind field columns-info]
  (ffirst (filter (fn [[_ info]] (and (= kind (:kind info)) (= field (:field info))
                                       (nil? (:ref-kind info))))
                   columns-info)))

;;; ── Download All ──────────────────────────────────────────────────────────
;;; Schema-driven, like the rest of this ns: keyed off "does this result set
;;; have a :link-template column" — self-linking (eg a gs: path) or
;;; :link-fields (eg Cirro's dataset+path pair) both included, never a
;;; specific kind/field name. The backend decides per item how to fetch it
;;; (a :dataset key means Cirro, its absence means the self-link backend,
;;; eg gs:) — this ns never builds or interprets a URL, just collects the
;;; raw values a schema-declared link needs.

(defn link-cols
  "[{:col ... :field-cols {var-name -> col}} ...] for every :link-template
   column in this result set — what Download All resolves per row into a
   spec to send the backend. Skips a column whose declared :link-fields
   aren't all resolvable in this result set (same fallback column-def
   already applies per-cell); field-cols is {} for a self-link."
  [columns-info]
  (keep (fn [[col info]]
          (when (:link-template info)
            (let [field-cols (u/map-values #(field-col-for-kind (:kind info) % columns-info)
                                            (or (:link-fields info) {}))]
              (when (every? val field-cols)
                {:col col :field-cols field-cols}))))
        columns-info))

(defn download-all-specs
  "Per-row {:self ... var-name ...} specs across `cols` (link-cols' result)
   in `results` — :self is the link column's own value, other keys are its
   resolved sibling values (eg :dataset for a Cirro link). Non-blank :self,
   deduplicated."
  [results cols]
  (->> results
       (mapcat (fn [row]
                 (map (fn [{:keys [col field-cols]}]
                        (into {:self (get row col)}
                              (map (fn [[var-name sibling-col]] [var-name (get row sibling-col)]))
                              field-cols))
                      cols)))
       (remove #(str/blank? (:self %)))
       distinct))

;;; {:all :downloading} while a Download All zip fetch is in flight — one key
;;; since only one Download All button is ever on screen at a time.
(defonce download-all-state (reagent/atom {}))

;;; POST (not a plain <a>/query-string GET) since `specs` can be many/long —
;;; one zip built server-side and streamed back as one attachment, so no
;;; popup-blocker hazard like N individual link clicks would have. Goes
;;; through fetch-and-save! (not a bare <form> submit) so the button can
;;; show progress while the zip is being built, which is slower than a
;;; single file.
;;;
;;; URLSearchParams (application/x-www-form-urlencoded), not FormData
;;; (multipart/form-data): okc's /api routes (hyperphor.way.handler,
;;; ring-defaults' api-defaults) only enable :urlencoded params, not
;;; :multipart — a FormData body silently parses to no params at all. specs
;;; is a heterogeneous list of maps (a gs: self-link has just :self, a
;;; Cirro pair also has :dataset), so it goes in as one JSON-encoded field
;;; rather than repeated same-key fields, which can't carry that shape.
(defn submit-download-all!
  [project specs]
  (let [body (js/URLSearchParams.)]
    (.append body "payload" (js/JSON.stringify (clj->js {:project project :specs specs})))
    (fetch-and-save! download-all-state :all "/api/download/zip" "download.zip"
                      {:method "POST" :body body})))

(defn download-all-button
  "\"Download All\" button, shown only when this result set has at least one
   :link-template column (see link-cols)."
  [project results columns-info]
  (when-let [cols (seq (link-cols columns-info))]
    (let [downloading? (= :downloading (get @download-all-state :all))]
      [:button.btn.btn-primary.mt-2
       {:style {:align-self "flex-start" :flex-shrink 0}
        :disabled downloading?
        :on-click #(submit-download-all! project (download-all-specs results cols))}
       (if downloading?
         [:span [qbox/spinner 1] " Downloading…"]
         "Download All")])))

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
        ;; A :link-template column (eg :file's :name => Cirro, :warehouse =>
        ;; itself) may need sibling columns' values too (:link-fields, eg
        ;; :name's Cirro dataset id) — only usable if every named sibling
        ;; actually resolved to a real column in this result set (a missing
        ;; one — same reasoning as label-id-col above — would build a
        ;; broken URL, so treat that as "no renderer" instead).
        link-field-template (:link-template info)
        link-field-cols (when link-field-template
                          (u/map-values #(field-col-for-kind (:kind info) % columns-info)
                                        (or (:link-fields info) {})))
        link-fields-ok? (every? val link-field-cols)
        ;; A resolved column always sits under a group header naming its
        ;; :kind (see ag-column-defs), so the kind part of the raw column
        ;; name (eg subject_sex's "subject") is redundant there — show just
        ;; the field; the group header carries the kind's icon, not this
        ;; row. Unresolved columns have no group header for context, so
        ;; keep the full raw name.
        label (if-let [field (:field info)] (name field) (name col))
        renderer (cond
                   link-template (external-link-renderer link-template)
                   ;; A :link-template column wins over inspect/label
                   ;; handling — it's an explicit schema opt-in for this
                   ;; exact column (eg a file's name should download, not
                   ;; drill down), so there's no case today where both
                   ;; apply to the same column and disagree.
                   (and link-field-template link-fields-ok?)
                   (link-field-cell-renderer project link-field-cols link-field-template)
                   inspect-kind  (inspect-cell-renderer project inspect-kind)
                   (and label-kind label-id-col)
                   (label-inspect-cell-renderer project label-kind label-id-col))]
    (cond-> {:field col
             :headerName label}
      (:doc info) (assoc :headerTooltip (:doc info))
      renderer    (assoc :cellRenderer renderer))))

(defn effective-kind
  "The kind a column is grouped and iconified under: its FK :ref-kind if it
   has one, else its own owning :kind. Must agree with column-def's :icon
   lookup (same effective-kind logic in schema.clj's resolved-column-info)
   — otherwise a column can show one kind's icon while sitting in a
   different kind's group (eg a sample_subject-style FK column showing the
   referenced kind's icon but grouped with its owning table's own columns)."
  [info]
  (or (:ref-kind info) (:kind info)))

(defn column-group-key
  "Columns with the same effective kind share a key (to be grouped
   together); an unresolved column gets a key unique to itself, so it
   doesn't merge or move."
  [col columns-info]
  (or (effective-kind (get columns-info col)) col))

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
  [kind icon]
  (str (when icon (str icon " ")) (some-> (name kind) (str/replace "-" " ") str/capitalize)))

;;; ag-grid only renders the expand/collapse toggle once some child is
;;; marked :columnGroupShow "open" — without it a group header can't be
;;; collapsed, hence marking every member but the first that way below.
(defn ag-column-defs
  "ag-grid columnDefs from `column-groups`: a real semantic-type group
   renders as a spanning, collapsible ag-grid column group; a column with no
   resolved type renders as a plain top-level column, not wrapped."
  [project cols columns-info & [group-priority]]
  (mapv (fn [[gk members]]
          (let [real-kind? (= gk (effective-kind (get columns-info (first members))))
                child-defs (map-indexed
                            (fn [i col]
                              (cond-> (column-def project col columns-info)
                                (and real-kind? (pos? i)) (assoc :columnGroupShow "open")))
                            members)]
            (if real-kind?
              {:groupId (name gk)
               :headerName (group-label gk (:icon (get columns-info (first members))))
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
                      :placeholder "Type a visualization request, or choose an example"
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
       [download-all-button project results columns]
       ;; Not gated on `results`: a visualize attempt can produce an error (e.g.
       ;; "no query results yet") even when there's no main-query data to show,
       ;; and that error still needs to render. Bounded + scrollable (rather
       ;; than the fix above's flex-shrink:0, which would just push this
       ;; div's overflow below the fold instead) so an oversized chart stays
       ;; fully reachable without blowing out the page layout.
       [:div {:style {:max-height "50%" :overflow "auto"}}
        [nlqv/ui results :sql-vizq]]]]]))
