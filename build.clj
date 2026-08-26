(ns build
  "Release build: AOT-compiled uberjar for `dapr.main`.

  One jar runs on every OS. That is new: the JavaFX UI needed a per-platform
  natives classifier, so releases used to ship a jar per OS. The web UI has no
  such dependency — the MTP and keystore backends bind to whatever the host
  provides at runtime — so there is a single artifact again.

  htmx and its SSE extension are *not* checked into this repository. They
  resolve as org.webjars.npm dependencies and are copied into the jar here like
  any other classpath entry; dapr.web.assets finds them at runtime and serves
  them.

  Usage:  clojure -T:build uber
          clojure -T:build uber :version 1.2.3

  The version defaults to the DAPR_VERSION env var (the release workflow sets it
  from the git tag), then the current git tag, then a SNAPSHOT placeholder."
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def ^:private main 'dapr.main)
(def ^:private class-dir "target/classes")

(defn- version
  "Release version: strip the leading `v` from a `v#.#.#` git tag."
  [opts]
  (let [raw (or (:version opts)
                (System/getenv "DAPR_VERSION")
                (not-empty (str/trim (or (b/git-process {:git-args "describe --tags --exact-match"}) "")))
                "0.0.0-SNAPSHOT")]
    (str/replace-first (str raw) #"^v" "")))

(def ^:private required-scripts
  "The WebJar scripts the UI cannot run without (see dapr.web.assets)."
  [#"^META-INF/resources/webjars/htmx\.org/.*/dist/htmx\.min\.js$"
   #"^META-INF/resources/webjars/htmx-ext-sse/.*/dist/sse\.min\.js$"])

(defn- scripts-in-jar!
  "Fail the build if a script did not make it into the uberjar. The UI is unusable
  without htmx, and degrades silently to polling without the SSE extension — a
  missing WebJar would otherwise only show up as a dead or sluggish page."
  [uber-file]
  (with-open [zip (java.util.zip.ZipFile. (java.io.File. ^String uber-file))]
    (let [names (mapv #(.getName ^java.util.zip.ZipEntry %) (enumeration-seq (.entries zip)))]
      (doseq [pattern required-scripts]
        (when-not (some #(re-find pattern %) names)
          (throw (ex-info "a required WebJar script is missing from the uberjar — check the org.webjars.npm dependencies in deps.edn"
                          {:uber-file uber-file :pattern (str pattern)})))))))

(defn uber
  "Build an AOT uberjar at target/dapr-<version>.jar."
  [opts]
  (let [ver       (version opts)
        basis     (b/create-basis {:project "deps.edn"})
        uber-file (format "target/dapr-%s.jar" ver)]
    (b/delete {:path "target"})
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis :ns-compile [main] :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis     basis
             :main      main
             ;; JDK honours this so `java -jar` runs without an explicit
             ;; --enable-native-access flag; still documented for older JDKs.
             :manifest  {"Enable-Native-Access" "ALL-UNNAMED"}})
    (scripts-in-jar! uber-file)
    (println "Built" uber-file)
    (assoc opts :uber-file uber-file)))
