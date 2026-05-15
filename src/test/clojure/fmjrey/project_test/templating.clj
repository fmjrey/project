(ns fmjrey.project-test.templating
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [fmjrey.project-test.graph :as graph]
            [clojure.java.io :as io]))

(def projects-dir "test-projects")

(defn gen
  [depth]
  (let [{:keys [save-edn?] :as projects} (graph/projects depth projects-dir)
        f (io/file projects-dir "projects.edn")
        _ (io/make-parents f)]
    (when save-edn?
      (with-open [wr (io/writer f)]
        (.write wr (with-out-str (pp/pprint projects)))))
    (graph/gen-imgs projects)))

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
