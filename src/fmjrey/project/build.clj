(ns fmjrey.project.build
  (:require [clojure.java.io :as io]
            [fmjrey.project :as project]))

(set! *warn-on-reflection* true)

(def ^:private copy-file
  (requiring-resolve 'clojure.tools.build.api/copy-file))

(def resource-filename project/resource-filename)
(def read-project project/read-project)
(def print-project project/print-project)
(def searched-deps project/searched-deps)
(def print-searched-deps project/print-searched-deps)

(defn copy-deps
  [{:keys [lib :fmjrey.project/verbose :fmjrey.project/resdir]
    :or {resdir "resources"}
    :as opts}]
  (let [_ (project/valid-lib? "copy-deps-edn" lib)
        [_ _ src] (project/basis-project-root-deps opts)
        dst (->> lib resource-filename (io/file resdir) str)]
    (when verbose
      (println "Copying" src "to" dst))
    (copy-file {:src src :target dst})
    opts))
