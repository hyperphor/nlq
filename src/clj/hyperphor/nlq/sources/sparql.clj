;;; Peer to sources.sql — NLQ's third query-type backend (see generate.clj's
;;; :sparql methods) — but deliberately not built on sources.sql's provider
;;; multimethods: those exist to share DDL/dialect machinery across SQL
;;; backends, and SPARQL/RDF has no DDL or table structure to share with them.
(ns hyperphor.nlq.sources.sparql
  "Execute SPARQL queries against a public SPARQL endpoint (Wikidata Query
   Service by default)."
  (:require [hato.client :as client]
            [clojure.data.json :as json]
            [hyperphor.multitool.core :as u]))

(def default-endpoint
  "https://query.wikidata.org/sparql")

;;; Identifies this client to the endpoint. Public endpoints (eg WDQS)
;;; throttle/block requests with no descriptive User-Agent — see
;;; https://www.mediawiki.org/wiki/Wikidata_Query_Service/User_Manual#Query_limits
;;; Override per-project via `db`'s :user-agent (a consuming app should
;;; identify itself, not this library).
(def default-user-agent
  "hyperphor-nlq/0.1 (https://github.com/hyperphor/nlq)")

;;; → multitool or sources.cirro, same shape as cirro's coerce-json
(defn- coerce-json
  [body]
  (if (string? body)
    (json/read-str body :key-fn keyword)
    body))

(defn- check-response
  [{:keys [status body] :as _resp} url]
  (let [body (coerce-json body)]
    (if (< status 400)
      body
      (throw (ex-info (str "SPARQL endpoint error: " status)
                      {:url url :status status :body body})))))

(defn- binding->value
  "A SPARQL JSON results binding is {var {:type ... :value ... ...}} — flatten
   to just the value string, same as a SQL/Datomic result row's plain values."
  [binding]
  (u/map-values :value binding))

(defn query
  "Run `sparql-string` against `db`'s SPARQL endpoint (default Wikidata's).
   Returns a seq of maps keyed by the query's own SELECT variables, same shape as sql/query."
  [db sparql-string]
  (let [url (or (:endpoint db) default-endpoint)
        resp (client/get url
                         {:query-params {:query sparql-string :format "json"}
                          :headers {"Accept" "application/sparql-results+json"
                                    "User-Agent" (or (:user-agent db) default-user-agent)}
                          :as :json
                          :throw-exceptions false})
        body (check-response resp url)]
    (map binding->value (get-in body [:results :bindings]))))
