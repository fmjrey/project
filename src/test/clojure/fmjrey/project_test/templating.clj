(ns fmjrey.project-test.templating
  "Functions related to generating test projects on disk from a template using
  [deps-new](https://github.com/seancorfield/deps-new).

  Test projects are all made from the same template and are created in the
  same directory, which can be deleted to start clean. Together they form
  a dependency tree with multiple application roots. All the tree/graph logic
  is in a separate graph namespace that also contains the logic to generate
  SVG images of the tree.

  Generating all test projects can take some time, so best is to do it once."
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
  ([]
   (distinct (for [app (projects :apps)
                   prj (prjs-depth-first app)]
               prj)))
  ([root-prj]
   (distinct (graph/prjs-depth-first projects root-prj))))
(def prjs-depth-first
  "Return the list of all project names, starting from application roots or
  the given project name, and ending with library leafs, so that a project
  appears before its dependencies (depth-first traversal).
  This function is memoized."
  (memoize prjs-depth-first-))

(declare prjs-leaf-first)
(defn prjs-leaf-first-
  ([]
   (distinct (for [app (projects :apps)
                   prj (prjs-leaf-first app)]
               prj)))
  ([root-prj]
   (distinct (graph/prjs-leaf-first projects root-prj))))
(def prjs-leaf-first
  "Return the list of all project names in the tree rooted in all applications
  or the given project, starting from library leafs and ending with tree roots,
  so that dependencies always appear before the projects depending on them
  (post-recursive depth-first traversal).
  This function is memoized."
  (memoize prjs-leaf-first-))


;;==============================================================================
;; Functions to prepare the data needed for generating test projects.

(def test-version "Version for all generated projects" "0.1.0-SNAPSHOT")

(defn project-info
  "Augment a project data map with additional entries used for generation."
  [{project-name :name :as libm}]
  (assoc libm
         :id (symbol "test"  project-name)
         :ns (str "test." project-name)
         :dir (str (io/file projects-dir project-name))
         :jar (format "target/%s-%s.jar" project-name test-version)
         :license {:id "EPL-2.0"
                   :name "Eclipse Public License 2.0"
                   :url "https://www.eclipse.org/legal/epl-2.0"}))

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
;; Functions to actually generate test projects.

(defn invoke-build
  "Invoke a build tool on a test project in an external process."
  [{:keys [dir]} f]
  (let [r (ext/invoke {:tool-alias :build
                       :dir dir
                       :fn f
                       ;;:debug true
                       :args {:clojure.exec/err :capture}})
        err (:err r)]
    (when err println)))

(defn gen-from-template
  "Generate a single test project with deps-new."
  [{:keys [id dir]}]
  (new/create {:template 'test/project-template
               :name id
               :target-dir dir
               :overwrite true}))

(defn build
  "Generate a new test project on disk and invoke its build steps such as
  creating a jar or uberjar, copying deps.edn to a resource directory, etc."
  [{:keys [builder] :as project-info}]
  (gen-from-template project-info)
  (doseq [step builder]
    (case step
      :local    nil
      :copydeps (invoke-build project-info 'copy-deps)
      :jar      (invoke-build project-info 'jar)
      :uberjar  (invoke-build project-info 'uberjar))))

(defn gen
  "Main entry point to generate all test projects on disk along with tree
  graph SVG images."
  []
  (let [{:keys [save-edn?]} projects
        f (io/file projects-dir "projects.edn")
        _ (io/make-parents f)]
    (when save-edn?
      (with-open [wr (io/writer f)]
        (.write wr (with-out-str (pp/pprint projects)))))
    (graph/gen-imgs projects)
    (doseq [prj prjs-leaf-first
            :let [project-info (->> (get-in projects [:by-name prj])
                                    project-info)]]
      (build project-info))))

;;==============================================================================
;; Functions needed by deps-new to generate from template.

(defn data-fn
  [{:keys [top main]}]
  {:pre (= top "test")}
  (let [{:keys [name jar] {license-id :license/id} :license :as project-info}
        (project-info (get-in projects [:by-name main]))]
    (assert name (format "Project %s not found" main))
    {:project/name name
     :description (str "Test project " name)
     :project/info (pstr 16 (dissoc project-info :deps :deps-nodes))
     :license/id license-id
     :project/jar jar
     :deps.edn/extra-aliases (pstr-entries 2 (extra-aliases project-info))
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
