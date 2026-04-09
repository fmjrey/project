(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [fmjrey.project.build :as project]))

(def project-n 0)
(def lib (symbol "test" (str "project" project-n)))
(def version (str "0.1." project-n))
(def class-dir "target/classes")
(def jar-file (format "%s-%s.jar" (name lib) version))


;; delay to defer side effects (artifact downloads)
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"})
  (b/delete {:path ".cpcache"}))


(defn- pom-template [version]
  [[:description (str "Test project #" project-n)]
   [:url (str "https://github.com/test/project" project-n)]
   [:licenses
    [:license
     [:name "Eclipse Public License 1.0"]
     [:url "http://www.eclipse.org/legal/epl-v10.html"]]]
   [:developers
    [:developer
     [:name "Francois"]]]
   [:scm
    [:url (str "https://github.com/test/project" project-n)]
    [:connection (str "scm:git:https://github.com/test/project" project-n ".git")]
    [:developerConnection (str "scm:git:ssh:git@github.com:test/project" project-n ".git")]
    [:tag (str "v" version)]]])


(defn copy-deps
  [_]
  (project/copy-deps {:lib lib}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :pom-data  (pom-template version)})
  (copy-deps {})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file})
  (clean {}))
