(ns com.domain.workout-filtering-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.barbiff.domain.workout-filtering :as wf]))

;; Test data

(def complete-set
  {:prescribed-weight 100 :prescribed-reps 8
   :actual-weight 100 :actual-reps 8})

(def incomplete-set
  {:prescribed-weight 100 :prescribed-reps 8
   :actual-weight nil :actual-reps nil})

(def partially-complete-set-1
  {:prescribed-weight 100 :prescribed-reps 8
   :actual-weight 100 :actual-reps nil})

(def partially-complete-set-2
  {:prescribed-weight 100 :prescribed-reps 8
   :actual-weight nil :actual-reps 8})

(def complete-exercise
  {:name "Bench Press"
   :sets [complete-set complete-set complete-set]})

(def incomplete-exercise
  {:name "Squat"
   :sets [complete-set incomplete-set incomplete-set]})

(def complete-workout
  {:name "Upper" :day :monday
   :exercises [complete-exercise]})

(def incomplete-workout
  {:name "Lower" :day :wednesday
   :exercises [complete-exercise incomplete-exercise]})

(def empty-workout
  {:name "Empty" :day :friday
   :exercises []})

(def sample-plan
  {:microcycles
   [{:workouts [complete-workout incomplete-workout]}
    {:workouts [incomplete-workout]}]})

(def all-complete-plan
  {:microcycles
   [{:workouts [complete-workout complete-workout]}]})

(def events-with-active-workout
  [{:type :microcycle-started}
   {:type :workout-started :name "Upper" :day :monday}
   {:type :set-logged :exercise "Bench Press" :weight 100 :reps 8}])

(def events-no-active-workout
  [{:type :microcycle-started}
   {:type :workout-started :name "Upper" :day :monday}
   {:type :set-logged :exercise "Bench Press" :weight 100 :reps 8}
   {:type :workout-completed}])

(def empty-events [])

;; Tests for set-complete?

(deftest set-complete?-test
  (testing "returns true when set has both actual weight and reps"
    (is (wf/set-complete? complete-set)))

  (testing "returns false when set has no actual values"
    (is (not (wf/set-complete? incomplete-set))))

  (testing "returns false when only weight is present"
    (is (not (wf/set-complete? partially-complete-set-1))))

  (testing "returns false when only reps is present"
    (is (not (wf/set-complete? partially-complete-set-2))))

  (testing "returns false for empty map"
    (is (not (wf/set-complete? {}))))

  (testing "returns false when values are explicitly nil"
    (is (not (wf/set-complete? {:actual-weight nil :actual-reps nil})))))

;; Tests for set-incomplete?

(deftest set-incomplete?-test
  (testing "is inverse of set-complete?"
    (is (= (not (wf/set-complete? complete-set))
           (wf/set-incomplete? complete-set)))
    (is (= (not (wf/set-complete? incomplete-set))
           (wf/set-incomplete? incomplete-set)))))

;; Tests for workout-has-incomplete-sets?

(deftest workout-has-incomplete-sets?-test
  (testing "returns false when all sets are complete"
    (is (not (wf/workout-has-incomplete-sets? complete-workout))))

  (testing "returns true when any exercise has incomplete sets"
    (is (wf/workout-has-incomplete-sets? incomplete-workout)))

  (testing "returns false for workout with no exercises"
    (is (not (wf/workout-has-incomplete-sets? empty-workout))))

  (testing "returns false for workout with empty sets"
    (is (not (wf/workout-has-incomplete-sets?
              {:name "Test" :exercises [{:name "Ex1" :sets []}]})))))

;; Tests for active-workout-name

(deftest active-workout-name-test
  (testing "extracts name from most recent workout-started event"
    (is (= "Upper" (wf/active-workout-name events-with-active-workout))))

  (testing "returns nil when no workout-started events"
    (is (nil? (wf/active-workout-name [{:type :microcycle-started}]))))

  (testing "returns nil for empty events"
    (is (nil? (wf/active-workout-name []))))

  (testing "returns most recent when multiple workout-started events"
    (let [events [{:type :workout-started :name "First"}
                  {:type :workout-completed}
                  {:type :workout-started :name "Second"}]]
      (is (= "Second" (wf/active-workout-name events))))))

;; Tests for find-current-workout

(deftest find-current-workout-test
  (testing "returns active workout when events indicate one is in progress"
    (let [result (wf/find-current-workout sample-plan events-with-active-workout)]
      (is (some? result))
      (is (= 0 (:microcycle-idx result)))
      (is (= "Upper" (get-in result [:workout :name])))))

  (testing "returns first incomplete workout when no active workout"
    ;; Note: Upper is complete, so it should skip to Lower
    (let [result (wf/find-current-workout sample-plan events-no-active-workout)]
      (is (some? result))
      (is (= 0 (:microcycle-idx result)))
      (is (= "Lower" (get-in result [:workout :name])))))

  (testing "returns nil when all workouts are complete"
    (is (nil? (wf/find-current-workout all-complete-plan empty-events))))

  (testing "finds incomplete workout in second microcycle"
    (let [plan-with-complete-first {:microcycles
                                    [{:workouts [complete-workout]}
                                     {:workouts [incomplete-workout]}]}
          result (wf/find-current-workout plan-with-complete-first empty-events)]
      (is (= 1 (:microcycle-idx result)))
      (is (= "Lower" (get-in result [:workout :name])))))

  (testing "prefers active workout over incomplete workout"
    (let [events [{:type :microcycle-started}
                  {:type :workout-started :name "Lower"}]
          result (wf/find-current-workout sample-plan events)]
      ;; Even though Upper is first and incomplete, Lower is active
      (is (= "Lower" (get-in result [:workout :name])))))

  (testing "handles empty plan"
    (is (nil? (wf/find-current-workout {:microcycles []} empty-events))))

  (testing "handles plan with empty microcycles"
    (is (nil? (wf/find-current-workout {:microcycles [{:workouts []}]} empty-events)))))