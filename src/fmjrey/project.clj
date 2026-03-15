(ns fmjrey.project
  (:require [clojure.java.io :as io]
            [clojure.tools.deps.edn :as deps]
            [fmjrey.project.specs :as specs]))

(set! *warn-on-reflection* true)

(def ^:private fsep java.io.File/separator)

(defn valid-lib?
  ([op lib] (valid-lib? op lib {}))
  ([op lib opts]
   (or (specs/valid-lib? lib)
       (throw
        (ex-info
         (format "Invalid lib in %s: got %s, expected a qualified symbol"
                 op lib)
         opts)))))

(defn valid-opts?
  [op & {lib :lib :as opts}]
  (or (specs/valid-lib? lib)
      (throw
        (ex-info
          (format "Invalid lib in %s: got %s, expected a qualified symbol"
                  op lib)
          opts))))

(defn readable?
  [f]
  (try
    (let [ff (io/file f)]
      (when (and (.exists ff) (.canRead ff))
        ff))
    (catch Exception _ nil)))

(defn validate
  [{:keys [lib path project] :as opts}]
  (if project
    (if (specs/valid-project? project)
      opts
      (throw
        (ex-info
          (format "Invalid :project entry in %s. %s"
                  path (specs/explain-project project))
          opts)))
    opts))

(defn read-edn
  [{file-or-res :file-or-res :as opts}]
  (if file-or-res
    (with-open [rdr (io/reader file-or-res)]
      ;(println "Reading" (-> opts :type name) (:path opts))
      (if-let [p (some-> rdr (deps/read-edn opts) :project)]
        (assoc opts :project p)
        opts))
    opts))

(defn unchunk [s]
  (when (seq s)
    (lazy-seq
      (cons (first s)
            (unchunk (next s))))))

(defn expand-opts
  "I don't do a whole lot."
  [{:keys [lib loader] :as opts}]
  (->> ["deps.edn" (->> ["deps" (namespace lib) (name lib) "deps.edn"]
                        (interpose fsep)
                        (apply str))]
       (mapcat (fn [p] [(assoc opts :path p)
                        (assoc opts :path (str fsep p))]))
       (mapcat (fn [{p :path :as opts}]
                 (cond-> [(assoc opts :type :file :file-or-res (readable? p))]
                   true (conj (-> opts
                                  (dissoc :loader)
                                  (assoc :type :res
                                         :file-or-res
                                         (io/resource p))))
                   loader (conj (assoc opts :type :res
                                       :file-or-res
                                       (io/resource p loader))))))))

(defn read-deps
  [opts]
  (when (valid-opts? "read-deps" opts)
    (let [opts-seq (-> opts expand-opts unchunk)]
      (->> opts-seq
           (map read-edn)
           (map validate)
           (some (fn [opts]
                   (let [id (some-> opts :project :id)]
                     #_(if (= lib id)
                         (println "Matched" (-> opts :type name) (:path opts))
                         (println "Mismatch" (-> opts :type name)
                                  (:path opts) id))
                     opts)))))))

(defn read-all-deps
  [opts]
  (when (valid-opts? "read-all-deps" opts)
    (let [opts-seq (expand-opts opts)]
      (->> opts-seq
           (map read-edn)
           (map validate)
           doall))))

(defmacro get-caller-classloader
  []
  `(.getClassLoader (class (proxy [Object] []))))

(defmacro project-info
  ([lib] `(project-info "project-info" ~lib))
  ([op lib]
   `(when (valid-lib? ~op ~lib)
      (or (some-> {:lib ~lib :loader (get-caller-classloader)}
                  read-deps
                  :project)
          {:id "project-not-found"
           :name (format "*Project %s not found*" ~lib)}))))
