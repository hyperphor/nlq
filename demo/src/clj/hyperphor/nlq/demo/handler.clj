(ns hyperphor.nlq.demo.handler
  (:require [compojure.core :refer [defroutes context GET]]
            [hyperphor.way.handler :as wh]
            [hyperphor.nlq.generate :as nlq]
            [hyperphor.nlq.visgen :as visgen]
            [hyperphor.nlq.config :as nlqc]
            hyperphor.nlq.inspect))

;;; hyperphor.nlq.frontend.qbox's :qbox-query event -- what universal-query/ui's
;;; project picker + sql-query/ui actually call -- always hits /api/qbox/query with
;;; :id/:project/:query params. Unlike nlq-aact's single-project version, `project`
;;; here is a real dispatch key: this app is generic over whatever :nlq config
;;; entries resources/config.edn declares, so the only fallback is "the first
;;; configured project," never a hardcoded name.
(defn qbox-endpoint
  [id query project]
  (let [project (or project (first (nlqc/projects)))]
    (case (keyword id)
      :sql              (nlq/endpoint project :sql query)
      :sparql           (nlq/endpoint project :sparql query)
      (:vizq :sql-vizq) (visgen/viz-endpoint project query)
      {:error (str "Unsupported query type: " id)})))

(defroutes site-routes)

(defroutes api-routes
  (context "/api" []
    (context "/qbox" []
      (GET "/query" [id query project] (wh/content-response (qbox-endpoint id query project))))))

;;; Warning: do not `(def app ...)` -- config isn't necessarily loaded yet at
;;; compile time (same caveat as okc/nlq-aact's handler.clj).
(defn app
  []
  (wh/app site-routes api-routes))
