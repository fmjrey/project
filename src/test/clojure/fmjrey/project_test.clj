(ns fmjrey.project-test
  (:require [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.java.process :as cjp]
            [fmjrey.invoke :as ext]))

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
