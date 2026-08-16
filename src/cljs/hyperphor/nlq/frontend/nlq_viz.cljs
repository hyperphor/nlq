(ns hyperphor.nlq.frontend.nlq-viz
  (:require [re-frame.core :as rf]
            [hyperphor.way.vega :as v]
            [hyperphor.way.aggrid :as ag]
            [hyperphor.way.web-utils :as wu]
            [hyperphor.way.api :as api]
            [hyperphor.way.markdown :as md]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [hyperphor.multitool.core :as u]
            )
  )


;;; TEMP, patient graph

(defn bar-spec
  [data yfield cfield facet-field]
  (let [y-quant? false #_ (= :numeric (dd/field-type yfield))
        c-quant? false #_ (= :numeric (dd/field-type cfield))
        c-domain {} #_ (dd/patient-field-domain cfield)]
    {:data {:values data}
     :mark {:type "bar", :tooltip {:content "data"} },
     :encoding
     {:y (merge {:field yfield :title yfield}
                (if y-quant?
                  {:type :quantitative :bin {:maxbins 15}}
                  {:type :nominal})),
      :x {:aggregate :count :type :quantitative}
      :color (when cfield {:field cfield
                           :type (if c-quant? :quantitative :nominal)
                           #_ :scale #_ {:domain (if c-quant?
                                             [(:min c-domain), (:max c-domain)]
                                             (into [] (sort c-domain)))}
                           :title cfield})
      :facet (when facet-field {:field facet-field :title facet-field :type :nominal})
      }}
    ))

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
                                          (str/replace (last (str/split (name (:db|ident v)) #"\|")) "-" " ")
                                          
                                          (vector? v)
                                          (mapv (fn [item]
                                                  (if (and (map? item) (:db|ident item))
                                                    (last (str/split (name (:db|ident item)) #"\|"))
                                                    item)) v)
                                          
                                          :else v)]
                        (assoc acc natural-key natural-val)))
                    {} row))
       data))

(defn ui
  [subjects & [id]]
  (let [id (or id :vizq)
        {:keys [viz-spec error]} @(rf/subscribe [:qbox-response id])]
    (cond
      viz-spec [v/vega-lite-view
                (assoc viz-spec :data {:values (naturalize-data subjects)}) []]
      error [:div.alert.alert-warning error]
      :else nil)))

