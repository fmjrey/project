(ns fmjrey.project
  (:import [java.lang StringBuilder]
           [java.io File])
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.tools.deps.edn :as deps]
            [fmjrey.project.specs :as specs]))

(set! *warn-on-reflection* true)

(defn- unchunk [s]
  (when (seq s)
    (lazy-seq
      (cons (first s)
            (unchunk (next s))))))

(defn- remove-work-keys
  [m]
  (into {} (remove (fn [[k _]]
                     (and (keyword? k)
                          (= (namespace k) (namespace ::here))))
                   m)))

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
  [{:keys [::path ::project] :as opts}]
  (if project
    (if (specs/valid-project? project)
      opts
      (throw
        (ex-info
          (format "Invalid :project entry in %s. %s"
                  path (specs/explain-project project))
          opts)))
    opts))

(defn expand-opts
  [{:keys [lib ::loader] :as opts}]
  (->> ["deps.edn" (->> ["deps" (namespace lib) (name lib) "deps.edn"]
                        (interpose File/separator)
                        (apply str))]
       (mapcat (fn [p] [(assoc opts ::path p)
                        (assoc opts ::path (str File/separator p))]))
       (mapcat (fn [{p ::path :as opts}]
                 (cond-> [(assoc opts ::type ::file ::file-or-res (readable? p))]
                   true (conj (-> opts
                                  (dissoc ::loader)
                                  (assoc ::type ::resource
                                         ::file-or-res
                                         (io/resource p))))
                   loader (conj (assoc opts ::type ::resource
                                       ::file-or-res
                                       (io/resource p loader))))))))

(defn- print-opts
  [{:keys [lib project ::path ::type ::file-or-res ::verbose] :as opts}]
  (let [match? (and project (= (:id project) lib))
        print-project? (and match? (= :very verbose))
        msg (as-> (StringBuilder.) $
              (.append $ (-> type name str/capitalize))
              (.append $ " ")
              (.append $ path)
              (.append $ ", ")
              (if file-or-res
                (.append $ "found and readable")
                (.append $ "not found or readable"))
              (if match?
                (.append $ ", matching id")
                (if project
                  (-> (.append $ ", mismatching id ")
                      (.append (:id project)))
                  (if file-or-res
                    (.append $ ", no project entry")
                    $)))
              (if print-project? (.append $ ":") $)
              (.append $ "\n")
              (if print-project?
                (->> (with-out-str (pp/pprint project))
                     str/split-lines
                     (reduce (fn [^StringBuilder r l]
                               (-> (.append r "  ")
                                   (.append l)
                                   (.append "\n")))
                             $))
                $)
              (str $))]
    (print msg)
    (flush)
    opts))

(defn read-edn
  [{:keys [::file-or-res ::verbose] :as opts}]
  (let [opts (or (when file-or-res
                   (with-open [rdr (io/reader file-or-res)]
                     (when-let [p (some-> rdr (deps/read-edn opts) :project)]
                       (assoc opts :project p))))
                 opts)]
    (if verbose
      (print-opts opts)
      opts)))

(defn read-project
  [{:keys [lib ::verbose] :as opts}]
  (when (valid-opts? "read-project" opts)
    (let [found (->> opts
                     expand-opts
                     unchunk
                     (map read-edn)
                     (map validate)
                     (some (fn [opts]
                             (and (= lib (some-> opts :project :id))
                                  opts))))]
      (if found
        (remove-work-keys found)
        (do (when verbose (println "No matching deps.edn found for" lib))
            opts)))))

(defn print-project
  [opts]
  (read-project (if (::verbose opts) opts (assoc opts ::verbose true))))

(defn searched-deps
  [opts]
  (when (valid-opts? "searched-deps" opts)
    (let [opts-seq (expand-opts opts)]
      (->> opts-seq
           (map read-edn)
           (map validate)
           doall))))

(defn print-searched-deps
  [opts]
  (searched-deps (if (::verbose opts) opts (assoc opts ::verbose true))))

(defmacro get-caller-classloader
  []
  `(.getClassLoader (class (proxy [Object] []))))

(defmacro project-info
  ([lib] `(project-info "project-info" ~lib))
  ([op lib]
   `(when (valid-lib? ~op ~lib)
      (some-> {:lib ~lib ::loader (get-caller-classloader)}
              read-project
              :project))))
