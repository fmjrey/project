(ns fmjrey.project-test.templating
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.math.combinatorics :as combo]
            [fmjrey.project-test.tangle :as tg]
            [clojure.java.io :as io]))

;;==============================================================================
;; Static project creation parameters that represent choices to be made for
;; forenerating a test project. They are used as parameters, or steps, to the
;; generation logic and for naming test projects.
;; Due to graphviz constraints, these keywords must not contain the hyphen
;; character '-' but can contain the underline character '_'.

(def resource-deps
  "Whether deps.edn is copied to a resource directory or not"
  [nil :copydeps])
(def lib-artifacts
  "Whether the library is to be required locally from source or as a jar"
  [:local :jar])
(def app-artifacts
  "Whether the application is to be run locally from source or as a uberjar"
  [:local :uberjar #_:war])

;; Generate all combinations of static parameters above for both apps and libs.
;; These combinations are then considered as parameters or steps for generating
;; a test project.
(def apps (->> (combo/cartesian-product resource-deps app-artifacts)
               (mapv (partial remove nil?))))
(def libs (->> (combo/cartesian-product resource-deps lib-artifacts)
               (mapv (partial remove nil?))))

;;==============================================================================
;;
;; The following represent test projects as a tree of dependencies where top
;; level applications (projects of type :app) depend on libraries (projects of
;; type :lib or :shared-lib), themselves depending on libraries generated in
;; the same way.
;; Projects are stored into a single map which contains data for generating
;; the project as well as data needed to generate graphviz SVGs.
;; The generation of dependencies for a project is always the same regardless
;; of the level in the dependency tree, and is derived from the combination of
;; static parameters defined above to name and create projects. Unless it's a
;; leaf node, each library project has its own unique set of dependencies, as
;; well as a set of dependencies shared by all at the same level in the tree.
;; Therefore the only parameter to the generation of test projects is the
;; depth of the tree to generate, level 0 being the top level apps, while leaf
;; projects are libraries without any dependency and separated from the top
;; applications by a number of edges equal to the specified graph depth.
;; For better layout only apps (top level roots) are represented as simple
;; nodes, while dependencies are grouped by type (:lib or :shared-lib) and
;; shown as a single node using graphviz record shape.
;;
;; Some naming conventions:
;; project = either an application or a library
;; prj, app, lib = the name of a project, application, or library
;; prjv, appv, libv = project/app/lib vector as [name type level builder]
;; prjm, appm, libm = project/app/lib map as per prjv->prjm and add-deps-to-libm
;; prjvs, appvs, libvs = sequence of project/app/lib vectors
;; dep(s) = (list of) dependency library name(s)
;; node = a graphviz node, which is either a simple application node which is
;;        just made up of the application name, or a "deps" node representing
;;        several libraries required by one or more projects. The latter use
;;        the graphviz record shape with a label as expected by graphviz, see
;;        https://www.graphviz.org/doc/info/shapes.html#record.
;; node+deps = a deps node and the deps it represents as a vector [node deps]
;;             where node is a map as expected by tangle (node descriptor) and
;;             deps a list of dependency names (lib names).
;; nodes+deps = a list of node+deps

(def separator
  "Separator character to be used instead of hyphen '-'"
  "_")

;; Prefix used for generating project names
(def app-prefix "app")
(def lib-prefix "lib")
(def shared-lib-prefix (str "shared" separator lib-prefix))

;; Prefix used for generating node names
(def deps-prefix "deps")
(def shared-deps-prefix (str "shared" separator deps-prefix))

(defn initial-projects
  "Return an initial empty list of projects, with some reference data."
  [depth]
  {:depth      depth
   :apps       [] ;; [name]
   :by-name    {} ;; {name {:type t :name n :level l :count c :deps [name]
   ;;                       :deps-nodes {type [{:id name opts} [name]]}}}
   :by-level   [] ;; [[name]]
   :by-deps    {} ;; {name [name]}
   :node+deps  {} ;; {name [{:id name opts} [name]]}
   :app-counter 0
   :lib-counter 0
   :types {:app        {:basis   apps
                        :prefix  app-prefix
                        :counter :app-counter}
           :lib        {:basis   libs
                        :prefix  lib-prefix
                        :counter :lib-counter}
           :shared-lib {:basis   libs
                        :prefix  shared-lib-prefix
                        :counter :lib-counter}}})

(defn app-nodes [projects] (projects :apps))

(defn new-prjvs
  [projects level type]
  (let [{:keys [basis prefix counter]} (-> projects :types type)
        count-start (projects counter)
        count-end (+ count-start (count basis))]
    (map #(vector (->> (mapv name %2)
                       (concat [prefix level %1])
                       (interpose separator)
                       (apply str))
                  type level %2)
         (range count-start count-end)
         basis)))

(defn prjv->prjm [[name type level builder]]
  {:type    type
   :level   level
   :name    name
   :builder builder})

(defn deps-node-name
  ([name level type]
   (case type
     (:app :lib)   (str deps-prefix separator name)
     (:shared-lib) (str shared-deps-prefix separator level))))

(defn deps-node-label
  [libvs]
  (str "{"
       (->> (mapv first libvs)
            (mapv #(format "<%s> %s" %1 %1))
            (interpose "|")
            (apply str))
       "}"))

(defn deps-node
  [name level type libvs]
  (let [deps-node-name (deps-node-name name level type)]
    {:id deps-node-name
     :shape :record
     :label (deps-node-label libvs)
     :URL (str deps-node-name ".svg")}))

(defn add-deps-to-prjm
  [prjm type libvs deps-node]
  (let [deps (mapv first libvs)]
    (-> prjm
        (update :deps (fnil into []) deps)
        (update :deps-nodes assoc type [deps-node deps]))))

(defn add-deps-to-tree
  [type level libvs projects name]
  (let [deps-node (deps-node name level type libvs)
        deps (mapv first libvs)]
    (-> projects
        (update-in [:by-name name] add-deps-to-prjm type libvs deps-node)
        (update :by-deps (fn [by-deps]
                           (reduce (fn [by-deps dep]
                                     (update by-deps dep (fnil conj []) name))
                                   by-deps deps)))
        (update :node+deps assoc (:id deps-node) [deps-node deps]))))

(defn add-new-prjm-to-tree
  [projects {:keys [type level name deps] :as prjm}]
  (if (contains? (projects :by-name) name)
    (throw (ex-info (str "Project " name " already registered") projects))
    (let [counter (get-in projects [:types type :counter])]
      (cond-> projects
        (= :app type) (update :apps conj name)
        name (assoc-in [:by-name name] prjm)
        level (update-in [:by-level level] (fnil conj []) name)
        deps (update :by-deps
                     (fn [by-deps]
                       (reduce (fn [by-deps dep]
                                 (update by-deps dep (fnil conj []) name))
                               by-deps deps)))
        counter (update counter inc)))))

(defn add-new-prjvs-to-tree
  [projects prjvs]
  (->> (mapv prjv->prjm prjvs)
       (reduce add-new-prjm-to-tree projects)))

(defn ->nodes+deps
  [projects name]
  (vals (get-in projects [:by-name name :deps-nodes])))

(defn deps-nodes
  ([projects]
   (mapcat (partial deps-nodes projects) (app-nodes projects)))
  ([projects name-or-node+deps]
   (let [nodes+deps (cond
                      (string? name-or-node+deps)
                      (->nodes+deps projects name-or-node+deps)
                      (sequential? name-or-node+deps)
                      [name-or-node+deps])
         nodes (mapv first nodes+deps)
         nodes* (->> (mapcat second nodes+deps)
                     distinct
                     (map (partial deps-nodes projects))
                     (mapcat next)
                     distinct)]
     (concat (cons (cond
                     (string? name-or-node+deps) name-or-node+deps
                     (sequential? name-or-node+deps)
                     (-> name-or-node+deps first))
                   nodes)
             nodes*))))

(defn deps-edges*
  [projects from-nodes+deps]
  (for [[{from-node-name :id} from-deps] from-nodes+deps
        from-dep from-deps
        [{to-node-name :id} to-deps] (->nodes+deps projects from-dep)
        to-dep to-deps]
    (cons [(if (str/starts-with? to-node-name shared-deps-prefix)
             from-node-name
             (str from-node-name ":" from-dep))
           to-node-name]
          (deps-edges* projects (->nodes+deps projects to-dep)))))

(defn deps-edges
  ([projects] (reduce into #{} (map (partial deps-edges projects)
                                    (projects :apps))))
  ([projects name-or-node+deps]
   (let [[name nodes+deps] (cond
                             (string? name-or-node+deps)
                             [name-or-node+deps
                              (->nodes+deps projects name-or-node+deps)]
                             (sequential? name-or-node+deps)
                             [(-> name-or-node+deps first :id)
                              [name-or-node+deps]])
         to-node-names (mapv (comp :id first) nodes+deps)]
     (into (into #{} (when (string? name-or-node+deps)
                       (map #(vector name %1) to-node-names)))
           (apply concat (deps-edges* projects nodes+deps))))))

(defn deps-nodes-edges
  ([projects] [(deps-nodes projects) (deps-edges projects)])
  ([projects name]
   [(deps-nodes projects name) (deps-edges projects name)]))

(defn prj-attrs
  [projects]
  {:compound true
   :concentrate true
   :graph {:dpi 72
           :rankdir "TB"
           :compound true
           :concentrate true}
   :node->id (fn [n] (cond
                       (map? n)    (:id n)
                       (string? n) n
                       :else (throw (ex-info (format "Invalid node %s (%s)"
                                                     n (type n))
                                             projects))))
   :node->descriptor (fn [n] (when (map? n) n))})

(defn app-attrs
  [projects]
  (update (prj-attrs projects) :graph merge {:nodesep 2 :ranksep 1.5}))

(defn dot
  ([projects]
   (let [[nodes edges] (deps-nodes-edges projects)]
     (tg/graph->dot nodes edges (app-attrs projects))))
  ([projects name-or-node+deps]
   (let [[nodes edges] (deps-nodes-edges projects name-or-node+deps)
         name (when (sequential? name-or-node+deps)
                (-> name-or-node+deps first :id))]
     (when name
       (with-open [wr (io/writer (format "test-projects/%s.edn" name))]
         (.write wr (with-out-str (pp/pprint  [nodes edges])))))
     (tg/graph->dot nodes edges (prj-attrs projects)))))

(defn gen-img
  ([projects]
   (let [dot (dot projects)
         svg (tg/dot->image dot "svg")]
     (io/copy dot (io/file "test-projects/projects.dot"))
     (io/copy svg (io/file "test-projects/projects.svg"))))
  ([projects name-or-node+deps]
   (let [dot (dot projects name-or-node+deps)
         svg (tg/dot->image dot "svg")
         name (cond
                (string? name-or-node+deps) name-or-node+deps
                (sequential? name-or-node+deps) (-> name-or-node+deps
                                                    first :id))]
     (io/copy dot (io/file "test-projects" (str name ".dot")))
     (io/copy svg (io/file "test-projects" (str name ".svg"))))))

(defn gen-imgs
  [projects]
  (gen-img projects)
  (doseq [node+deps (->> projects :node+deps vals)]
    (gen-img projects node+deps)))

(defn projects
  [depth] ; depth = number of edges from the root to the furthest node
  (loop [projects (initial-projects depth)
         level    0]
    (cond
      (zero? level)
      (let [appvs (new-prjvs projects level :app)]
        (recur (add-new-prjvs-to-tree projects appvs) (inc level)))
      (<= level depth)
      (let [prev (get-in projects [:by-level (dec level)])
            projects (reduce
                      (fn [projects name]
                        (let [libvs (new-prjvs projects level :lib)
                              projects (add-new-prjvs-to-tree projects libvs)
                              projects (add-deps-to-tree :lib level libvs
                                                         projects name)]
                          projects))
                      projects prev)
            libvs (new-prjvs projects level :shared-lib)
            projects (add-new-prjvs-to-tree projects libvs)
            projects (reduce (partial add-deps-to-tree :shared-lib level libvs)
                             projects prev)]
        (recur projects (inc level)))
      (> level depth) projects)))

(defn gen
  [depth]
  (let [projects (projects depth)]
    (with-open [wr (io/writer "test-projects/projects.edn")]
      (.write wr (with-out-str (pp/pprint projects))))
    (gen-imgs projects)))

;; Run conditions
(def execution-time [:runtime :devtime])
(def where [:app :lib])
(def query [:app :lib])

(defn data-fn
  [data]
  data)

(defn template-fn
  [edn data]
  edn)
