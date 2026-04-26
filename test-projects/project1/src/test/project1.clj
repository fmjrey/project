(ns test.project1
  (:require [clojure.string :as str]
            [fmjrey.project :as project]))

;; This number and the one in this namespace must match and be the only
;; textual difference with the corresponding file in other projects
(def project-n 1)
;; This lib symbol is expected to be defined in all test projects
(def lib (symbol "test" (str "project" project-n)))

;; Storing the result of calling the project-info macro so it can be
;; inspected by test cases
(def lib-info (project/project-info lib))
(def app-info (project/info))

;; Make some fmjrey.project functions available for testing
(def read-project project/read-project)
(def searched-deps project/searched-deps)

;; Because project0 declares the other projects as dependencies it is
;; able to delegate to them the execution so that behavior can be tested
;; by different part of the code (project or dependency) and under
;; different circumstances (jar deps, source deps, uberjar, etc).
;; In the code below "from" denotes where a symbol "s" should be evaluated.

(defonce ^:private nl (System/getProperty "line.separator"))

(defn- printerrln
  "println to *err*"
  [& msgs]
  (binding [*out* *err*
            *print-readably* nil]
    (pr (str (str/join " " msgs) nl))
    (flush)))

(defn resolve-from
  [from symbol-or-string]
  (printerrln (format "resolve-from %s (%s) %s (%s)" (str from) (type from) (str symbol-or-string) (type symbol-or-string)))
  (let [from (-> (case from
                   (0 "0" p0 "p0" project0 "project0") 0
                   (1 "1" p1 "p1" project1 "project1") 1
                   (2 "2" p2 "p2" project2 "project2") 2
                   (3 "3" p3 "p3" project3 "project3") 3
                   (throw (ex-info
                           (format "test.project%d: invalid from %s"
                                   project-n (str from))
                           {}))))
        from-here? (= from project-n)
        s (if (string? symbol-or-string)
            (symbol symbol-or-string)
            symbol-or-string)
        from-ns-str (str "test.project" (if from-here? project-n from))
        s-ns-str (or (namespace s) from-ns-str)
        s (if (qualified-symbol? s) s (symbol s-ns-str (name s)))
        _ (printerrln "  s=" s)
        run (if from-here? s (symbol from-ns-str "invoke"))
        _ (printerrln "  run=" run)
        resolved (requiring-resolve run)]
    (when-not resolved
      (throw (ex-info (format "project%d: can't resolve %s" project-n run) {})))
    (printerrln "  resolved=" resolved (some-> resolved type))
    resolved))

(defn invoke
  [{:keys [from s]
    :or {from project-n s 'lib-info}
    :as opts}]
  (println (format ";project%d/invoke: :from=%s s=%s" project-n (str from) (str s)))
  (let [resolved (resolve-from from s)
        v (var-get resolved)
        _ (printerrln (format "project%d/invoke: v=%s (%s)" project-n (str v) (str (type v))))]
    (if (fn? v) (v opts) v)))
