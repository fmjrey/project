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
            [fmjrey.invoke :as ext]
            [fmjrey.project-test.graph :as graph]
            [fmjrey.project-test.templating :as tmpl]))

;;==============================================================================
;; TODO: use generated projects, until then test are failing.

;; Run conditions
(def execution-time [:runtime :devtime])
(def where [:app :lib])
(def query [:app :lib])


(defn project-invoke
  [{:keys [type builder dir jar]} invoke-fn from s & opts]
  {:pre [(= :app type)]}
  (let [cmd {:alias :cli
             :dir dir
             :fn invoke-fn
             :debug true
             :preserve-envelope true
             :args (merge opts {:clojure.exec/err :capture
                                :fmjrey.project/verbose :very
                                :from (pr-str from)
                                :s (pr-str s)})}
        cmd (if (some #{:uberjar} builder)
              (assoc cmd :cp jar)
              cmd)
        r (ext/invoke cmd)]
    (case (:tag r)
      :err (-> r :err println)
      :ret (-> r :val edn/read-string))))

(deftest project-name-test
  (testing "Scaffolding: test projects have correct name defined"
    (doseq [{app :name :as app-info} (mapv tmpl/project-info tmpl/apps)
            :let [prjs (tmpl/prjs-depth-first app)
                  res (project-invoke app-info 'invoke* prjs 'project-name)]
            :while res]
      (is (= (count prjs) (count res)))
      (doseq [prj prjs]
        (is (= prj (get res [prj 'project-name])))))))

(deftest project-lib-test
  (testing "Scaffolding: test projects have correct name and lib defined"
    (doseq [{app :name :as app-info} (mapv tmpl/project-info tmpl/apps)
            :let [prjs (tmpl/prjs-depth-first app)
                  res (project-invoke app-info 'invoke* prjs 'lib)]]
      (is (not (nil? res)))
      (when res
        (is (= (count prjs) (count res)))
        (doseq [prj prjs]
          (is (= (tmpl/->project-lib prj) (get res [prj 'lib]))))))))

#_(deftest lib-info-test
  (testing "Testing project-info with lib argument"
    (doseq [n (range 4)]
      (is (= (if (= n 1)
               nil ; project1 does not have a deps.edn copied  as a resource
               (project-info n))
             (project-invoke n 'lib-info))))))

#_(deftest app-info-test
  (testing "Testing project-info without lib argument"
    (doseq [n (range 4)]
      (is (= (project-info 0)
             (project-invoke n 'app-info))))))


(defn test-ns-hook []
  (project-name-test)
  ;;(project-lib-test)
  ;;(lib-info-test)
  ;;(app-info-test)
  ;;
  )
