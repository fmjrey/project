(ns fmjrey.project.specs
  (:require [clojure.spec.alpha :as s]))

;; Options
(s/def ::lib (s/and symbol? qualified-ident?))
(s/def ::alias keyword?)
(def search-in-keyword? #{:basis :project :resource})
(s/def ::search-in
  (s/or ::keyword search-in-keyword?
        ::vector (s/coll-of search-in-keyword?
                            :kind vector?
                            :min-count 1
                            :max-count 3
                            :distinct true
                            :into [])))

;; Project data
(s/def ::id symbol?)
(s/def ::description string?)
(s/def ::url string?)
(s/def ::version-string string?)
(s/def :license/id string?)
(s/def :license/name string?)
(s/def :license/url string?)
(s/def :version/major pos-int?)
(s/def :version/minor pos-int?)
(s/def :version/patch pos-int?)
(s/def :source/url string?)
(s/def :source/rev string?)

(s/def ::license1 (s/keys :opt [:license/id
                                :license/name
                                :license/url]))
(s/def ::license (s/or ::license1 (s/coll-of ::license1)))

(s/def ::version (s/keys :opt [:version/major
                               :version/minor
                               :version/patch]))

(s/def ::source (s/keys :opt [:source/url :source/rev]))

(s/def ::project (s/nilable (s/keys
                              :req-un [::id]
                              :opt-un [::description ::url
                                       ::version ::license ::source
                                       ::version-string])))

(defn valid-lib?
  [lib]
  (s/valid? ::lib lib))

(defn valid-alias?
  [alias]
  (s/valid? ::alias alias))

(defn valid-search-in?
  [search-in]
  (s/valid? ::search-in search-in))

(defn valid-project?
  "True if given map is a valid :project entry according to the specs"
  [project-map]
  (s/valid? ::project project-map))

(defn explain-project
  "If a spec is invalid, return a message explaining why, suitable
  for an error message"
  [project-map]
  (let [err-data (s/explain-data ::project project-map)]
    (if (nil? err-data)
      "Failed spec, reason unknown"
      (let [problems (->> (::s/problems err-data)
                          (sort-by #(- (count (:in %))))
                          (sort-by #(- (count (:path %)))))
            {:keys [pred val reason in]} (first problems)]
        (str "Found: " (pr-str val) ", expected: " (if reason reason (s/abbrev pred)) ", in: " (pr-str in))))))
