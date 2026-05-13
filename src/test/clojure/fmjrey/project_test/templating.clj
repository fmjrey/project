(ns fmjrey.project-test.templating
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.math.combinatorics :as combo]
            [fmjrey.project-test.tangle :as tg]
            [clojure.java.io :as io]))

;; Static project creation parameters that represent
;; choices to be made for creating a test project
(def deps-tree-depth 2)
(def resdeps [nil :resdeps])
(def lib-artifacts [:local :jar])
(def app-artifacts [:local :uberjar #_:war])

(def apps (combo/cartesian-product resdeps app-artifacts))
(def libs (combo/cartesian-product resdeps lib-artifacts))

(def separator "_")

(defn lib-vectors [level counter prefix]
  (->> libs
       (map (partial remove nil?))
       (map #(vector (->> (map name %2)
                          (concat [prefix level %1])
                          (interpose separator)
                          (apply str))
                     %2)
            (range counter (+ counter (count libs))))))

(defn libv->libm [type [name builder]]
  {:type    type
   :name    name
   :builder builder})

(defn deps-node-name
  ([name level type]
   (case type
     (:app :lib)   (str "deps" separator name)
     (:shared-lib) (str "shared" separator "deps" separator level)))
  ([{:keys [name level type]}]
   (deps-node-name name level type)))

(def deps-prefix (str  "deps" separator))
(def shared-deps-prefix (str "shared" separator "deps" separator))

(defn deps-node-name->project-names
  [projects deps-node-name]
  (cond
    (str/starts-with? deps-node-name deps-prefix)
    [(subs deps-node-name 5)]
    (str/starts-with? deps-node-name shared-deps-prefix)
    (let [level (Integer/parseInt (subs deps-node-name 12))]
      (get-in projects [:by-level level]))
    :else [deps-node-name]))

(defn deps-node-label
  [libvs]
  (str "{"
       (->> (map first libvs)
            (map #(format "<%s> %s" %1 %1))
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

(defn add-deps-to-libm
  [libm type libvs deps-node]
  (let [deps (mapv first libvs)]
    (-> libm
        (update :deps (fnil into []) deps)
        (update :deps-nodes assoc type [deps-node deps])
        ;;(update :deps-nodes assoc type [(assoc deps-node :URL (str (:name libm) ".svg")) deps]) ;
        )))

(defn add-deps-to-tree
  [type level libvs projects name]
  (let [deps-node (deps-node name level type libvs)
        deps (mapv first libvs)]
    (-> projects
        (update-in [:by-name name] add-deps-to-libm type libvs deps-node)
        (update :by-deps (fn [by-deps]
                           (reduce (fn [by-deps dep]
                                     (update by-deps dep (fnil conj []) name))
                                   by-deps deps)))
        (update :node+deps assoc (:id deps-node) [deps-node deps]))))

(defn add-new-to-tree
  ([type level projects libv]
   (cond
     (seq? libv)
     (reduce (partial add-new-to-tree type level) projects libv)
     (vector? libv)
     (add-new-to-tree projects (assoc (libv->libm type libv)
                                      :level level))))
  ([projects {:keys [type level name deps] :as libm}]
   (if (contains? (projects :by-name) name)
     (throw (ex-info (str "Project " name " already registered") projects))
     (let [counter (get-in projects [:type->counter type])
           count (get projects counter)
           libm (assoc libm :count count)]
       (cond-> projects
         (= :app type) (update :apps conj name)
         name (assoc-in [:by-name name] libm)
         level (update-in [:by-level level] (fnil conj []) name)
         deps (update :by-deps
                      (fn [by-deps]
                        (reduce (fn [by-deps dep]
                                  (update by-deps dep (fnil conj []) name))
                                by-deps deps)))
         counter (update counter inc))))))

(defn initial-projects
  [depth]
  {:depth      depth
   :apps       [] ;; [name]
   :by-name    {} ;; {name {:type t :name n :level l :count c :deps [name]
   ;;                       :deps-nodes {type [{:id name opts} [name]]]}}}
   :by-level   [] ;; [[name]]
   :by-deps    {} ;; {name [name]}
   :node+deps  {} ;; {name [{:id name opts} [name]]}
   :app-counter 0
   :lib-counter 0
   :type->counter {:lib        :lib-counter
                   :shared-lib :lib-counter
                   :app        :app-counter}})

(defn project-nodes [projects] (-> projects :by-name keys))
(defn project-edges [projects] (for [n (project-nodes projects)
                                     d (get-in projects [:by-name n :deps])]
                                 [n d]))

(defn project-nodes-edges
  ([projects]
   [(project-nodes projects) (project-edges projects)])
  ([projects name]
   (let [deps (get-in projects [:by-name name :deps])]
     (reduce (fn [[nodes edges] dep]
               (let [[ns es] (project-nodes-edges projects dep)]
                 [(into nodes ns) (into edges es)]))
             [(into #{name} deps) (mapv #(vector name %1) deps)]
             deps))))

(defn app-nodes [projects] (projects :apps))

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

(defn deps-edges1
  [projects from-nodes+deps]
  (for [[{from-node-name :id} from-deps] from-nodes+deps
        from-dep from-deps
        [{to-node-name :id} to-deps] (->nodes+deps projects from-dep)
        to-dep to-deps]
    [(str from-node-name ":" from-dep) (str to-node-name ":" to-dep)]))

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

(defn dot-attrs
  [projects]
  {;;:node {:shape :box}
   ;;:directed? true
   ;;:dpi 72
   ;;:rankdir "TB"
   :compound true
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

(defn dot
  ([projects]
   (let [[nodes edges] (deps-nodes-edges projects)]
     (tg/graph->dot nodes edges (dot-attrs projects))))
  ([projects name-or-node+deps]
   (let [[nodes edges] (deps-nodes-edges projects name-or-node+deps)
         name (when (sequential? name-or-node+deps)
                (-> name-or-node+deps first :id))]
     (when name
       (with-open [wr (io/writer (format "test-projects/%s.edn" name))]
         (.write wr (with-out-str (pp/pprint  [nodes edges])))))
     (tg/graph->dot nodes edges (dot-attrs projects)))))

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
  #_(doseq [project (project-nodes projects)]
    (gen-img projects project))
  (doseq [node+deps (->> projects :node+deps vals)]
    (gen-img projects node+deps)))

(defn projects
  [depth] ; depth = number of edges from the root to the furthest node
  (loop [projects (initial-projects depth)
         level    0]
    (cond
      (zero? level)
      (let [appsv (lib-vectors level (projects :app-counter) "app")]
        (recur (add-new-to-tree :app level projects appsv) (inc level)))
      (<= level depth)
      (let [prev (get-in projects [:by-level (dec level)])
            projects (reduce
                      (fn [{:keys [lib-counter] :as projects} name]
                        (let [libvs (lib-vectors level lib-counter "lib")
                              projects (add-new-to-tree :lib level
                                                        projects libvs)
                              projects (add-deps-to-tree :lib level libvs
                                                         projects name)]
                          projects))
                      projects prev)
            libvs (lib-vectors level (projects :lib-counter) "shared_lib")
            projects (add-new-to-tree :shared-lib level projects libvs)
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
