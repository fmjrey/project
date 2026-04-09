(ns test.project0
  (:require [fmjrey.project :as project]
            [test.project1 :as p1]
            [test.project2 :as p2]
            [test.project3 :as p3]))

(def read-project project/read-project)
(def searched-deps project/searched-deps)

(def project-n 0)
(def lib (symbol "test" (str "project" project-n)))
(def lib-info (project/project-info lib))
(def app-info (project/project-info))

(defn envelope?
  [val]
  (and (map? val) (contains? val :tag) (contains? val :val)))

(defn envelope
  [tag s val]
  (let [e (if (envelope? val)
            val
            {:tag tag
             :ran s
             :val (binding [*print-namespace-maps* false] (pr-str val))})]
    ;(println (format "project%d/envelope: envelope? %s" project-n (str (envelope? val))))
    e))

(defn resolved?
  [ns-str]
  (if (find-ns (symbol ns-str))
    ns-str
    (throw (ex-info (format "project%d: can't resolve %s" project-n ns-str) {}))))

(defn invoke
  [{:keys [from s]
    :or {from project-n s 'lib-info}
    :as opts}]
  (let [from (-> (case from
                   (0 "0" 'p0 "p0" 'project0 "project0") 0
                   (1 "1" 'p1 "p1" 'project1 "project1") 1
                   (2 "2" 'p2 "p2" 'project2 "project2") 2
                   (3 "3" 'p3 "p3" 'project3 "project3") 3
                   (throw (ex-info
                           (format "test.project%d: invalid :from %s"
                                   project-n (str from))
                           opts))))
        ns-str (resolved? (str "test.project" from))
        from-here? (= from project-n)
        s (if (namespace s) s (symbol ns-str (name s)))
        envelope (let [run (if from-here? s (symbol ns-str "invoke"))]
                   (try
                     ;(println (format "project%d/invoke: :from=%s run=%s" project-n (str from) (str run)))
                     (let [v (-> run resolve var-get)
                           ;_ (println (format "project%d/invoke: v fn? %s type %s" project-n (str (fn? v)) (str (type v))))
                           r (if (fn? v) (v opts) v)]
                       (envelope :ret run r))
                     (catch Throwable t
                       (envelope :err run (Throwable->map t)))))]
    (when from-here? (binding [*print-namespace-maps* false] (prn envelope)))
    envelope))

(comment
  (invoke {})
  (invoke {:s 'lib})
  )
