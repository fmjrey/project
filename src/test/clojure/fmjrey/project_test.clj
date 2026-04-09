(ns fmjrey.project-test
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.test :refer :all]
            [clojure.java.process :as cjp]))

(defn test-project-invoke
  [from s & opts]
  (let [base-strs ["clojure" "-X:cli" "invoke" ":from" (pr-str from) ":s" (pr-str s)]
        opts-strs (some->> opts seq flatten (map pr-str))
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

(deftest project-n-test
  (testing "Testing test projects have correct number defined"
    (doseq [n (range 4)]
      (is (= n (test-project-invoke n 'project-n))))))

(deftest lib-test
  (testing "Testing test projects have correct lib defined"
    (doseq [n (range 4)]
      (is (= (symbol "test" (str "project" n))
             (test-project-invoke n 'lib))))))

(deftest lib-info-test
  (testing "Testing project-info with lib argument"
    (doseq [n (range 4)]
      (is (= (if (= n 1) nil (project-n-info n))
             (test-project-invoke n 'lib-info))))))

(deftest app-info-test
  (testing "Testing project-info without lib argument"
    (doseq [n (range 4)]
      (is (= (project-n-info 0)
             (test-project-invoke n 'app-info))))))
