(ns fmjrey.project-test
  "fmjrey/project testing is based on generating test projects with `deps-new`
  and invoking their code using `fmjrey/invoke`.
  Projects are generated from the same template following a dependency tree
  graph for which SVG images are also generated.

  For better semantics the code follows the following naming conventions:

  - project = general designation of either an application or a library project,
    the former being just a root in the dependency tree.
  - prj, app, lib = the string name of a project, application, or library, using
    _ instead of - due to GraphViz constraints.
  - prjs, apps, libs = sequence of prj/app/lib names.
  - prjm, appm, libm = prj/app/lib data map in the graph.
  - dep(s) = (list of) dependency library name(s).

  A function name containing -> usually indicates some simple format conversion.
  A function name ending with * indicates it applies repetitively the logic of
  the function without *, possibly recursively, or by accepting some sequence
  argument(s) instead of a single value."
  (:require [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.java.process :as cjp]
            [fmjrey.invoke :as ext]))

;;==============================================================================
;; TODO: use generated projects, until then test are failing.

;; Run conditions
(def execution-time [:runtime :devtime])
(def where [:app :lib])
(def query [:app :lib])


(defn project-invoke
  [from s & opts]
  (let [r (ext/invoke {:alias :cli
                       :dir "test-projects/project0"
                       :fn 'test.project0/invoke
                       ;:debug true
                       :preserve-envelope true
                       :args (merge opts {:clojure.exec/err :capture
                                          :fmjrey.project/verbose :very
                                          :from (pr-str from)
                                          :s (pr-str s)})})]
    ;(-> r :err println)
    (-> r :val edn/read-string)))

(defn project-n-lib
  [n]
  (symbol "test" (str "project" n)))

(defn project-n-info
  [n]
  {:id (project-n-lib n)
   :name (str "Test project #" n)
   :license {:id "EPL-2.0"
             :name "Eclipse Public License 2.0"
             :url "https://www.eclipse.org/legal/epl-2.0"}})

#_(deftest project-n-test
  (testing "Scaffolding: test projects have correct number defined"
    (doseq [n (range 4)]
      (is (= n (project-invoke n 'project-n))))))

(deftest project-lib-test
  (testing "Scaffolding: test projects have correct number and lib defined"
    (doseq [n (range 4)]
      (is (= (symbol "test" (str "project" n))
             (project-invoke n 'lib))))))

(deftest lib-info-test
  (testing "Testing project-info with lib argument"
    (doseq [n (range 4)]
      (is (= (if (= n 1)
               nil ; project1 does not have a deps.edn copied  as a resource
               (project-n-info n))
             (project-invoke n 'lib-info))))))

(deftest app-info-test
  (testing "Testing project-info without lib argument"
    (doseq [n (range 4)]
      (is (= (project-n-info 0)
             (project-invoke n 'app-info))))))


(defn test-ns-hook []
  ;;(project-n-test)
  (project-lib-test)
  (lib-info-test)
  (app-info-test))
