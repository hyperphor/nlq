(ns hyperphor.nlq.visgen
  (:require [hyperphor.nlq.generate :refer :all] ;TEMP
            [clojure.string :as str]
            [clojure.data.json :as json]
            [hyperphor.nlq.config :as nlq]
            [hyperphor.ellum.util :as llm-util]
            ))

;;; Vis generation has its own "Vegalite" entry in config.edn's :nlq (own :llm,
;;; own :examples) — it's not a data project, so *project-conf* here is always
;;; bound to that entry rather than whichever data project the results came from.
(def viz-project-name "Vegalite")

;;; Transform data to more natural field names for Vega. `kind|field` is a
;;; flattened-key convention some consuming apps use for hierarchical result
;;; data (eg a flattened Datomic pull result) — this is a no-op for any key
;;; without a `|` in it, so it's harmless for plain SQL/SPARQL result rows.
(defn naturalize-data
  [data]
  (map (fn [row]
         (reduce-kv (fn [acc k v]
                      (let [natural-key (if (keyword? k)
                                          (let [name-str (name k)]
                                            (if (str/includes? name-str "|")
                                              (keyword (last (str/split name-str #"\|")))
                                              k))
                                          k)
                            natural-val (cond
                                          (and (map? v) (:db|ident v))
                                          (last (str/split (name (:db|ident v)) #"/"))

                                          (vector? v)
                                          (mapv (fn [item]
                                                  (if (and (map? item) (:db|ident item))
                                                    (last (str/split (name (:db|ident item)) #"/"))
                                                    item)) v)

                                          :else v)]
                        (assoc acc natural-key natural-val)))
                    {} row))
       data))

;;; Shares llm-complete/example-queries/*project-conf* with the SQL/SPARQL
;;; generation methods in generate.clj.
(defn viz-generate-with-data
  [nl data]
  (let [natural-data (naturalize-data data)
        sample-data (take 5 natural-data)
        field-names (pr-str (map name (keys (first natural-data))))
        system (get-in *project-conf* [:llm :system]
                       "You are a data visualization expert.")]
    (-> (llm-complete
         [
          {:role :user :content (str "Available data fields: " field-names)}
          {:role :user :content (str "Sample data: " (json/write-str sample-data))}
          {:role :user :content (str "Example visualizations: " (json/write-str (example-queries :vega)))}
          {:role :user :content "Return only the Vega-Lite JSON specification in a code block. Use field names without prefixes."}
          {:role :user :content (str "Create a Vega-Lite visualization for: " nl)}]
         :system system)
        llm-util/extract-json)))

;;; Combined endpoint that generates a visualization for the most recently fetched
;;; data. `project` is the *data* project (for the log's :project field) —
;;; generation itself always uses the "Vegalite" config entry.
(defn viz-endpoint
  [project nl]
  (binding [*project-conf* (nlq/project-named viz-project-name)
            *last-llm-call* (atom nil)]
    (let [data (:results @last-query-response)    ;; TEMP CROCK OF COURSE
          response-object
          (if (empty? data)
            ;; No point asking the LLM to invent a chart from zero fields — that
            ;; just produces a spec that silently renders as an empty chart. Run
            ;; a real query first (or, right now, the last one errored out before
            ;; getting any :results — see `endpoint`).
            {:nl nl :error (ex-info "No query results to visualize — run a query first." {})}
            (with-ex
              :nl nl
              [:viz-spec :viz-text] (viz-generate-with-data :nl data)))]
      (record project response-object @*last-llm-call*)
      (update response-object :error #(when % (print-str %))))))
