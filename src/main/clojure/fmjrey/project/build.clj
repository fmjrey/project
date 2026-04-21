(ns fmjrey.project.build
  (:require [clojure.java.io :as io]
            [clojure.tools.deps.edn :as deps]
            [fmjrey.project :as project]))

(set! *warn-on-reflection* true)

(def ^:private copy-file
  (requiring-resolve 'clojure.tools.build.api/copy-file))

(def resource-filename project/resource-filename)
(def read-source project/read-source)
(def matching? project/matching?)
(def validate-project-info project/validate-project-info)
(defn info
  ([] (info {}))
  ([lib-or-opts] (project/project-info lib-or-opts)))
(def valid-opts? project/valid-opts?)
(def read-project project/read-project)
(def print-project project/print-project)
(def searched-deps project/searched-deps)
(def print-searched-deps project/print-searched-deps)

(defn copy-deps
  [{:keys [lib ::project/verbose ::project/resdir]
    :or {resdir "resources"}
    :as opts}]
  (let [src (or (deps/project-deps-path)
                (throw (ex-info "tools.deps.edn/project-deps-path returned nil"
                                opts)))
        opts (-> (valid-opts? "copy-deps" opts)
                 (assoc ::project/type   :deps-edn-file
                        ::project/source [src])
                 read-source
                 validate-project-info
                 matching?
                 (or (throw
                       (ex-info
                         (format ":lib (%s) does not match :id in %s"
                               lib src)
                       opts))))
        id (some->> opts ::project/alias (get opts) :id)
        lib (or lib id (throw (ex-info (format "No :id found in %s" src) opts)))
        dst (->> lib resource-filename (io/file resdir) str)]
    (when verbose
      (println "Copying" src "to" dst))
    (copy-file {:src src :target dst})
    opts))
