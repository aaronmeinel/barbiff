(ns com.domain.workout-filtering-property-test
  (:require [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [com.barbiff.domain.workout-filtering :as wf]))

;; Generators

(def gen-weight (gen/choose 40 200))
(def gen-reps (gen/choose 1 20))

(def gen-complete-set
  (gen/let [pw gen-weight pr gen-reps
            aw gen-weight ar gen-reps]
    {:prescribed-weight pw :prescribed-reps pr
     :actual-weight aw :actual-reps ar}))

(def gen-incomplete-set
  (gen/frequency
   [[1 (gen/return {:prescribed-weight 100 :prescribed-reps 8
                    :actual-weight nil :actual-reps nil})]
    [1 (gen/let [w gen-weight]
         {:prescribed-weight 100 :prescribed-reps 8
          :actual-weight w :actual-reps nil})]
    [1 (gen/let [r gen-reps]
         {:prescribed-weight 100 :prescribed-reps 8
          :actual-weight nil :actual-reps r})]]))

(def gen-set
  (gen/one-of [gen-complete-set gen-incomplete-set]))

(def gen-exercise-name
  (gen/elements ["Bench Press" "Squat" "Deadlift" "Barbell Row" "Overhead Press"]))

(def gen-exercise
  (gen/let [name gen-exercise-name
            sets (gen/vector gen-set 1 5)]
    {:name name :sets sets}))

(def gen-workout-name
  (gen/elements ["Upper" "Lower" "Push" "Pull" "Legs"]))

(def gen-day
  (gen/elements [:monday :tuesday :wednesday :thursday :friday :saturday :sunday]))

(def gen-workout
  (gen/let [name gen-workout-name
            day gen-day
            exercises (gen/vector gen-exercise 1 4)]
    {:name name :day day :exercises exercises}))

(def gen-microcycle
  (gen/let [workouts (gen/vector gen-workout 1 3)]
    {:workouts workouts}))

(def gen-plan
  (gen/let [microcycles (gen/vector gen-microcycle 1 3)]
    {:microcycles microcycles}))

(def gen-workout-started-event
  (gen/let [name gen-workout-name
            day gen-day]
    {:type :workout-started :name name :day day}))

(def gen-projection-events
  (gen/let [has-microcycle? gen/boolean
            has-workout? gen/boolean
            workout-name (if has-workout? gen-workout-name (gen/return nil))
            workout-day (if has-workout? gen-day (gen/return nil))]
    (cond-> []
      has-microcycle? (conj {:type :microcycle-started})
      has-workout? (conj {:type :workout-started :name workout-name :day workout-day}))))

;; Properties

(defspec set-complete-behaves-correctly 100
  (prop/for-all [set-data gen-complete-set]
                ;; A complete set should always return true
                (wf/set-complete? set-data)))

(defspec set-incomplete-is-inverse-of-complete 100
  (prop/for-all [set-data gen-set]
                (= (wf/set-incomplete? set-data)
                   (not (wf/set-complete? set-data)))))

(defspec incomplete-set-always-incomplete 100
  (prop/for-all [set-data gen-incomplete-set]
                (wf/set-incomplete? set-data)))

(defspec complete-set-never-incomplete 100
  (prop/for-all [set-data gen-complete-set]
                (not (wf/set-incomplete? set-data))))

(defspec workout-with-incomplete-exercise-is-incomplete 100
  (prop/for-all [complete-ex gen-exercise
                 incomplete-set gen-incomplete-set
                 name gen-exercise-name]
                (let [incomplete-ex (assoc complete-ex
                                           :name name
                                           :sets [incomplete-set])
                      workout {:name "Test" :day :monday
                               :exercises [complete-ex incomplete-ex]}]
                  (wf/workout-has-incomplete-sets? workout))))

(defspec active-workout-name-returns-string-or-nil 100
  (prop/for-all [events gen-projection-events]
                (let [result (wf/active-workout-name events)]
                  (or (nil? result) (string? result)))))

(defspec active-workout-name-consistent-with-events 100
  (prop/for-all [events gen-projection-events]
                (let [result (wf/active-workout-name events)
                      workout-events (filter #(= :workout-started (:type %)) events)]
                  (if (empty? workout-events)
                    (nil? result)
                    (= result (:name (last workout-events)))))))

(defspec find-current-workout-returns-map-or-nil 100
  (prop/for-all [plan gen-plan
                 events gen-projection-events]
                (let [result (wf/find-current-workout plan events)]
                  (or (nil? result)
                      (and (map? result)
                           (contains? result :microcycle-idx)
                           (contains? result :workout))))))

(defspec find-current-workout-idx-in-bounds 100
  (prop/for-all [plan gen-plan
                 events gen-projection-events]
                (let [result (wf/find-current-workout plan events)]
                  (if result
                    (and (>= (:microcycle-idx result) 0)
                         (< (:microcycle-idx result) (count (:microcycles plan))))
                    true))))  ; nil is valid

(defspec find-current-workout-returns-valid-workout 100
  (prop/for-all [plan gen-plan
                 events gen-projection-events]
                (let [result (wf/find-current-workout plan events)]
                  (if result
                    (let [workout (:workout result)]
                      (and (map? workout)
                           (string? (:name workout))
                           (keyword? (:day workout))
                           (vector? (:exercises workout))))
                    true))))  ; nil is valid