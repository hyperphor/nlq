;;; Adapted from pici/pimento's pimento.logging.dynamo (same AWS client/
;;; credential/tag-attribute pattern) -- generalized to a config-supplied
;;; table/region instead of pimento's single hardcoded "pimento_log" table,
;;; since this is a library used by more than one app. See
;;; hyperphor.nlq.generate's :dynamo branch of `record`/`recent`/
;;; `all-log-rows`, the :bigquery-equivalent counterpart to this ns.
(ns hyperphor.nlq.logging.dynamo
  "Generic DynamoDB log sink: write-item + scan-and-sort read. Not a query
   source (no NL->Dynamo translation) -- just a logging backend."
  (:require [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [hyperphor.multitool.core :as u]
            [hyperphor.multitool.cljcore :as ju]))

;;; Credentials come from the environment (AWS_ACCESS_KEY_ID/
;;; AWS_SECRET_ACCESS_KEY/AWS_SESSION_TOKEN) -- same #env-sourced pattern as
;;; every other credential in this codebase, never hardcoded. Scope the
;;; IAM user/role to PutItem+Scan on just the log table.
(u/defn-memoized client
  [region]
  (aws/client {:api :dynamodb
               :region region
               :credentials-provider (credentials/environment-credentials-provider)}))

(defn- invoke-with-error
  [region op]
  (let [result (aws/invoke (client region) op)]
    (when (or (:Error result) (:cognitect.anomalies/category result))
      (throw (ex-info "DynamoDB error" {:op op :result result})))
    result))

;;; Minimal attribute-value tagging -- only the shapes an NLQ log row
;;; actually needs (strings, booleans, sequences); everything else gets
;;; stringified. See pimento.logging.dynamo for the fuller AttributeValue
;;; shape this is a subset of.
(defn- tag-attribute
  [v]
  (cond (boolean? v) {:BOOL v}
        (sequential? v) {:L (map tag-attribute v)}
        (nil? v) {:NULL true}
        :else {:S (str v)}))

(defn- tag-attributes [m] (u/map-values tag-attribute m))
(defn- untag-attributes [m] (u/map-values (comp u/coerce-numeric first vals) m))

(defn write-item
  "Log `item` (a flat map) to DynamoDB `table` in `region`. Adds :uuid (the
   table's expected partition key -- create the table with a String
   partition key named \"uuid\") and :datetime if not already present."
  [table region item]
  (let [item (cond-> item
               (not (:uuid item)) (assoc :uuid (str (java.util.UUID/randomUUID)))
               (not (:datetime item)) (assoc :datetime (ju/date-format (ju/now) "yyyy-MM-dd HH:mm:ss")))]
    (invoke-with-error region
                        {:op :PutItem
                         :request {:TableName table :Item (tag-attributes item)}})))

;;; DynamoDB Scan has no ORDER BY/LIMIT -- reads the whole table, sorted
;;; client-side by the caller. Fine at NLQ-log scale (same approach
;;; pimento's own dynamo logging uses); would need a real index (or
;;; switching to Query on a :datetime sort key) if a log ever got large.
(defn- scan-all
  [table region & [start-key]]
  (let [resp (invoke-with-error region
                                 {:op :Scan
                                  :request (cond-> {:TableName table}
                                             start-key (assoc :ExclusiveStartKey start-key))})]
    (concat (map untag-attributes (:Items resp))
            (when-let [k (:LastEvaluatedKey resp)]
              (scan-all table region k)))))

(defn all-items
  "Every logged item in `table`, newest first."
  [table region]
  (->> (scan-all table region) (sort-by :datetime) reverse))

(defn recent-items
  "The `n` most recent logged items in `table`, newest first."
  [table region n]
  (take n (all-items table region)))
