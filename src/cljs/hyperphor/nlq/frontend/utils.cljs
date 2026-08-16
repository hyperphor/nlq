(ns hyperphor.nlq.frontend.utils
  (:require [hyperphor.way.ui.config :as config]))

;;; Stuff to eventually go to way

;;; ag-grid, by default, treats a mouse-drag inside a cell as row/range
;;; selection rather than normal text selection, so there's nothing to copy
;;; on Ctrl+C. `enableCellTextSelection` restores normal browser text
;;; selection within a cell; `ensureDomOrder` is required alongside it (per
;;; ag-grid docs) for that selection to behave correctly with virtualized
;;; rows. Merge into every `ag/ag-table` call's :ag-grid-options.
(def copyable-grid-options
  {:enableCellTextSelection true
   :ensureDomOrder true})

;;; → Way, more options
;;; TODO Icon option
(defn link
  [label url]
  [:a {:href url :target "_blank"} label])

(defn plink
  [label url]
  [:p (link label url)])
