(ns com.barbiff-test
  (:require [cheshire.core :as cheshire]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [com.biffweb :as biff :refer [test-xtdb-node]]
            [com.barbiff :as main]
            [com.barbiff.app :as app]
            [malli.generator :as mg]
            [rum.core :as rum]
            [xtdb.api :as xt]))

(deftest example-test
  (is (= 4 (+ 2 2))))

(defn get-context [node]
  {:biff.xtdb/node  node
   :biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})

