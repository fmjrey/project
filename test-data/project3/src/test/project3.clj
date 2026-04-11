(ns test.project3
  (:require [fmjrey.project :as project]))

;; This number and the one in this namespace must match and be the only
;; textual difference with the corresponding file in other projects
(def project-n 3)
;; This lib symbol is expected to be defined in all test projects
(def lib (symbol "test" (str "project" project-n)))

;; Storing the result of calling the project-info macro so it can be
;; inspected by test cases
(def lib-info (project/project-info lib))
(def app-info (project/project-info))

;; Make some fmjrey.project functions available for testing
(def read-project project/read-project)
(def searched-deps project/searched-deps)

;; The following enables calling some functions via the clojure CLI.
;; Because project0 declares the other projects as dependencies it is
;; able to delegate to them the execution so that behavior can be tested
;; by different part of the code (project or dependency) and under
;; different circumstances (jar deps, source deps, uberjar, etc).
;; In the code below "from" denotes where a symbol "s" should be evaluated.

(defn envelope?
  [val]
  (and (map? val) (contains? val :tag) (contains? val :val)))

(defn envelope
  [tag ran val]
  (println (format "; enveloping %s %s %s" tag ran val))
  (let [envelope (if (envelope? val)
                   val
                   {:tag tag
                    :ran ran
                    :val (binding [*print-namespace-maps* false] (pr-str val))})]
    envelope))

(defn resolve-from
  [from symbol-or-string]
  (println (format ";resolve-from %s (%s) %s (%s)" (str from) (type from) (str symbol-or-string) (type symbol-or-string)))
  (let [from (-> (case from
                   (0 "0" 'p0 "p0" 'project0 "project0") 0
                   (1 "1" 'p1 "p1" 'project1 "project1") 1
                   (2 "2" 'p2 "p2" 'project2 "project2") 2
                   (3 "3" 'p3 "p3" 'project3 "project3") 3
                   (throw (ex-info
                           (format "test.project%d: invalid from %s"
                                   project-n (str from))
                           {}))))
        from-here? (= from project-n)
        s (if (string? symbol-or-string)
            (symbol symbol-or-string)
            symbol-or-string)
        here-ns-str (namespace ::here)
        from-ns-str (if from-here? here-ns-str (str "test.project" from))
        s-ns-str (or (namespace s) from-ns-str)
        s (if (qualified-symbol? s) s (symbol s-ns-str (name s)))
        _ (println ";  s=" s)
        ;;rrs (if from-here? s (symbol from-ns-str "lib"))
        run (if from-here? s (symbol from-ns-str "invoke"))
        _ (println ";  run=" run)
        resolved (requiring-resolve run)]
    (when-not resolved
      (throw (ex-info (format "project%d: can't resolve %s" project-n run) {})))
    (println ";  resolved=" resolved (some-> resolved type))
    [resolved from run]))

(defn invoke
  [{:keys [from s]
    :or {from project-n s 'lib-info}
    :as opts}]
  (println (format ";project%d/invoke: :from=%s s=%s" project-n (str from) (str s)))
  (let [[resolved from run] (resolve-from from s)
        ;;ns-str (resolved? (str "test.project" from))
        ;;from-here? (= from project-n)
        ;;s (if (namespace s) s (symbol ns-str (name s)))
        envelope (try
                   (let [v (var-get resolved)
                         _ (println (format ";project%d/invoke: v=%s (%s)" project-n (str v) (str (type v))))
                         r (if (fn? v) (v opts) v)]
                     (envelope :ret run r))
                   (catch Throwable t
                     (envelope :err run (Throwable->map t))))]
    (when (= from project-n)
      (binding [*print-namespace-maps* false] (prn envelope)))
    envelope))

(comment
  (invoke {})
  (invoke {:s 'lib})
  )
