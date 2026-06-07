(ns fmjrey.project-test.templating
  "Functions related to generating test projects on disk from a template using
  [deps-new](https://github.com/seancorfield/deps-new).

  Test projects are all made from the same template and are created in the
  same directory, which can be deleted to start clean. Together they form
  a dependency tree with multiple application roots. All the tree/graph logic
  is in a separate graph namespace that also contains the logic to generate
  SVG images of the tree.

  Generating all test projects can take some time, so best is to do it once.
  For now images and projects are generated when the test-projects directory
  does not exist, and aren't if it does. This check happens statically, ie when
  this namespace is loaded."

  (:require [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [org.corfield.new :as new]
            [fmjrey.invoke :as ext]
            [fmjrey.project-test.graph :as graph]))

;;==============================================================================
;; The project dependency tree is generated statically and stored in a var.
;; Parameters such as depth and target directory are also statically defined.

(def depth "The deps of the dependency tree" 2)
(def projects-dir "Where projects are generated" "test-projects")
(def projects "The project dependency tree" (graph/projects depth projects-dir))
(def apps (projects :apps))

(declare prjs-depth-first)
(defn prjs-depth-first-
  ([] (graph/prjs-from-roots projects))
  ([root-prj]
   (distinct (graph/prjs-depth-first projects root-prj))))
(def prjs-depth-first
  "Return the list of all project names, starting from application roots or
  the given project name, and ending with library leaves, so that a project
  appears before its dependencies (depth-first traversal).
  This function is memoized."
  (memoize prjs-depth-first-))

(declare prjs-leaf-first)
(defn prjs-leaf-first-
  ([] (graph/prjs-from-leaves projects))
  ([root-prj]
   (distinct (graph/prjs-leaf-first projects root-prj))))
(def prjs-leaf-first
  "Return the list of all project names in the tree rooted in all applications
  or the given project, starting from library leaves and ending with tree roots,
  so that dependencies always appear before the projects depending on them
  (post-recursive depth-first traversal).
  This function is memoized."
  (memoize prjs-leaf-first-))


;;==============================================================================
;; Functions to prepare the data needed for generating test projects.

(def test-version "Version for all generated projects" "0.1.0-SNAPSHOT")

(defn ->project-name
  [artifact-id]
  (str/replace artifact-id "-" "_"))

(defn ->project-ns-str
  [prj]
  (str "test." (str/replace prj "_" "-")))
(defn ->project-lib
  [prj]
  (symbol "test" (str/replace prj "_" "-")))

(defn ->project-ns-str
  [prj]
  (str "test." (str/replace prj "_" "-")))

(defn ->project-dir
  [prj]
  (str (io/file projects-dir prj)))

(defn project-info
  "Retrieve and augment a project data map with additional entries used for
  generation."
  [prj-or-prjm]
  (let [{prj :name :as libm} (cond
                               (string? prj-or-prjm)
                               (get-in projects [:by-name prj-or-prjm])
                               (map? prj-or-prjm)
                               prj-or-prjm)]
    (assoc libm
           :id  (->project-lib    prj)
           :ns  (->project-ns-str prj)
           :dir (->project-dir    prj)
           :jar (format "target/%s-%s.jar" prj test-version)
           :license {:id "EPL-2.0"
                     :name "Eclipse Public License 2.0"
                     :url "https://www.eclipse.org/legal/epl-2.0"})))

(defn ->dep-edn-decl
  "deps.edn dependency declaration for one test project."
  [{:keys [id name jar builder]}]
  [id {:local/root (if (some #{:jar} builder)
                     (str (io/file ".." name jar))
                     (str (io/file ".." name)))}])

(defn ->deps-edn-decl
  "deps.edn dependency declarations for a list of test projects."
  [{:keys [deps]}]
  (into (sorted-map 'org.clojure/clojure {:mvn/version (clojure-version)}
                    'fmjrey/project      {:local/root "../.."})
        (->> deps
             (mapv #(get-in projects [:by-name %1]))
             (mapv (comp ->dep-edn-decl project-info)))))

(defn indent
  "Generate a string of n spaces or nil if n=0 or nil."
  [n]
  (if (string? n)
    n
    (case n
      (nil 0) nil
      1 " "
      2 "  "
      3 "   "
      4 "    "
      5 "     "
      6 "      "
      7 "       "
      8 "        "
      (apply str (repeat n \space)))))

(defn pstr
  "Pretty-printed string for one value, optionally with indentation."
  ([v] (pstr nil v))
  ([n v]
   (binding [*print-namespace-maps* false
             clojure.pprint/*print-right-margin* 90]
     (let [indent (indent n)
           pps (pp/write v :stream nil)]
       (if indent
         (->> (str/split-lines pps)
              (interpose (str \newline indent))
              (apply str))
         pps)))))

(defn pstr-entries
  "Pretty-printed string of all entries in a collection, with optional
  indentation."
  ([coll] (pstr-entries nil coll))
  ([n coll]
   (if (coll? coll)
     (let [pprint-entry (if (map? coll)
                          (fn [[k v]]
                            (let [ppk (pstr k)]
                              (str ppk \space (pstr (inc (count ppk)) v))))
                          pstr)]
       (->> (map pprint-entry coll)
            (interpose (str \newline (indent n)))
            (apply str)))
     (pstr n coll))))

(defn pstr-coll
  "Pretty-printed string of a collection, one entry per line, with optional
  indentation."
  ([coll] (pstr-coll nil coll))
  ([n coll]
   (if (coll? coll)
     (let [[begin end] (cond
                         (map?    coll) ["{" "}"]
                         (set?    coll) ["#{" "}"]
                         (vector? coll) ["[" "]"]
                         (seq?    coll) ["(" ")"])]
       (str begin (pstr-entries (+ n (count begin)) coll) end))
     (pstr n coll))))

(defn extra-aliases
  "Additional aliases to add in the generated deps.edn of a project."
  [{:keys [type]}]
  ({:app {:project {:deps {'io.github.clojure/tools.build {:mvn/version "0.10.12"}
                           'fmjrey/project {:local/root "../.."}}
                    :exec-args {:fmjrey.project/verbose true}
                    :ns-default 'fmjrey.project}}
    :lib (symbol ";;")
    :shared-lib (symbol ";;")}
   type))

;;==============================================================================
;; Functions to actually generate and build test projects.

(defn gen-from-template
  "Generate a single test project with deps-new."
  [{:keys [id dir]}]
  (with-out-str
    (new/create {:template 'test/project-template
                 :name id
                 :target-dir dir
                 :overwrite true})))

(defn invoke-build
  "Invoke a build function on a test project in an external process."
  [{:keys [dir]} f]
  (let [r (ext/invoke {:tool-alias :build
                       :dir dir
                       :fn f
                       ;;:debug true
                       :args {:clojure.exec/err :capture}})
        err (:err r)]
    (when err println)))

(defn build
  "Generate a new test project on disk and invoke its build steps such as
  creating a jar or uberjar, copying deps.edn to a resource directory, etc."
  [project-info]
  (gen-from-template project-info)
  (invoke-build project-info 'build))

(defn build-prj
  "Generate on disk the project with the given name."
  [prj]
  (->> (get-in projects [:by-name prj])
       project-info
       build))

(defn build-prjs
  "Generate on disk projects with the given list of project names. Projects in prjs must not
  depend on each other so as to enable concurrent building."
  [prjs]
  (doseq [prj prjs] (build-prj prj)))

(defn build-apps
  "Generate on disk application (top level) projects only."
  []
  (build-prjs (projects :apps)))

(defn gen-imgs
  "Generate on disk all SVG images and optional EDN files."
  []
  (let [{:keys [save-edn?]} projects
        f (io/file projects-dir "projects.edn")
        _ (io/make-parents f)]
    (when save-edn?
      (with-open [wr (io/writer f)]
        (.write wr (with-out-str (pp/pprint projects)))))
    (graph/gen-imgs projects)))

(defn gen
  "Main entry point to generate all test projects on disk along with SVG images."
  []
  (gen-imgs)
  (doseq [prjs (-> projects :by-level rseq)]
    (build-prjs prjs)))

;;==============================================================================
;; Generate images and projects if destination directory does not exist.

(when-not (.exists (io/file projects-dir))
  (println "Directory" projects-dir "not found.")
  (gen))

;;==============================================================================
;; Functions needed by deps-new to generate from template.

(defn data-fn
  [{:keys [top artifact/id] :as data}]
  {:pre (= top "test")}
  (let [prj (->project-name id)
        {:keys [name jar deps] {license-id :license/id} :license :as project-info}
        (some-> (get-in projects [:by-name prj]) project-info)]
    (assert name (format "Project named \"%s\" not found" prj))
    {:project/name name
     :description (str "Test project " name)
     :project/info (pstr 16 (dissoc project-info :deps-nodes))
     :license/id license-id
     :project/jar jar
     :project/deps (pstr-coll 11 deps)
     :deps.edn/extra-aliases (pstr-entries 10 (extra-aliases project-info))
     :deps.edn/deps (pstr 7 (->deps-edn-decl project-info))}))

(defn template-fn
  [edn data]
  edn)

;;==============================================================================

(comment
  ;;
  (->> (get-in projects [:by-name "app_0_0_local"])
       project-info
       gen-from-template)

  ;; a previous attempts to generate deps.edn entirely from data.
  (defn deps-edn
    [{:keys [type ns] :as project-info}]
    (cond-> {:paths ["src" "resources"]
             :deps (->deps-edn-decl project-info)
             :tools/usage {:ns-default ns}
             :aliases
             {:project/info (dissoc project-info :deps :deps-nodes)
              :cli {:ns-default ns}
              :build
              {:deps {'io.github.clojure/tools.build {:mvn/version "0.10.12"}
                      'fmjrey/project {:local/root "../.."}}
               :ns-default 'build}}}
      (= type :app)
      (assoc-in [:aliases :project]
                {:deps {'io.github.clojure/tools.build {:mvn/version "0.10.12"}
                        'fmjrey/project {:local/root "../.."}}
                 :exec-args {:fmjrey.project/verbose true}
                 :ns-default 'fmjrey.project})))

  (->> (get-in projects [:by-name "app_0_0_local"])
       project-info
       deps-edn
       pstr)
  ;;
  )
