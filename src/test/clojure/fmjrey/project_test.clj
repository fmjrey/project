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
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.test :as test :refer :all]
            [clojure.java.io :as io]
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
  [{:keys [type builder dir jar verbose]} invoke-fn from s & opts]
  {:pre [(= :app type)]}
  (let [cmd (cond-> {:alias :cli
                     :dir dir
                     :fn invoke-fn
                     :args (assoc opts :from from :s s)}
              verbose (assoc :debug true
                             :preserve-envelope true)
              verbose (update :args assoc
                              :clojure.exec/err :capture))
        cmd (if (some #{:uberjar} builder)
              (assoc cmd :cp jar)
              cmd)
        r (ext/invoke cmd)
        tag (when verbose (:tag r))
        err (when verbose (:err r))
        val (if verbose (-> r :val edn/read-string) r)]
    (if (= :err tag) err val)))

(defn project-info-with-sets
  [project-info]
  (-> project-info
      (update :builder set)
      (update :deps set)))

;; Atom to accumulate custom data during the run
(def test-results (atom {:events []}))


(def event-types #{:default :pass :fail :error :summary
                   :begin-test-ns :end-test-ns :begin-test-var :end-test-var})
(defn event-handler
  [{:keys [type] :as event}]
  ;;(swap! test-results update :events conj event)
  (swap!
   test-results
   (fn [{:keys [nss vars] :as test-results}]
     (cond-> (update test-results :events conj event)
       (= :begin-test-ns type)
       (update :nss (fnil conj []) (-> event :ns ns-name))
       (= :begin-test-var type)
       (-> (update :vars (fnil conj []) (-> event :var meta :name))
           (assoc-in [:by-ns (last nss) (-> event :var meta :name)]
                     {:pass [] :fail [] :error []}))
       (#{:pass :fail :error} type)
       (update-in [:by-ns (last nss) (last vars) type] conj event)
       (= :summary type)
       ((fn [test-results]
          (with-open [wr (io/writer (io/file tmpl/projects-dir "test-results.edn"))]
            (.write wr (with-out-str (pp/pprint test-results))))
          test-results))))))

;; Override original reporters so we can call them after our own reporting
(defmacro override-reports []
  (cons
   'do
   (mapcat (fn [t]
             (let [tstr (name t)
                   ori (symbol (format "report-%s-ori" tstr))
                   met (symbol (format "report-%s"     tstr))
                   arg (symbol "event")]
               `((defonce ~ori (get-method test/report ~t))
                 (defmethod test/report ~t ~met [~arg]
                   (event-handler ~arg)
                   (~ori ~arg)))))
           event-types)))
(override-reports)

(deftest project-name-test
  (testing "Scaffolding: test projects have correct name defined"
    (doseq [{app :name :as app-info} (mapv tmpl/project-info tmpl/apps)
            :let [prjs (tmpl/prjs-depth-first app)
                  res (project-invoke app-info 'invoke* prjs 'project-name)]]
      (is (= (count prjs) (count res)))
      (is (not= ::reporter :start))
      (doseq [prj prjs]
        (is (= prj (get res [prj 'project-name]))))
      (is (not= ::reporter :end :extra)))))

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
