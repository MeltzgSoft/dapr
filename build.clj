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

  `stage-electron` prepares the desktop shell's payload: the jar built here plus
  a trimmed JRE, put where electron-builder expects them (see
  electron/README.md). It lives here rather than in a script under electron/ so
  the jar's name and the release version come from the build that produced them
  rather than being re-derived by guesswork.

  Usage:  clojure -T:build uber
          clojure -T:build uber :version 1.2.3
          clojure -T:build stage-electron

  The version defaults to the DAPR_VERSION env var (the release workflow sets it
  from the git tag), then the current git tag, then a SNAPSHOT placeholder."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
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
   #"^META-INF/resources/webjars/htmx-ext-sse/.*/dist/sse\.min\.js$"
   #"^META-INF/resources/webjars/idiomorph/.*/dist/idiomorph-ext\.min\.js$"])

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

;; --- desktop shell payload ---------------------------------------------------

(def ^:private electron-dir "electron")
(def ^:private electron-resources "electron/resources")

(def ^:private jlink-modules
  "Modules linked into the runtime shipped inside the installers.

  `java.se` rather than a jdeps-derived minimum on purpose: Clojure resolves
  classes at runtime and the device backends reach the OS through JNA, so
  static analysis of the jar under-reports what actually loads — and the failure
  mode is a NoClassDefFoundError on a user's machine months later, on whichever
  path was not exercised at build time. The extra modules cost tens of megabytes;
  getting it wrong costs a broken release.

  The additions are the ones repeatedly needed in practice: jdk.unsupported for
  sun.misc.Unsafe (Clojure and JNA both reach for it), jdk.crypto.ec for TLS
  curves, jdk.zipfs for the jar/zip filesystem provider, and jdk.localedata so
  formatting is not limited to the root locale."
  ["java.se" "jdk.unsupported" "jdk.crypto.ec" "jdk.zipfs" "jdk.localedata"])

(defn- windows? []
  (str/includes? (str/lower-case (System/getProperty "os.name" "")) "win"))

(defn- jdk-tool
  "Path to a JDK tool of the JVM running this build. jlink ships with the JDK,
  and this build already runs on one, so there is no reason to hunt the PATH for
  a possibly different version."
  [tool]
  (str (io/file (System/getProperty "java.home") "bin"
                (if (windows?) (str tool ".exe") tool))))

(defn- run!*
  "Run a command, throwing with its output when it fails."
  [{:keys [command-args] :as opts}]
  (let [{:keys [exit out err]} (b/process (merge {:out :capture :err :capture} opts))]
    (when-not (zero? exit)
      (throw (ex-info (str "command failed: " (str/join " " command-args))
                      {:exit exit :out out :err err})))
    (str out err)))

(defn- link-runtime!
  "Build the trimmed JRE at electron/resources/runtime, and prove it starts.

  The check is the same idea as scripts-in-jar!: a runtime missing a module
  should fail the build that made it, not the user who installed it."
  []
  (let [dir (io/file electron-resources "runtime")]
    (b/delete {:path (str dir)})
    (println "Linking runtime with" (str/join ", " jlink-modules))
    (run!* {:command-args [(jdk-tool "jlink")
                           "--add-modules" (str/join "," jlink-modules)
                           "--output" (str dir)
                           ;; A runtime needs none of these: debug symbols, man
                           ;; pages, or the headers only a native compiler wants.
                           "--strip-debug" "--no-header-files" "--no-man-pages"
                           "--compress" "zip-6"]})
    (let [java (io/file dir "bin" (if (windows?) "java.exe" "java"))]
      (when-not (.exists java)
        (throw (ex-info "jlink produced no java launcher" {:expected (str java)})))
      ;; `--version` (two dashes) writes to stdout, so the banner is capturable.
      (println "Runtime:" (str/trim (run!* {:command-args [(str java) "--version"]}))))))

(defn- set-electron-version!
  "Record `ver` as the shell's package version, via npm so the rest of
  package.json is left alone.

  Deliberately not electron-builder's -c.extraMetadata.version: that rewrites
  package.json in place and drops everything it does not recognise — including
  scripts and devDependencies — leaving a checkout that later steps cannot build.

  Only called for an explicitly given version (see stage-electron): writing a
  file that is in git is a side effect a local packaging run should not have, and
  stamping the SNAPSHOT placeholder into it would leave the tree dirty every
  time."
  [ver]
  (run!* {:command-args [(if (windows?) "npm.cmd" "npm") "pkg" "set" (str "version=" ver)]
          :dir electron-dir})
  (println "Shell version:" ver))

(defn stage-electron
  "Stage the desktop shell's payload into electron/resources: the uberjar and a
  trimmed JRE, which electron-builder copies verbatim into the packaged app (see
  electron/README.md).

  Uses the jar this build names rather than whatever is newest in target/, so a
  stale artifact cannot be packaged silently.

  The shell's package.json version is only written when a version was actually
  asked for — `:version` or DAPR_VERSION, which is what the release workflow
  sets. A local run stages against whatever jar is there and leaves the tracked
  file alone."
  [opts]
  (let [ver       (version opts)
        explicit? (boolean (or (:version opts) (System/getenv "DAPR_VERSION")))
        uber-file (or (:uber-file opts) (format "target/dapr-%s.jar" ver))]
    (when-not (.exists (io/file uber-file))
      (throw (ex-info (str "no uberjar at " uber-file " — run `clojure -T:build uber` first")
                      {:uber-file uber-file :version ver})))
    (b/copy-file {:src uber-file :target (str (io/file electron-resources "dapr.jar"))})
    (println "Staged" uber-file "-> electron/resources/dapr.jar")
    (link-runtime!)
    (if explicit?
      (set-electron-version! ver)
      (println "Shell version: left as-is (no :version or DAPR_VERSION given)"))
    (assoc opts :version ver :uber-file uber-file)))

(defn- electron-builder-bin
  "The electron-builder installed under electron/node_modules.

  The locally installed binary rather than `npx`: the version is the one pinned
  in package-lock.json, and nothing reaches the network mid-build to find out
  which that is."
  []
  (let [bin (io/file electron-dir "node_modules" ".bin"
                     (if (windows?) "electron-builder.cmd" "electron-builder"))]
    (when-not (.exists bin)
      (throw (ex-info "electron-builder is not installed — run `npm ci` in electron/ first"
                      {:expected (str bin)})))
    (str (.getAbsolutePath bin))))

(defn package-electron
  "Build the desktop installers for the current OS: stage the payload, then run
  electron-builder over it. Outputs land in electron/dist.

  One task rather than a stage-then-npm dance, so there is a single entry point
  to packaging and a single place that knows the order. electron-builder can only
  produce installers for the platform it runs on — the staged JRE is native code
  — so a full set means running this once per OS (see the release workflow).

  Requires `npm ci` in electron/ to have been run; that stays a separate step so
  CI can cache it."
  [opts]
  (let [opts (stage-electron opts)]
    (println "Running electron-builder")
    (print (run!* {:command-args [(electron-builder-bin) "--publish" "never"]
                   :dir electron-dir}))
    (flush)
    opts))
