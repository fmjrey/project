(ns fmjrey.project
  (:import [java.lang StringBuilder]
           [java.io File])
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.java.basis :as basis]
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

(defn valid-search-in?
  ([op search-in] (valid-search-in? op search-in {}))
  ([op search-in opts]
   (or (specs/valid-search-in? search-in)
       (throw
        (ex-info
         (format "Invalid search-in in %s: got %s, expected one of, or a vector of #{:basis :project :resource}"
                 op search-in)
         opts)))))

(defn valid-opts?
  [op {:keys [lib ::alias ::search-in]
       :as opts}]
  (or (nil? lib)
      (valid-lib? op lib opts))
  (or (nil? alias)
      (valid-alias? op alias opts))
  (or (nil? search-in)
      (valid-search-in? op search-in opts))
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

(defn basis-project-root-deps
  [{verbose ::verbose}]
  (let [basis (basis/current-basis)
        custd (get-in basis [:basis-config :dir])
        custp (get-in basis [:basis-config :project] :standard)
        custd? (and (string? custd)
                    (not (re-matches #"^(?:\.[/\\]?)?$" custd)))
        custp? (and (string? custp)
                    (not (re-matches #"^(?:\.?[/\\])?deps\.edn$" custp)))
        filepath (cond
                   (and custd? custp?)       (str (io/file custd custp))
                   (and (not custd?) custp?) custp
                   (and custd? (not custp?)) (str (io/file custd "deps.edn"))
                   true                      "deps.edn")]
    (when verbose
      (when custd?
        (println "Found custom project path in current basis:" custd))
      (when custp?
        (println "Found custom edn path in current basis:" custp)))
    [custd custp filepath]))

(defn- start-opts
  [{:keys [lib ::search-in]
    :or {search-in [:basis :project :resource]}
    :as opts}]
  (let [search-in (if (keyword? search-in) [search-in] search-in)]
    (reduce
     (fn [r k]
       (let [nocl (dissoc opts ::loader)
             [_ custp filepath] (basis-project-root-deps opts)]
         (case k
           :basis
           (cond-> r
             true         (conj (assoc nocl ::type :current-basis))
             true         (conj (assoc nocl ::type :initial-basis))
             (map? custp) (conj (assoc nocl ::type :edn ::edn custp)))
           :project
           (cond-> r
             (not= "deps.edn" filepath)
             (conj (assoc nocl ::type :file ::path filepath))
             true
             (conj (assoc nocl ::type :file ::path "deps.edn")))
           :resource
           (cond-> r
             lib (conj (assoc opts ::type :resource
                              ::path (resource-filename lib)))))))
     [] search-in)))

(defn- expand-opts
  [{:keys [::loader] :as opts}]
  (->> (start-opts opts)
       ;; expand file to resource
       (mapcat (fn [{type ::type :as o}]
                 (if (= :file type)
                   [o (assoc o ::type :resource)]
                   [o])))
       ;; expand resources with leading / or \
       (mapcat (fn [{:keys [::type  ::path] :as o}]
                 (if (and path (= type :resource))
                   [o (assoc o ::path (str File/separator path))]
                   [o])))
       ;; add :file-or-res and expand with optional classloader if provided
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
                                                   (io/resource path loader))))
                   [o])))
       ;; throw if nothing to do
       (#(if (seq %)
           %
           (throw (ex-info "Nothing to do, possible causes: ::search-in is empty or set to :resource without a :lib entry" opts))))
       ))

(defn- print-opts
  [{:keys [lib ::path ::type ::file-or-res ::verbose ::loader ::alias]
    :or {alias default-alias}
    :as opts}]
  (let [project (get opts alias)
        match? (and project (or (nil? lib) (= (:id project) lib)))
        print-project? (and match? (= :very verbose))
        basis? (#{:current-basis :initial-basis} type)
        msg (as-> (StringBuilder.) $
              (.append $ "  ")
              (.append $ (-> type name str/capitalize))
              (cond-> $
                path (.append " ")
                path (.append path))
              (.append $ ", ")
              (cond-> $
                loader (.append "with caller classloader, ")
                file-or-res (.append "found and readable")
                (not (or file-or-res basis?)) (.append "not found or readable")
                (and basis? (not match?)) (.append "not found")
                ;;
                )
              (if match?
                (cond-> $
                  basis? (.append "found")
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
  [{:keys [::type ::file-or-res ::verbose ::alias ::edn]
    :or {alias default-alias}
    :as opts}]
  (let [edn (case type
              :edn edn
              (:file :resource) (when file-or-res
                                  (with-open [rdr (io/reader file-or-res)]
                                    (deps/read-edn rdr opts)))
              :current-basis (basis/current-basis)
              :initial-basis (basis/initial-basis)
              (throw (ex-info (str "Invalid type " type) opts)))
        project (get-in edn [:aliases alias])
        opts (cond-> opts
               ;;edn (assoc ::edn edn)
               project (assoc alias project))]
    (if verbose
      (print-opts opts)
      opts)))

(defn- print-header
  [{:keys [lib ::verbose ::alias]
    :or {alias default-alias}
    :as opts}]
  (when verbose
    (if lib
      (println "Searching for id" (str lib) "in alias" alias)
      (println "Searching for project info in alias" alias)))
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
              (print " in:")
              (doseq [{:keys [::type ::path ::loader]} opts-list]
                (println)
                (print "  ")
                (if path
                  (do (print path "(as" (name type))
                      (when (and loader (= :resource type))
                        (print "with caller classloader"))
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
  ([] `(project-info {}))
  ([lib-or-opts]
   `(let [{lib# :lib alias# ::alias loader# ::loader
           :or {alias# default-alias}
           :as opts#} (cond
           (map? ~lib-or-opts)
           ~lib-or-opts
           (nil? ~lib-or-opts)
           {}
           (valid-lib? "project-info" ~lib-or-opts)
           {:lib ~lib-or-opts})]
      (cond-> opts# ;;(if (map? ~lib-or-opts) ~lib-or-opts {})
        (nil? loader#) (assoc ::loader (caller-classloader))
        true (some-> read-project alias#)))))
