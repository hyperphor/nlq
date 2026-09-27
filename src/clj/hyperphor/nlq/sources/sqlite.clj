(ns hyperphor.nlq.sources.sqlite
  "Generic SQLite backend for hyperphor.nlq.sources.sql. A `db` config map is
   {:provider :sqlite :dbname \"/path/to/file.db\"} -- no host/user/password,
   since SQLite is just a local file."
  (:require [clojure.java.jdbc :as jdbc]
            [hyperphor.nlq.sources.sql :as sql]))

;;; clojure.java.jdbc has natively understood {:dbtype "sqlite" :dbname ...}
;;; since 0.7 (see its own classnames table), same as postgres.clj's spec --
;;; just needs the org.xerial/sqlite-jdbc driver on the classpath.
(defn- jdbc-spec
  [db]
  {:dbtype "sqlite" :dbname (:dbname db)})

(defmethod sql/query :sqlite
  [db sql-string]
  (jdbc/query (jdbc-spec db) [sql-string]))

;;; sqlite_master lists the file's own tables/views; sqlite_% names are
;;; SQLite's internal bookkeeping tables (eg sqlite_sequence), not real data.
(defn- table-names
  [spec]
  (->> (jdbc/query spec
                   ["select name from sqlite_master
                     where type = 'table' and name not like 'sqlite\\_%' escape '\\'
                     order by name"])
       (map :name)))

;;; PRAGMA table_info has no placeholder support, but table-name always comes
;;; from sqlite_master itself here, never from user/LLM input.
(defn- columns
  [spec table-name]
  (->> (jdbc/query spec [(str "pragma table_info(\"" table-name "\")")])
       (map (fn [{:keys [name type]}]
              ;; A column declared with no type (legal in SQLite) gets no
              ;; affinity string back from the pragma -- fall back to TEXT
              ;; rather than emitting a blank type into generated DDL.
              {:name name :type (if (seq type) type "TEXT")}))))

(defmethod sql/project-tables :sqlite
  [db]
  (let [spec (jdbc-spec db)]
    (map (fn [table-name] {:table-name table-name :columns (columns spec table-name)})
         (table-names spec))))
