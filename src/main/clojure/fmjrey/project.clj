(ns fmjrey.project
  (:import [java.lang StringBuilder]
           [java.io File Reader])
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

(defn- remove-ns-keys
  ([m] (remove-ns-keys m (namespace ::here)))
  ([m ns-str]
   (not-empty (reduce-kv #(if (= ns-str (namespace %2)) %1 (assoc %1 %2 %3))
                         {} m))))

(defn valid-lib?
  ([op lib] (valid-lib? op lib {}))
  ([op lib opts]
   (or (specs/valid-lib? lib)
       (throw
         (ex-info
           (format "Invalid lib in %s: got %s, expected a qualified symbol"
                   op lib)
           opts)))))

(defn valid-search-in?
  ([op search-in] (valid-search-in? op search-in {}))
  ([op search-in opts]
   (or (specs/valid-search-in? search-in)
       (throw
        (ex-info
         (format "Invalid search-in in %s: got %s, expected one of, or a vector of #{:basis :project :resource}"
                 op search-in)
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

(defn available-file
  [f]
  (try
    (let [ff (io/file f)]
      (when (and (.exists ff) (.canRead ff))
        ff))
    (catch Exception _ nil)))

(defn validate-project-info
  [{:keys [::path ::alias] :as opts}]
  (if-let [project (get opts alias)]
    (if (specs/valid-project? project)
      opts
      (throw
        (ex-info
          (format "Invalid %s in %s. %s"
                  alias path (specs/explain-project project))
          opts)))
    opts))

(defn resource-filename
  [lib]
  (->> ["deps" (namespace lib) (name lib) "deps.edn"]
       (interpose File/separator)
       (apply str)))

(defn- start-sources
  [{:keys [lib ::search-in ::loader]
    :or {search-in [:basis :project :deps-edn-rsrc]}
    :as opts}]
  (let [search-in (if (keyword? search-in) [search-in] search-in)
        nocl (dissoc opts ::loader)]
    (reduce
     (fn [result in]
       (case in
         :basis
         (conj result
               (assoc nocl ::source ['current-basis] ::type :deps-edn)
               (assoc nocl ::source ['initial-basis] ::type :deps-edn)
               (assoc nocl ::source ['current-basis :basis-config :project]
                      ::type :deps-edn))
         :project
         (conj result
               (assoc nocl ::source ['project-deps] ::type :deps-edn)
               (assoc nocl ::source ["deps.edn"] ::type :deps-edn-file))
         :deps-edn-rsrc
         (let [p  (and lib (resource-filename lib))
               rp (and lib (str File/separator p))]
           (cond-> result
             p  (cond->
                 true   (conj (assoc nocl ::source [p] ::type :deps-edn-rsrc))
                 loader (conj (assoc opts ::source [p] ::type :deps-edn-rsrc)))
             rp (cond->
                 true   (conj (assoc nocl ::source [rp] ::type :deps-edn-rsrc))
                 loader (conj (assoc opts ::source [rp] ::type :deps-edn-rsrc)))))))
     [] search-in)))

(defn- print-opts
  [{:keys [lib ::source ::type ::available? ::cause ::verbose ::loader ::alias]
    :or {alias default-alias}
    :as opts}]
  (let [project (get opts alias)
        match? (and project (or (nil? lib) (= (:id project) lib)))
        print-project? (and match? (= :very verbose))
        msg (as-> (StringBuilder.) $
              (cond-> $
                type (.append "> ")
                type (.append (name type))
                type (.append " ")
                source (.append (str source))
                available? (.append " available")
                (not available?) (.append " not available")
                cause (.append " (")
                cause (.append cause)
                cause (.append ")")
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
                    lib (.append (:id project)))
                  (cond-> $
                    available? (.append ", no ")
                    available? (.append (str alias)))))
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

(defn read-source
  [{:keys [::source ::type ::loader ::verbose ::alias]
    :or {alias default-alias}
    :as opts}]
  (let [source (cond-> source
                 (= :deps-edn-file type) (conj 'file 'read-edn)
                 (= :deps-edn-rsrc type) (conj (if loader 'resource-cl 'resource)
                                               'read-edn)
                 (#{:deps-edn-file :deps-edn-rsrc :deps-edn} type)
                 (conj :aliases alias))
        opts (assoc opts ::source source)
        or-reduced (fn [val part opts]
                     (if (nil? val)
                       (let [cause (str (format "nil %s" part)
                                        (when-let [c (::cause opts)]
                                          (str " (" c ")")))]
                         (reduced [nil (assoc opts ::cause cause)]))
                       [val opts]))
        [src opts] (reduce
                    (fn [[src opts] part]
                      (cond
                        (symbol? part)
                        (case part
                          current-basis  (or-reduced (basis/current-basis)
                                                     part opts)
                          initial-basis  (or-reduced (basis/initial-basis)
                                                     part opts)
                          project-deps   (or-reduced (deps/project-deps)
                                                     part opts)
                          file (or-reduced (available-file src)
                                           part
                                           (assoc opts ::path src))
                          (resource resource-cl)
                          (or-reduced (if loader
                                        (io/resource src loader)
                                        (io/resource src))
                                      part (assoc opts ::path src))
                          read-edn (try
                                     (with-open [rdr (io/reader src)]
                                       (or-reduced (deps/read-edn rdr opts)
                                                   part opts))
                                     (catch Throwable t
                                       (when verbose
                                         (println "read-edn failed:"
                                                  (str t)))
                                       (or-reduced nil part
                                                   (assoc opts ::cause (str t)))))
                          (or-reduced part part opts))
                        (keyword? part) (or-reduced (get src part) part opts)
                        :else (or-reduced part part opts)))
                    [nil opts] source)
        opts (cond-> opts
               true (assoc ::available? (some? src))
               src  (assoc alias src))]
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
        count (count opts-list)
        sources (if (> count 1) "sources" "source")]
    (when verbose
      (if (zero? count)
        (if lib
          (print "No matching source found with id" lib)
          (print "No source found with" alias))
        (do (if lib
              (print "Found" count "matching" sources "with id" lib)
              (print "Found" count "matching" sources "with" alias))
            (when (= :very verbose)
              (print " in:")
              (doseq [{:keys [::type ::source]} opts-list]
                (println)
                (print " " (name type) source)))))
      (newline)))
  matching-opts)

(defn matching?
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
       start-sources
       unchunk
       (map read-source)
       (map validate-project-info)
       (some matching?)
       (print-summary opts)
       remove-ns-keys))

(defn print-project
  [opts]
  (read-project (if (::verbose opts) opts (assoc opts ::verbose true))))

(defn searched-deps
  [opts]
  (let [searched (->> opts
                      (valid-opts? "searched-deps")
                      print-header
                      start-sources
                      (map read-source)
                      (map validate-project-info))]
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

(defn info
  ([] (info {}))
  ([lib-or-opts] (project-info lib-or-opts)))
