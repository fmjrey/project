(ns fmjrey.project.build
  (:require [clojure.java.io :as io]
            [fmjrey.project :as project]))

(set! *warn-on-reflection* true)

(def ^:private copy-file
  (requiring-resolve 'clojure.tools.build.api/copy-file))

(def resource-filename project/resource-filename)
(def read-edn project/read-edn)
(def matching? project/matching?)
(def validate-project-info project/validate-project-info)
(def read-project project/read-project)
(def print-project project/print-project)
(def searched-deps project/searched-deps)
(def print-searched-deps project/print-searched-deps)

(defn copy-deps
  [{:keys [lib ::project/verbose ::project/resdir]
    :or {resdir "resources"}
    :as opts}]
  (let [{alias ::project/alias :as opts} (project/valid-opts? "copy-deps" opts)
        [_ _ src] (project/basis-project-deps opts)
        opts (-> opts
                 (assoc ::project/type :file ::project/path src)
                 read-edn
                 validate-project-info)
        dst (->> lib resource-filename (io/file resdir) str)]
    (when-not (matching? opts)
      (throw
        (ex-info
          (format "Provided :lib (%s) does not match project deps.edn :id (%s)"
                  lib (some-> opts alias :id))
        opts)))
    (when verbose
      (println "Copying" src "to" dst))
    (copy-file {:src src :target dst})
    opts))
