(ns tasks
  (:require [com.biffweb.tasks :as tasks]
            [clojure.test :as test]))

(defn hello
  "Says 'Hello'"
  []
  (println "Hello"))

(defn test-all
  "Runs all tests"
  []
  (println "Running tests...")
  (require 'com.barbiff-test)
  (require 'com.domain.hardcorefunctionalprojection-test)
  (test/run-all-tests))

(defn test-domain
  "Runs only domain tests"
  []
  (println "Running domain tests...")
  (require 'com.domain.hardcorefunctionalprojection-test)
  (test/run-tests 'com.domain.hardcorefunctionalprojection-test))

;; Tasks should be vars (#'hello instead of hello) so that `clj -M:dev help` can
;; print their docstrings.
(def custom-tasks
  {"hello" #'hello
   "test" #'test-all
   "test-domain" #'test-domain})

(def tasks (merge tasks/tasks custom-tasks))
