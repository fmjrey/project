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

(defn lib-vectors [level counter prefix]
  (->> libs
       (map (partial remove nil?))
       (map #(vector (->> (map name %2)
                          (concat [prefix level %1])
                          (interpose "_")
                          (apply str))
                     %2)
            (range counter (+ counter (count libs))))))

(defn libv->libm [type [name builder]]
  {:type    type
   :name    name
   :builder builder})

(defn add-deps-to-libm
  ([type libv libvs]
   (add-deps-to-libm (libv->libm type libv) libvs))
  ([libm libvs]
   (update libm :deps (fnil into []) (map first libvs))))

(defn add-deps-to-tree
  [libvs projects name]
  (-> projects
      (update-in [:by-name name] add-deps-to-libm libvs)
      (update :by-deps (fn [by-deps]
                         (reduce (fn [by-deps dep]
                                   (update by-deps dep (fnil conj []) name))
                                 by-deps (map first libvs))))))

(defn ->cluster-prefix
  [name-or-level]
  (some->> name-or-level (str "cluster_") keyword))

(defn cluster->dummy-edges
  "Return a list of invisible edges between nodes of a cluster.
  See https://stackoverflow.com/questions/47295225/vertical-alignment-of-nodes-in-graphviz"
  [projects cluster]
  (loop [res []
         clusters (some-> projects :by-cluster cluster)]
    (if (seq (next clusters))
      (recur (conj res [(first clusters) (first (next clusters))
                        {:style :invis :rank :same}])
             (next clusters))
      res)))

(defn cluster->dummy-name
  "Derive a dummy node name for a given cluster.
  See https://stackoverflow.com/questions/2012036/graphviz-how-to-connect-subgraphs#comment14678124_2012106"
  [cluster]
  (when cluster
    (cond-> cluster
      (string? cluster) (->cluster-prefix)
      true (->> name (str "dummy_")))))

(defn ->dummym
  [level cluster]
  {:type :dummy
   :level level
   :name (cluster->dummy-name cluster)
   :cluster (->cluster-prefix cluster)})

(defn add-new-to-tree
  ([type level cluster projects libv]
   (cond
     (seq? libv)
     (cond-> (reduce (partial add-new-to-tree type level cluster) projects libv)
       cluster (add-new-to-tree (->dummym level cluster)))
     (vector? libv)
     (add-new-to-tree projects (cond-> (libv->libm type libv)
                                 level   (assoc :level level)
                                 cluster (assoc :cluster
                                                (->cluster-prefix cluster))))))
  ([projects {:keys [type level name deps cluster] :as libm}]
   (if (contains? (projects :by-name) name)
     (throw (ex-info (str "Project " name " already registered") projects))
     (cond-> projects
       (= :app type) (update :apps conj name)
       cluster (update :clusters conj cluster)
       cluster (update-in [:by-cluster cluster] (fnil conj []) name)
       name (assoc-in [:by-name name] libm)
       level (update-in [:by-level level] (fnil conj []) name)
       deps (update :by-deps (fn [by-deps]
                               (reduce (fn [by-deps dep]
                                         (update by-deps dep (fnil conj []) name))
                                       by-deps deps)))
       (= :lib type) (update :lib-counter inc)
       (= :app type) (update :app-counter inc)))))

(def initial-projects
  {:apps       [] ; [name name name]
   :by-name    {} ; {name {:type t :name n :level l :cluster c}}
   :by-level   [] ; [[name name] [name name] [name name]]
   :by-deps    {} ; {name [name name] name [name name]}
   :by-cluster {} ; {:name [name name] :name [name name]}
   :clusters  #{} ; #[name name name}
   :app-counter 0
   :lib-counter 0})

(defn nodes [projects] (-> projects :by-name keys))
(defn edges [projects] (for [n (nodes projects)
                             d (get-in projects [:by-name n :deps])]
                         [n d]))

(defn nodes-edges
  ([projects]
   [(nodes projects) (edges projects)])
  ([projects name]
   (let [deps (get-in projects [:by-name name :deps])]
     (reduce (fn [[nodes edges] dep]
               (let [[ns es] (nodes-edges projects dep)]
                 [(into nodes ns) (into edges es)]))
             [(into #{name} deps) (mapv #(vector name %1) deps)]
             deps))))

(defn cluster-edge [projects from to]
  (let [from-c (get-in projects [:by-name from :cluster])
        to-c (get-in projects [:by-name to :cluster])]
    #_[from to (cond-> {}
               (and from-c (str/starts-with? to "shared"))
               (assoc :ltail (name from-c))
               to-c
               (assoc :lhead (name to-c)))]
    [(if (and from-c (str/starts-with? to "shared"))
       (cluster->dummy-name from-c)
       from)
     (or (cluster->dummy-name to-c) to)
     (cond-> {}
       (and from-c (str/starts-with? to "shared"))
       (assoc :ltail (name from-c))
       to-c
       (assoc :lhead (name to-c)))]))

(defn cluster-edges
  ([projects]
   (into
    #{}
    (for [{from :name deps :deps} (-> projects :by-name vals)
          to (mapv #(get-in projects [:by-name %1 :name]) deps)]
      (cluster-edge projects from to))))
  ([projects name]
   (into
    #{}
    (let [deps (get-in projects [:by-name name :deps])]
      (reduce (fn [edges dep]
                (into edges (cluster-edges projects dep)))
              (mapv (partial cluster-edge projects name) deps)
              deps)))))

(defn nodes-cluster-edges
  ([projects]
   (let [clusters (projects :clusters)
         dummy-nodes (->> (map cluster->dummy-name clusters)
                          (into #{}))
         dummy-edges (mapcat (partial cluster->dummy-edges projects)
                             clusters)]
     [(concat dummy-nodes (nodes projects))
      (concat dummy-edges (cluster-edges projects))]))
  ([projects name]
   (let [deps (get-in projects [:by-name name :deps])
         clusters (->> (map #(get-in projects [:by-name %1 :cluster])
                            (conj deps name))
                       (filter some?)
                       (into #{}))
         dummy-nodes (->> (map cluster->dummy-name clusters)
                          (into #{}))
         dummy-edges (mapcat (partial cluster->dummy-edges projects)
                             clusters)]
     (reduce (fn [[nodes edges] dep]
               (let [[ns es] (nodes-cluster-edges projects dep)]
                 [(into nodes ns) (into edges es)]))
             [(into (conj dummy-nodes name) deps)
              (concat dummy-edges (mapv (partial cluster-edge projects name)
                                        deps))]
             deps))))

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
   :subgraphs (mapv (fn [cluster] {:name cluster
                                   ;;:shape :box
                                   ;;:rankdir "TB"
                                   })
                    (projects :clusters))
   :node->cluster (fn [name] (some-> projects :by-name (get name) :cluster))
   :node->descriptor (fn [name]
                       (if (= :dummy (get-in projects [:by-name name :type]))
                         {:shape :point :style :invis :height 0 :width 0}
                         {:shape :plaintext
                          :rankdir "TB"
                          ;;:label name
                          :URL (str name ".svg")}))
   :cluster->descriptor (fn [name] {;;:shape :box
                                    :rankdir "LR"
                                    ;;:id (clojure.core/name name)
                                    ;;:label name
                                    })
   :cluster->id name ;;
   })

(defn dot
  ([projects]
   (let [[nodes edges] (nodes-cluster-edges projects)]
     (tg/graph->dot nodes edges (dot-attrs projects))))
  ([projects name]
   (let [[nodes edges] (nodes-cluster-edges projects name)]
     (tg/graph->dot nodes edges (dot-attrs projects)))))

(defn gen-img
  ([projects]
   (let [dot (dot projects)
         svg (tg/dot->image dot "svg")]
     (io/copy dot (io/file "test-projects/projects.dot"))
     (io/copy svg (io/file "test-projects/projects.svg"))))
  ([projects name]
   (when (#{:app :lib} (get-in projects [:by-name name :type]))
     (let [dot (dot projects name)
           svg (tg/dot->image dot "svg")]
       (io/copy dot (io/file "test-projects" (str name ".dot")))
       (io/copy svg (io/file "test-projects" (str name ".svg")))))))

(defn gen-imgs
  [projects]
  (with-open [wr (io/writer "test-projects/projects.edn")]
    (.write wr (with-out-str (pp/pprint projects))))
  (gen-img projects)
  (doseq [project (nodes projects)]
    (gen-img projects project)))

(defn projects
  [depth] ; depth = number of edges from the root to the furthest node
  (loop [projects initial-projects
         level    0]
    (cond
      (zero? level)
      (let [appsv (lib-vectors level (projects :app-counter) "app")]
        (recur (add-new-to-tree :app level nil projects appsv) (inc level)))
      (<= level depth)
      (let [prev (get-in projects [:by-level (dec level)])
            projects (reduce
                      (fn [{:keys [lib-counter] :as projects} name]
                        (let [libvs (lib-vectors level lib-counter "lib")
                              projects (add-new-to-tree :lib level
                                                        name
                                                        projects libvs)
                              projects (add-deps-to-tree libvs projects name)]
                          projects))
                      projects prev)
            libvs (lib-vectors level (projects :lib-counter) "shared_lib")
            projects (add-new-to-tree :lib level
                                      (str "level" level)
                                      projects libvs)
            projects (reduce (partial add-deps-to-tree libvs) projects prev)]
        (recur projects (inc level)))
      (> level depth) projects)))

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
