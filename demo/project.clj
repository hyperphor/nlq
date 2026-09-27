(defproject hyperphor/nlq-demo "0.1.0"
  :description "Generic, config-driven demo app for hyperphor/nlq -- point it at any
                :nlq config (see resources/config.edn) and launch to investigate a
                new database, no code changes needed. See ../design/demo-app.md."
  ;; Not published -- lives inside the nlq repo as an example/dev tool, not a
  ;; standalone library. No :deploy-repositories.
  ;; checkouts/nlq (a symlink to the repo root, ../.. from here) puts this repo's
  ;; own live src/clj+cljs ahead of the pinned jar version below on the classpath --
  ;; edit nlq's source, re-run, no version bump/republish needed. See
  ;; ../design/demo-app.md for why this lives here instead of a separate repo.
  :plugins [[lein-shadow "0.4.1"]]
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [com.hyperphor/way "0.2.7"]
                 [com.hyperphor/nlq "0.3.28"]
                 [com.taoensso/timbre "6.7.1"]
                 [environ "1.2.0"]
                 ;; checkouts/nlq overrides nlq's *source* with this repo's own
                 ;; live tree, but Leiningen checkouts never pull in a checked-out
                 ;; project's own :dependencies -- those still resolve from the
                 ;; pinned com.hyperphor/nlq "0.3.28" jar's published pom. sqlite
                 ;; support (hyperphor.nlq.sources.sqlite) was added after 0.3.28
                 ;; shipped, so its driver has to be declared here directly until
                 ;; nlq's next Clojars release includes it in its own pom.
                 [org.xerial/sqlite-jdbc "3.46.1.3"]
                 ;; Direct dep, NOT :dev-profile-only -- `lein uberjar` activates
                 ;; :uberjar, not :dev, so a :dev-scoped shadow-cljs would be
                 ;; invisible to :uberjar's own ["shadow" "release" "app"] prep-task
                 ;; below. :exclusions ring/ring-core: shadow-cljs pins its own
                 ;; older ring-core, which can win Leiningen's nearest-wins
                 ;; tie-break over way's newer one once way is only a transitive
                 ;; dependency -- same reasoning nlq-aact's project.clj documents.
                 [thheller/shadow-cljs "3.1.8" :exclusions [ring/ring-core]]]
  :main ^:skip-aot hyperphor.nlq.demo.core
  :source-paths ["src/clj" "src/cljs"]
  :resource-paths ["resources"]
  :target-path "target/%s"
  :clean-targets ^{:protect false} [".shadow-cljs" "resources/public/cljs-out" "target" "shadow-cljs.edn"]
  :uberjar-name "nlq-demo-standalone.jar"
  :profiles {:uberjar {:aot :all
                       :omit-source true
                       :prep-tasks [["shadow" "release" "app"] "javac" "compile"]
                       :resource-paths ["resources"]
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}}
  :shadow-cljs {:lein true
                :builds
                {:app {:target :browser
                       :compiler-options {:infer-externs true}
                       :output-dir "resources/public/cljs-out"
                       :asset-path "/cljs-out"
                       :modules {:dev-main {:entries [hyperphor.nlq.demo.frontend.core]}}}}})
