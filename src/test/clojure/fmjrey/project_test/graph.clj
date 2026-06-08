(ns fmjrey.project-test.graph
  "Generates test projects data as a tree of dependencies where top level
  applications (projects of type :app) depend on libraries (projects of
  type :lib or :shared-lib), themselves depending on libraries generated in
  the same way. The dependency tree graph contains the necessary data for
  generating and building test projects and SVG images of the tree.
  The idea is to build a dependency tree with various forms of projects: source
  projects, jar projects, deps.edn copied as a resource or not, etc.

  The logic for generating dependencies of a project is always the same
  regardless of the level in the dependency tree. It starts from combining
  static parameters that represent build steps for generating a project, steps
  that are referred in the code as builder(s).
  Unless it's a leaf node, each project has its own set of dependency projects
  generated with various builder combinations and not required by any other
  projects, as well as a set of dependencies required/shared by all projects
  of the same level in the tree.

  A visual representation of the tree as a set of SVG files is generated with
  GraphViz. Each node in the SVGs hyperlinks to a subgraph. For better layout
  only apps (top level roots) are represented as simple elliptical shapes,
  while dependencies are grouped by type (:lib or :shared-lib) and shown as
  a single node using the rectangular graphviz record shape
  (https://www.graphviz.org/doc/info/shapes.html#record).

  Some naming conventions in addition to those in project_test.clj:
  - projects = map containing projects and graph data (the state)
  - prjv, appv, libv = prj/app/lib vector as [name type level builder].
  - prjm, appm, libm = prj/app/lib map as per prjv->prjm and add-deps-to-prjm.
  - prjvs, appvs, libvs = sequence of prjv/appv/libv vectors.
  - dep(s) = a (list of) dependency library name(s), so same as lib(s) but in
    the context of a dependency.
  - node = a graphviz node, which is either a simple application node which id
    is the application name it represents, or a \"deps\" node representing
    several libraries required by one or more projects. The latter use the
    rectangle graphviz record shape with ports as expected by graphviz.
  - node+deps = a deps node + the deps it represents as a vector [node deps]
    where node is a map as expected by tangle (node descriptor) and deps
    a list of dependency names (libs).
  - nodes+deps = a list of node+deps.
  - id = the :id of a graphviz node."
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.math.combinatorics :as combo]
            [fmjrey.project-test.tangle :as tg]
            [clojure.java.io :as io]))

;;==============================================================================
;; Static project creation parameters that represent choices to be made for
;; for generating a test project. They are used as parameters, or steps, for
;; naming and building test projects.
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
;; These combinations are then considered as parameters or steps for building
;; a test project.
(def app-builders
  "All possible sets of build steps for generating test applications."
  (->> (combo/cartesian-product resource-deps app-artifacts)
       (mapv (partial remove nil?))))
(def lib-builders
  "All possible sets of build steps for generating test libraries."
  (->> (combo/cartesian-product resource-deps lib-artifacts)
       (mapv (partial remove nil?))))

;;==============================================================================
;; Graph/tree data structure and traversal logic.
;;
;; Note: it's getting difficult to maintain, datascript would be a better choice
;;

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
  "Return an initial empty tree of projects, with some reference data."
  [depth projects-dir]
  {:depth       depth
   :apps        [] ;; [app]
   :by-name     {} ;; {prj {:type t :name prj :level l :node o :deps [lib]
   ;;                       :deps-nodes {type [{:id id ...} [lib]]}
   ;;                       :builder (:step)}}
   :by-level    [] ;; [[prj]]
   :by-deps     {} ;; {lib [prj]}
   :node+deps   [] ;; [[{:id id ...} [lib]]]
   :adjacent    {} ;; {id [id]}
   :deps-edges #{} ;; #{[id id]}
   :app-counter 0
   :lib-counter 0
   :types {:app        {:builders   app-builders
                        :prefix     app-prefix
                        :counter    :app-counter}
           :lib        {:builders   lib-builders
                        :prefix     lib-prefix
                        :counter    :lib-counter}
           :shared-lib {:builders   lib-builders
                        :prefix     shared-lib-prefix
                        :counter    :lib-counter}}
   :projects-dir (str projects-dir)
   })

(defn prjs-from-roots
  "Return a list of all projects, starting from root projects at the top, and
  ending with library leaves at the lowest level in the tree, without any
  duplicates."
  [projects]
  (apply concat (projects :by-level)))

(defn prjs-from-leaves
  "Return a list of all projects, starting from library leaves at the lowest
  level in the tree, and ending with root projects at the top, without any
  duplicates."
  [projects]
  (apply concat (-> projects :by-level reverse)))

(defn prjs-depth-first
  "Return the list of all project names, starting from application roots or
  the given project name, and ending with library leaves, so that a project
  appears just before its dependencies (depth-first traversal).
  The resulting sequence contains duplicates for shared libraries."
  [projects root-prj]
  (tree-seq #(seq (get-in projects [:by-name %1 :deps]))
            #(get-in projects [:by-name %1 :deps])
            root-prj))

(defn prjs-leaf-first
  "Return the list of all project names in the tree rooted in all applications
  or the given project, starting from library leaves and ending with tree roots,
  so that dependencies always appear before the projects depending on them
  (post-recursive depth-first traversal).
  The resulting sequence contains duplicates for shared libraries."
  [projects root-prj]
  (let [deps (get-in projects [:by-name root-prj :deps])]
    (if (seq deps)
      (concat (mapcat (partial prjs-leaf-first projects) deps) (list root-prj))
      (list root-prj))))

(defn deps-nodes-from-roots
  "Return a list of all deps nodes, starting from top level in the tree down to
  the lowest. Does not include the list of application nodes."
  [projects]
  (->> projects :node+deps (mapv first)))

(defn deps-from-roots
  "Return a list of deps vectors for all projects, starting from the list of apps
  a the top level of  the tree, and ending with leaf projects at the bottom,
  without any duplicates."
  [projects]
  (cons (projects :apps) (deps-nodes-from-roots projects)))

(defn deps-nodes-from-leaves
  "Return a list of all deps nodes, starting from the deepest level to the top.
  Does not include the list of application nodes."
  [projects]
  (->> projects :node+deps rseq (mapv second)))

(defn deps-from-leaves
  "Return a list of deps vectors for all projects, starting from the lowest level
  in the tree, and ending with root projects at the top, without any duplicates."
  [projects]
  (conj (deps-nodes-from-leaves projects) (projects :apps)))

;;==============================================================================
;; Main logic for creating the graph/tree.
;;

(defn new-prjvs
  [projects level type]
  (let [{:keys [builders prefix counter]} (-> projects :types type)
        count-start (projects counter)
        count-end (+ count-start (count builders))]
    (map #(vector (->> (mapv name %2)
                       (concat [prefix level %1])
                       (interpose separator)
                       (apply str))
                  type level %2)
         (range count-start count-end)
         builders)))

(defn prjv->prjm [[name type level builder]]
  {:type    type
   :level   level
   :name    name
   :builder builder})

(defn deps-node-name
  "Return the name (:id) of a graphviz node representing a set of deps of a
  given type, these deps being of a project with given name and level."
  ([prj level type]
   (case type
     (:app :lib)   (str deps-prefix separator prj)
     (:shared-lib) (str shared-deps-prefix separator level))))

(defn deps-node-label
  "Format a label so that it lists all dependencies it represents in a way
  that is required by GraphViz record shape."
  [libvs]
  (str "{"
       (->> (mapv first libvs)
            (mapv #(format "<%s> %s" %1 %1))
            (interpose "|")
            (apply str))
       "}"))

(defn deps-node
  "Return a new graphviz node (a map) that represents a set of deps (libvs)
  of a given type, these deps being of a project with given name and level."
  [prj level type libvs]
  (let [deps-node-name (deps-node-name prj level type)]
    {:id deps-node-name
     :shape :record
     :label (deps-node-label libvs)}))

(defn add-deps-to-prjm
  "Add a new set of deps of a given type to a project map."
  [prjm type [_ deps :as node+deps]]
  (-> prjm
      (update :deps (fnil into []) deps)
      (update :deps-nodes assoc type node+deps)))

(defn add-deps-to-tree
  "Add to the projects tree a set of deps (libvs) of a given type as dependencies
  of one or more projects with given names and same level."
  [projects type level libvs prj-or-prjs]
  (let [deps (mapv first libvs)
        prjs (cond
               (string? prj-or-prjs)     [prj-or-prjs]
               (sequential? prj-or-prjs)  prj-or-prjs)
        {to-id :id :as deps-node} (deps-node prj-or-prjs level type libvs)
        node+deps [deps-node deps]]
    (reduce
     (fn [projects prj]
       (let [from-id (get-in projects [:by-name prj :node] prj)]
         (-> projects
             (update-in [:by-name prj] add-deps-to-prjm type node+deps)
             (update :by-deps
                     (fn [by-deps]
                       (reduce (fn [by-deps dep]
                                 (update by-deps dep (fnil conj []) prj))
                               by-deps deps)))
             (update :deps-edges conj [from-id to-id])
             (update-in [:adjacent from-id] (fnil conj []) to-id))))
     (-> (reduce #(assoc-in %1 [:by-name %2 :node] to-id) projects deps)
         (update :node+deps conj node+deps))
     prjs)))

(defn add-prjm-to-tree
  "Add a new project map to the tree."
  [projects {prj :name :keys [type level deps] :as prjm}]
  (if (contains? (projects :by-name) prj)
    (throw (ex-info (str "Project " prj " already registered") projects))
    (let [counter (get-in projects [:types type :counter])]
      (cond-> projects
        (= :app type) (update :apps conj prj)
        prj (assoc-in [:by-name prj] prjm)
        level (update-in [:by-level level] (fnil conj []) prj)
        deps (update :by-deps
                     (fn [by-deps]
                       (reduce (fn [by-deps dep]
                                 (update by-deps dep (fnil conj []) prj))
                               by-deps deps)))
        counter (update counter inc)))))

(defn add-prjvs-to-tree
  [projects prjvs]
  (->> (mapv prjv->prjm prjvs)
       (reduce add-prjm-to-tree projects)))

(defn add-new-deps-to-tree
  "Create a new set of deps of a given type to be added to the projects tree as
  dependencies of one or more projects with given names and same level."
  [level type projects prj-or-prjs]
  (let [libvs (new-prjvs projects level type)]
    (-> projects
        (add-prjvs-to-tree libvs)
        (add-deps-to-tree type level libvs prj-or-prjs))))

(defn projects
  "Generate a test project tree with the given depth and output dir. The
  generated tree starts from level 0 made of top level apps, while leaf
  projects are libraries without any dependency and separated from the top
  applications by a number of edges equal to the specified graph depth.
  The provided dir specifies where graphs SVGs are generated."
  [depth dir]
  (loop [projects (initial-projects depth dir)
         level    0]
    (cond
      (zero? level)
      (let [appvs (new-prjvs projects level :app)
            projects (add-prjvs-to-tree projects appvs)]
        (recur projects (inc level)))
      (<= level depth)
      (let [prjs (get-in projects [:by-level (dec level)])
            projects (reduce (partial add-new-deps-to-tree level :lib)
                             projects prjs)
            projects (add-new-deps-to-tree level :shared-lib projects prjs)]
        (recur projects (inc level)))
      (> level depth) projects)))

;;==============================================================================
;; Now comes the code for generating the GraphViz data and image files

(defn ->nodes+deps
  "Return a list of node+deps for a given project name.
   node+deps = a deps node map + the vector of deps it represents as a vector
               [node deps]. A deps node is the node descriptor map as expected
               by tangle, and deps is a vector of dependency names (lib names).
   nodes+deps = a list of node+deps"
  [projects prj]
  (vals (get-in projects [:by-name prj :deps-nodes])))

(defn deps-nodes
  "The nodes as expected by tangle (clojure graphviz library)."
  ([projects]
   (concat (projects :apps) (deps-nodes-from-roots projects)))
  ([projects prj-or-node+deps]

   (let [nodes+deps (cond
                      (string? prj-or-node+deps)
                      (->nodes+deps projects prj-or-node+deps)
                      (sequential? prj-or-node+deps)
                      [prj-or-node+deps])
         nodes (mapv first nodes+deps)
         nodes* (->> (mapcat second nodes+deps) ;; merge all deps
                     distinct ;; eliminate shared libs duplicates
                     (map (partial deps-nodes projects)) ;; recur
                     (mapcat next) ;; remove top node duplication
                     distinct)]
     (concat (cons (cond
                     (string? prj-or-node+deps) prj-or-node+deps
                     (sequential? prj-or-node+deps)
                     (first prj-or-node+deps))
                   nodes)
             nodes*))))

(defn deps-edges
  "The edges as expected by tangle (clojure graphviz library)."
  ([projects] (projects :deps-edges))
  ([projects prj-or-id-or-node+deps]
   (let [from-id (cond
                   (string? prj-or-id-or-node+deps)
                   (get-in projects [:by-name prj-or-id-or-node+deps :node]
                           prj-or-id-or-node+deps)
                   (sequential? prj-or-id-or-node+deps)
                   (-> prj-or-id-or-node+deps first :id))
         to-ids (get-in projects [:adjacent from-id])]
     (into (mapv #(vector from-id %1) to-ids)
           (mapcat (partial deps-edges projects) to-ids)))))

(defn deps-nodes-edges
  "The nodes and edges as expected by tangle (clojure graphviz library)."
  ([projects] [(deps-nodes projects) (deps-edges projects)])
  ([projects prj-or-node+deps]
   [(deps-nodes projects prj-or-node+deps)
    (deps-edges projects prj-or-node+deps)]))

(defn prj-attrs
  "Graphviz attributes for a project graph."
  [{:keys [subdir] :as projects}]
  {:compound true
   :concentrate true
   :graph {:dpi 72
           :rankdir "TB"
           :compound true
           :concentrate true}
   :node->id (fn [n]
               (cond
                 (map? n)    (:id n)
                 (string? n) n
                 :else (throw (ex-info (format "Invalid node %s (%s)"
                                               n (type n))
                                       projects))))
   :node->descriptor (fn [n]
                       (cond
                         (map? n) (assoc n :URL (str (io/file subdir
                                                              (str (:id n)
                                                                   ".svg"))))
                         (string? n) {:id n
                                      :URL (str (io/file subdir
                                                         (str n ".svg")))}))})

(defn app-attrs
  "Graphviz attributes for the ubergraph of all projects."
  [{:keys [depth] :as projects}]
  (update (prj-attrs projects) :graph merge {:nodesep (dec depth)
                                             :ranksep depth}))

(defn dot
  "Generate the dot language data as expected by graphviz."
  ([{:keys [projects-dir debug] :as projects}]
   ;; generate the dot data for the ubergraph of all projects
   (let [projects (assoc projects :subdir "graph")
         [nodes edges] (deps-nodes-edges projects)
         edn-file (io/file projects-dir "nodes-edges.edn")
         _ (io/make-parents edn-file)]
     (when debug
       (with-open [wr (io/writer edn-file)]
         (.write wr (with-out-str (pp/pprint [nodes edges])))))
     (tg/graph->dot nodes edges (app-attrs projects))))
  ([{:keys [projects-dir debug] :as projects} prj-or-node+deps]
   ;; generate a subtree dot data given its root as a project name or node+deps
   (let [[nodes edges] (deps-nodes-edges projects prj-or-node+deps)
         name (cond
                (sequential? prj-or-node+deps)
                (-> prj-or-node+deps first :id)
                (string? prj-or-node+deps)
                prj-or-node+deps)
         edn-file (io/file projects-dir "graph" (str name ".edn"))
         _ (io/make-parents edn-file)]
     (when debug
       (with-open [wr (io/writer edn-file)]
         (.write wr (with-out-str (pp/pprint [nodes edges])))))
     (tg/graph->dot nodes edges (prj-attrs projects)))))

(defn gen-img
  "Generate graphviz images to disk."
  ([{:keys [projects-dir debug] :as projects}]
   ;; generate the image for the ubergraph of all projects
   (let [dot (dot projects)
         svg (tg/dot->image dot "svg")]
     (when debug
       (io/copy dot (io/file projects-dir "projects.dot")))
     (io/copy svg (io/file projects-dir "projects.svg"))))
  ([{:keys [projects-dir debug] :as projects} prj-or-node+deps]
   ;; generate a subtree image given its root as a project name or node+deps
   (let [dot (dot projects prj-or-node+deps)
         svg (tg/dot->image dot "svg")
         name (cond
                (string? prj-or-node+deps) prj-or-node+deps
                (sequential? prj-or-node+deps) (-> prj-or-node+deps
                                                    first :id))
         svg-file (io/file projects-dir "graph" (str name ".svg"))]
     (io/make-parents svg-file)
     (io/copy svg svg-file)
     (when debug
       (io/copy dot (io/file projects-dir "graph" (str name ".dot")))))))

(defn gen-imgs
  "Generate graphviz images for all projects/deps nodes to disk."
  [projects]
  ;; generate the image for the ubergraph of all projects
  (gen-img projects)
  ;; generate a subtree image for each app
  (doseq [app (projects :apps)]
    (gen-img projects app))
  ;; generate a subtree image for each deps-node
  (doseq [node+deps (projects :node+deps)]
    (gen-img projects node+deps)))
