(ns fmjrey.project-test.templating
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.tools.deps.edn :as deps]
            [org.corfield.new :as new]
            [clojure.pprint :as pprint]
            [fmjrey.invoke :as ext]
            [fmjrey.project-test.graph :as graph]))

(def depth 2)
(def projects-dir "test-projects")
(def projects (graph/projects depth projects-dir))
(def prjs-depth-first (distinct (for [app (projects :apps)
                                     prj (graph/prjs-depth-first projects app)]
                                  prj)))
(def prjs-leaf-first (distinct (for [app (projects :apps)
                                     prj (graph/prjs-leaf-first projects app)]
                                 prj)))
(def test-version "0.1.0-SNAPSHOT")

(defn project-info
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
  [{:keys [id name jar builder]}]
  [id {:local/root (if (some #{:jar} builder)
                     (str (io/file ".." name jar))
                     (str (io/file ".." name)))}])

(defn ->deps-edn-decl
  [{:keys [deps]}]
  (into (sorted-map 'org.clojure/clojure {:mvn/version (clojure-version)}
                    'fmjrey/project      {:local/root "../.."})
        (->> deps
             (mapv #(get-in projects [:by-name %1]))
             (mapv (comp ->dep-edn-decl project-info)))))

(defn indent
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

(defn pprint
  ([v] (pprint nil v))
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

(defn pprint-entries
  ([coll] (pprint-entries nil coll))
  ([n coll]
   (if (coll? coll)
     (let [pprint-entry (if (map? coll)
                           (fn [[k v]]
                             (let [ppk (pprint k)]
                               (str ppk \space (pprint (inc (count ppk)) v))))
                           pprint)]
        (->> (map pprint-entry coll)
             (interpose (str \newline (indent n)))
             (apply str)))
     (pprint n coll))))

(defn extra-aliases
  [{:keys [type]}]
  ({:app {:project {:deps {'io.github.clojure/tools.build {:mvn/version "0.10.12"}
                           'fmjrey/project {:local/root "../.."}}
                    :exec-args {:fmjrey.project/verbose true}
                    :ns-default 'fmjrey.project}}
    :lib (symbol ";;")
    :shared-lib (symbol ";;")}
   type))

(defn invoke-build
  [{:keys [dir]} f]
  (let [r (ext/invoke {:tool-alias :build
                       :dir dir
                       :fn f
                       :debug true
                       :args {:clojure.exec/err :capture}})]
    (-> r :err println)))

(defn gen-from-template
  [{:keys [id dir]}]
  (new/create {:template 'test/project-template
               :name id
               :target-dir dir
               :overwrite true}))

(defn build
  [{:keys [builder] :as project-info}]
  (gen-from-template project-info)
  (doseq [step builder]
    (case step
      :local    nil
      :copydeps (invoke-build project-info 'copy-deps)
      :jar      (invoke-build project-info 'jar)
      :uberjar  (invoke-build project-info 'uberjar))))

(defn gen
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

;; Run conditions
(def execution-time [:runtime :devtime])
(def where [:app :lib])
(def query [:app :lib])

(defn data-fn
  [{:keys [top main]}]
  {:pre (= top "test")}
  (let [{:keys [name jar] {license-id :license/id} :license :as project-info}
        (project-info (get-in projects [:by-name main]))]
    (assert name (format "Project %s not found" main))
    {:project/name name
     :description (str "Test project " name)
     :project/info (pprint 16 (dissoc project-info :deps :deps-nodes))
     :license/id license-id
     :project/jar jar
     :deps.edn/extra-aliases (pprint-entries 2 (extra-aliases project-info))
     :deps.edn/deps (pprint 7 (->deps-edn-decl project-info))}))

(defn template-fn
  [edn data]
  edn)

(comment
  ;;
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
       gen-from-template)

  (->> (get-in projects [:by-name "app_0_0_local"])
       project-info
       deps-edn
       pprint)
  ;;
  )
