(ns fmjrey.project-test
  (:require [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.java.process :as cjp]))

;; The invoke functions below use clojure.tools.deps.interop/invoke-tool as
;; a source of inspiration. If it could call other projects tools by changing
;; the basis then most of the code below would disappear.
(defn invoke
  [base-strs opts]
  (let [opts-strs (some->> opts seq flatten (map pr-str))
        cmd-strs (concat base-strs opts-strs)
        {:keys [preserve-envelope dir]
         :or {preserve-envelope false dir "test-data/project0"}} opts]
    (apply println "invoking" cmd-strs)
    (let [proc (apply cjp/start {:dir dir} cmd-strs)
          out (cjp/stdout proc)
          err (cjp/stderr proc)]
      (.waitFor proc)
      (if-let [envelope (edn/read-string (slurp out))]
        (if preserve-envelope
          envelope
          (let [{:keys [tag val]} envelope
                parsed-val (edn/read-string val)]
            (if (= :ret tag)
              parsed-val
              (throw (ex-info (:cause parsed-val) (or parsed-val {}))))))
        (let [err-str (slurp err)
              err-msg (if (= "" err-str) "Unknown error invoking Clojure CLI" err-str)]
          (throw (ex-info err-msg
                          {:command (str/join " " cmd-strs)
                           :opts opts})))))))

(defn project-invoke
  [from s & opts]
  (invoke ["clojure" "-X:cli" "invoke" ":from" (pr-str from) ":s" (pr-str s)] opts))

(defn build-invoke
  [from task & opts]
  (invoke ["clojure" "-T:build" (pr-str task)] (assoc opts :dir (str "test-data/project" from))))

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
      (is (= n (project-invoke n 'project-n) (build-invoke n 'number))))))

(deftest project-lib-test
  (testing "Scaffolding: test projects have correct number and lib defined"
    (doseq [n (range 4)]
      (is (= (symbol "test" (str "project" n))
             (project-invoke n 'lib)
             (build-invoke   n 'id))))))

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
