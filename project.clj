(defproject com.hyperphor/nlq "0.3.0"
  :description "Natural-language query engine for structured databases (SQL, SPARQL, ...) —  See design/hyperphorization.md"
  :url "https://github.com/hyperphor/nlq"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :deploy-repositories [["clojars" {:sign-releases false}]]
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [com.hyperphor/multitool "0.3.0"]
                 [com.hyperphor/way "0.2.5"]
                 [com.hyperphor/alzabo "1.3.4" :exclusions [hiccup]]
                 [com.hyperphor/ellum "0.1.3"]
                 [org.clojure/data.json "2.5.2"]
                 [org.clojure/data.csv "1.1.0"] ;; infer.clj: reading tabular files for schema inference
                 [environ "1.2.0"]
                 [com.taoensso/timbre "6.7.1"]
                 [hato "1.0.0"]

                 ;; sources/bigquery.clj
                 [com.google.cloud/google-cloud-bigquery "2.54.0"]
                 ;; sources/cirro.clj (sheet-upload path)
                 [com.cognitect.aws/api "0.8.838"]
                 [com.cognitect.aws/endpoints "871.2.51.4"]
                 [com.cognitect.aws/s3 "871.2.51.4"]

                 ;; sources/postgres.clj. Plain java.jdbc + the driver, not
                 ;; clj-postgresql -- the latter's only pull here would have
                 ;; been its `spec` connection-map helper, which java.jdbc's
                 ;; own native :dbtype/:host/:port/:dbname/:user/:password
                 ;; map format already covers with no extra dependency.
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.postgresql/postgresql "42.7.13"]]
  :source-paths ["src/cljc" "src/clj" "src/cljs"]
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[thheller/shadow-cljs "3.1.8"]]}
             :uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
