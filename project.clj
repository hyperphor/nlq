(defproject com.hyperphor/nlq "0.1.0-SNAPSHOT"
  :description "Natural-language query engine for structured databases (SQL, SPARQL, ...) — extracted from ParkerICI/okc. See design/hyperphorization.md there for the extraction history."
  :url "https://github.com/hyperphor/nlq"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :deploy-repositories [["clojars" {:sign-releases false}]]
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [com.hyperphor/multitool "0.3.0"]
                 [com.hyperphor/way "0.2.4"]
                 [com.hyperphor/alzabo "1.3.4" :exclusions [hiccup]]
                 [com.hyperphor/ellum "0.1.3"]
                 [org.clojure/data.json "2.5.2"]
                 [environ "1.2.0"]
                 [com.taoensso/timbre "6.7.1"]
                 [hato "1.0.0"]

                 ;; sources/bigquery.clj
                 [com.google.cloud/google-cloud-bigquery "2.54.0"]
                 ;; sources/cirro.clj (sheet-upload path)
                 [com.cognitect.aws/api "0.8.838"]
                 [com.cognitect.aws/endpoints "871.2.51.4"]
                 [com.cognitect.aws/s3 "871.2.51.4"]]
  :source-paths ["src/cljc" "src/clj" "src/cljs"]
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[thheller/shadow-cljs "3.1.8"]]}
             :uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
