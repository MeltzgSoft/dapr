(ns build
  "Release build: AOT-compiled uberjar for `dapr.main`.

  JavaFX ships a per-OS native classifier, so a jar built on one platform will
  not run on another. deps.edn pins the `:linux` classifier for local dev; here
  we rewrite it to the target platform's classifier (auto-detected, or passed as
  `:javafx-classifier`) so each release matrix leg produces a jar carrying its
  own OS's JavaFX natives.

  Usage:  clojure -T:build uber
          clojure -T:build uber :javafx-classifier win :version 1.2.3

  The version defaults to the DAPR_VERSION env var (the release workflow sets it
  from the git tag), then the current git tag, then a SNAPSHOT placeholder."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def ^:private main 'dapr.main)
(def ^:private class-dir "target/classes")
(def ^:private javafx-lib 'org.openjfx/javafx-controls$linux)

(defn- default-classifier
  "JavaFX classifier for the host OS/arch (matching the openjfx Maven classifiers)."
  []
  (let [os   (str/lower-case (System/getProperty "os.name"))
        arch (str/lower-case (System/getProperty "os.arch"))
        arm? (or (str/includes? arch "aarch64") (str/includes? arch "arm64"))]
    (cond
      (str/includes? os "win") "win"
      (str/includes? os "mac") (if arm? "mac-aarch64" "mac")
      :else                    (if arm? "linux-aarch64" "linux"))))

(defn- version
  "Release version: strip the leading `v` from a `v#.#.#` git tag."
  [opts]
  (let [raw (or (:version opts)
                (System/getenv "DAPR_VERSION")
                (not-empty (str/trim (or (b/git-process {:git-args "describe --tags --exact-match"}) "")))
                "0.0.0-SNAPSHOT")]
    (str/replace-first (str raw) #"^v" "")))

(defn- project-deps
  "The deps.edn map with the JavaFX classifier swapped to `classifier`."
  [classifier]
  (let [raw     (edn/read-string (slurp "deps.edn"))
        jfx-ver (get-in raw [:deps javafx-lib :mvn/version])
        target  (symbol (str "org.openjfx/javafx-controls$" classifier))]
    (-> raw
        (update :deps dissoc javafx-lib)
        (assoc-in [:deps target] {:mvn/version jfx-ver}))))

(defn uber
  "Build an AOT uberjar at target/dapr-<version>-<classifier>.jar."
  [opts]
  (let [classifier (or (some-> (:javafx-classifier opts) str) (default-classifier))
        ver        (version opts)
        basis      (b/create-basis {:project (project-deps classifier)})
        uber-file  (format "target/dapr-%s-%s.jar" ver classifier)]
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
    (println "Built" uber-file)
    (assoc opts :uber-file uber-file)))
