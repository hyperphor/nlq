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

(rf/reg-event-db
 :qbox-query-response
 (fn [db [_ id response]]
   ;; TODO!
   #_
   (when (:error response)
     (rf/dispatch [:open-card :nlq :error]))
   (-> db
       (assoc-in [:qbox id :spin?] false)
       (assoc-in [:qbox id :response] response)
       ;; Put datalog query into form so it can be edited
       ;; TODO
       #_                               
       (assoc-in [:form :qgen :datalog]
                 (with-out-str (pprint/pprint (:query response)))))))

(rf/reg-sub
 :qbox-response
 (fn [db [_ id]]
   (get-in db [:qbox id :response])))

(rf/reg-sub
 :qbox-results
 (fn [db [_ id]]
   (get-in db [:qbox id :response :results])))
