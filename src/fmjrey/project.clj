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
  (not-empty (into {}
                   (remove (fn [[k _]]
                             (and (keyword? k)
                                  (= (namespace k) (namespace ::here))))
                           m))))

(defn valid-lib?
  ([op lib] (valid-lib? op lib {}))
  ([op lib opts]
   (or (specs/valid-lib? lib)
       (throw
        (ex-info
         (format "Invalid lib in %s: got %s, expected a qualified symbol"
                 op lib)
         opts)))))

(defn valid-alias?
  ([op alias] (valid-alias? op alias {}))
  ([op alias opts]
   (or (specs/valid-alias? alias)
       (throw
        (ex-info
         (format "Invalid alias in %s: got %s, expected a keyword"
                 op alias)
         opts)))))

(def default-alias :project/info)

(defn valid-opts?
  [op {:keys [lib ::alias]
         :as opts}]
  (or (nil? lib)
      (valid-lib? op lib opts))
  (or (nil? alias)
      (valid-alias? op alias opts))
  (cond-> opts
    (nil? alias) (assoc ::alias default-alias)))

(defn readable?
  [f]
  (try
    (let [ff (io/file f)]
      (when (and (.exists ff) (.canRead ff))
        ff))
    (catch Exception _ nil)))

(defn validate-project
  [{:keys [::path ::project-key] :as opts}]
  (if-let [project (get opts project-key)]
    (if (specs/valid-project? project)
      opts
      (throw
       (ex-info
        (format "Invalid %s in %s. %s"
                project-key path (specs/explain-project project))
        opts)))
    opts))

(defn resource-filename
  [lib]
  (->> ["deps" (namespace lib) (name lib) "deps.edn"]
       (interpose File/separator)
       (apply str)))

(defn expand-opts
  [{:keys [lib ::loader] :as opts}]
  (->> (cond-> [(assoc opts ::type :file ::path "deps.edn")]
         lib (conj (assoc opts ::type :resource ::path (resource-filename lib))))
       ;; expand with leading /
       (mapcat (fn [{path ::path :as o}]
                 [o (assoc o ::path (str File/separator path))]))
       ;; expand file to resource and vice-versa
       (mapcat (fn [{type ::type :as o}]
                 (case type
                   :file     [o (assoc o ::type :resource)]
                   :resource [o (assoc o ::type :file)])))
       ;; add :file-or-res and expand with optional :loader if provided
       (mapcat (fn [{:keys [::path ::type] :as o}]
                 (case type
                   :file     [(-> o
                                  (dissoc ::loader)
                                  (assoc  ::file-or-res (readable? path)))]
                   :resource (cond-> [(-> o
                                          (dissoc ::loader)
                                          (assoc  ::file-or-res
                                                  (io/resource path)))]
                               loader (conj (assoc o ::file-or-res
                                                   (io/resource path loader)))))))))

(defn- print-opts
  [{:keys [lib ::path ::type ::file-or-res ::verbose ::loader ::alias]
    :or {alias default-alias}
    :as opts}]
  (let [project (get opts alias)
        match? (and project (or (nil? lib) (= (:id project) lib)))
        print-project? (and match? (= :very verbose))
        msg (as-> (StringBuilder.) $
              (.append $ "  ")
              (.append $ (-> type name str/capitalize))
              (cond-> $
                path (.append " ")
                path (.append path))
              (.append $ ", ")
              (cond-> $
                loader (.append "with classloader, ")
                file-or-res (.append "found and readable")
                (not file-or-res) (.append "not found or readable")
                ;;
                )
              (if match?
                (cond-> $
                  lib (.append ", matching id")
                  (nil? lib) (.append ", found id ")
                  (nil? lib) (.append (:id project)))
                (if project
                  (cond-> $
                    lib (.append ", mismatching id ")
                    lib (.append (:id project))
                    file-or-res (.append ", no ")
                    file-or-res (.append (str alias)))
                  $))
              (if print-project? (.append $ ":") $)
              (.append $ "\n")
              (if print-project?
                (->> (with-out-str (pp/pprint project))
                     str/split-lines
                     (reduce (fn [^StringBuilder r l]
                               (-> (.append r "    ")
                                   (.append l)
                                   (.append "\n")))
                             $))
                $)
              (str $))]
    (print msg)
    (flush)
    opts))

(defn read-edn
  [{:keys [::file-or-res ::verbose ::alias]
    :or {alias default-alias}
    :as opts}]
  (let [opts (or (when file-or-res
                   (with-open [rdr (io/reader file-or-res)]
                     (when-let [p (some-> rdr
                                          (deps/read-edn opts)
                                          (get-in [:aliases alias]))]
                       (assoc opts alias p))))
                 opts)]
    (if verbose
      (print-opts opts)
      opts)))

(defn- print-header
  [{:keys [lib ::verbose ::alias]
    :or {alias default-alias}
    :as opts}]
  (when verbose
    (if lib
      (println "Searching for id" (str lib) "in" alias)
      (println "Searching for project info in" alias)))
  opts)

(defn- print-summary
  [{:keys [lib ::verbose ::alias] :or {alias default-alias}} matching-opts]
  (let [opts-list (if (map? matching-opts)
                     [matching-opts]
                     matching-opts)
        count (count opts-list)]
    (when verbose
      (if (zero? count)
        (if lib
          (print "No matching deps.edn found with id" lib)
          (print "No deps.edn found with" alias))
        (do (if lib
              (print "Found" count "matching deps.edn with id" lib)
              (print "Found" count "matching deps.edn with" alias))
            (when (= :very verbose)
              (print ":")
              (doseq [{:keys [::type ::path ::loader]} opts-list]
                (println)
                (print "  ")
                (if path
                  (do (print path "(as" (name type))
                      (when (and loader (= :resource type))
                        (print "with classloader"))
                      (print ")"))
                  (print (name type)))))))
      (newline)))
  matching-opts)

(defn- matching?
  [{:keys [lib ::alias]
    :or {alias default-alias}
    :as opts}]
  (or (and (not lib)
           (some-> opts alias :id)
           opts)
      (and lib
           (= lib (some-> opts alias :id))
           opts)))

(defn read-project
  [opts]
  (->> opts
       (valid-opts? "read-project")
       print-header
       expand-opts
       unchunk
       (map read-edn)
       (map validate-project)
       (some matching?)
       (print-summary opts)
       remove-work-keys))

(defn print-project
  [opts]
  (read-project (if (::verbose opts) opts (assoc opts ::verbose true))))

(defn searched-deps
  [opts]
  (let [searched (->> opts
                      (valid-opts? "searched-deps")
                      print-header
                      expand-opts
                      (map read-edn)
                      (map validate-project))]
    (->> searched
         (filter matching?)
         (print-summary opts))
    searched))

(defn print-searched-deps
  [opts]
  (searched-deps (if (::verbose opts) opts (assoc opts ::verbose true))))

(defmacro caller-classloader
  []
  `(.getClassLoader (class (proxy [Object] []))))

(defmacro project-info
  ([lib-or-opts] `(project-info "project-info" ~lib-or-opts))
  ([op lib-or-opts]
   `(let [lib# (if (map? ~lib-or-opts)
                 (:lib ~lib-or-opts)
                 ~lib-or-opts)
          opts# (if (map? ~lib-or-opts)
                  (assoc ~lib-or-opts ::loader (caller-classloader))
                  {:lib lib# ::loader (caller-classloader)})
          alias# (or (::alias opts#) default-alias)]
      (when (or (nil? lib#) (valid-lib? ~op lib#))
        (some-> opts# read-project alias#)))))
