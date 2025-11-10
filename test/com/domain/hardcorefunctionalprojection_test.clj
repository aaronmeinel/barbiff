(ns com.domain.hardcorefunctionalprojection-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.barbiff.domain.hardcorefunctionalprojection :as proj]))

;; Test data

(def complete-microcycle-events
  [{:type :microcycle-started}
   {:type :workout-started :name "Upper" :day :monday}
   {:type :set-logged :exercise "Bench Press" :weight 100 :reps 10}
   {:type :set-logged :exercise "Bench Press" :weight 100 :reps 10}
   {:type :set-logged :exercise "Barbell Row" :weight 80 :reps 10}
   {:type :workout-completed}
   {:type :workout-started :name "Lower" :day :wednesday}
   {:type :set-logged :exercise "Squat" :weight 150 :reps 5}
   {:type :workout-completed}
   {:type :microcycle-completed}])

(def active-workout-events
  [{:type :microcycle-started}
   {:type :workout-started :name "Upper" :day :monday}
   {:type :set-logged :exercise "Bench Press" :weight 105 :reps 11}])

(def multiple-microcycles-events
  [{:type :microcycle-started}
   {:type :workout-started :name "Week1" :day :monday}
   {:type :set-logged :exercise "Squat" :weight 100 :reps 10}
   {:type :workout-completed}
   {:type :microcycle-completed}
   {:type :microcycle-started}
   {:type :workout-started :name "Week2" :day :monday}
   {:type :set-logged :exercise "Squat" :weight 105 :reps 10}
   {:type :workout-completed}
   {:type :microcycle-completed}])

;; Tests for utility functions

(deftest take-until-test
  (testing "take-until includes the matching element"
    (is (= [1 2 3] (proj/take-until #(= 3 %) [1 2 3 4 5]))))

  (testing "take-until takes everything if no match"
    (is (= [1 2 3] (proj/take-until #(= 99 %) [1 2 3]))))

  (testing "take-until works on first element"
    (is (= [1] (proj/take-until #(= 1 %) [1 2 3]))))

  (testing "take-until handles empty collection"
    (is (empty? (proj/take-until #(= 1 %) [])))))

(deftest find-next-start-test
  (testing "finds first matching event"
    (let [result (proj/find-next-start complete-microcycle-events :workout-started)]
      (is (= :workout-started (:type (first result))))
      (is (= "Upper" (:name (first result))))))

  (testing "returns nil when no match"
    (is (nil? (proj/find-next-start [{:type :other}] :workout-started))))

  (testing "skips non-matching events"
    (let [events [{:type :other} {:type :workout-started :name "Test"}]
          result (proj/find-next-start events :workout-started)]
      (is (= "Test" (:name (first result)))))))

(deftest extract-section-test
  (testing "extracts section up to and including end marker"
    (let [events [{:type :workout-started}
                  {:type :set-logged}
                  {:type :workout-completed}
                  {:type :other}]
          section (proj/extract-section events :workout-completed)]
      (is (= 3 (count section)))
      (is (= :workout-completed (:type (last section))))))

  (testing "takes everything if no end marker"
    (let [events [{:type :workout-started}
                  {:type :set-logged}]
          section (proj/extract-section events :workout-completed)]
      (is (= 2 (count section))))))

(deftest sections-between-test
  (testing "extracts multiple sections"
    (let [sections (proj/sections-between complete-microcycle-events
                                          :workout-started
                                          :workout-completed)]
      (is (= 2 (count sections)))
      (is (= "Upper" (:name (first (first sections)))))
      (is (= "Lower" (:name (first (second sections)))))))

  (testing "handles incomplete sections"
    (let [sections (proj/sections-between active-workout-events
                                          :workout-started
                                          :workout-completed)]
      (is (= 1 (count sections)))
      (is (= "Upper" (:name (first (first sections)))))))

  (testing "returns empty when no sections found"
    (is (empty? (proj/sections-between [{:type :other}]
                                       :workout-started
                                       :workout-completed)))))

;; Tests for domain transformations

(deftest ->set-test
  (testing "transforms set event to domain model"
    (let [event {:type :set-logged :exercise "Bench" :weight 100 :reps 10}
          result (proj/->set event)]
      (is (nil? (:prescribed-weight result)))
      (is (nil? (:prescribed-reps result)))
      (is (= 100 (:actual-weight result)))
      (is (= 10 (:actual-reps result))))))

(deftest ->exercise-test
  (testing "transforms exercise name and sets"
    (let [set-events [{:weight 100 :reps 10}
                      {:weight 100 :reps 10}]
          result (proj/->exercise ["Bench Press" set-events])]
      (is (= "Bench Press" (:name result)))
      (is (= 2 (count (:sets result)))))))

(deftest ->workout-test
  (testing "transforms workout section to domain model"
    (let [events [{:type :workout-started :name "Upper" :day :monday}
                  {:type :set-logged :exercise "Bench Press" :weight 100 :reps 10}
                  {:type :set-logged :exercise "Bench Press" :weight 100 :reps 10}
                  {:type :set-logged :exercise "Row" :weight 80 :reps 10}]
          result (proj/->workout events)]
      (is (= "Upper" (:name result)))
      (is (= :monday (:day result)))
      (is (= 2 (count (:exercises result))))
      (is (= "Bench Press" (:name (first (:exercises result)))))
      (is (= 2 (count (:sets (first (:exercises result))))))))

  (testing "groups sets by exercise"
    (let [events [{:type :workout-started :name "Test" :day :monday}
                  {:type :set-logged :exercise "Squat" :weight 100 :reps 5}
                  {:type :set-logged :exercise "Bench" :weight 80 :reps 8}
                  {:type :set-logged :exercise "Squat" :weight 100 :reps 5}]
          result (proj/->workout events)
          squat-exercise (first (filter #(= "Squat" (:name %)) (:exercises result)))]
      (is (= 2 (count (:sets squat-exercise)))))))

(deftest ->microcycle-test
  (testing "transforms microcycle section to domain model"
    (let [result (proj/->microcycle complete-microcycle-events)]
      (is (= 2 (count (:workouts result))))
      (is (= "Upper" (:name (first (:workouts result)))))
      (is (= "Lower" (:name (second (:workouts result))))))))

;; Integration tests

(deftest build-state-test
  (testing "builds complete state from complete events"
    (let [result (proj/build-state complete-microcycle-events)]
      (is (map? result))
      (is (contains? result :microcycles))
      (is (= 1 (count (:microcycles result))))
      (is (= 2 (count (:workouts (first (:microcycles result))))))
      (is (= "Upper" (:name (first (:workouts (first (:microcycles result)))))))
      (is (= 2 (count (:exercises (first (:workouts (first (:microcycles result))))))))))

  (testing "handles active incomplete workout"
    (let [result (proj/build-state active-workout-events)]
      (is (= 1 (count (:microcycles result))))
      (is (= 1 (count (:workouts (first (:microcycles result))))))
      (is (= "Upper" (:name (first (:workouts (first (:microcycles result)))))))))

  (testing "handles multiple microcycles"
    (let [result (proj/build-state multiple-microcycles-events)]
      (is (= 2 (count (:microcycles result))))
      (is (= "Week1" (:name (first (:workouts (first (:microcycles result)))))))
      (is (= "Week2" (:name (first (:workouts (second (:microcycles result)))))))))

  (testing "handles empty event log"
    (let [result (proj/build-state [])]
      (is (= {:microcycles []} result))))

  (testing "exercise sets are in correct order"
    (let [result (proj/build-state complete-microcycle-events)
          first-workout (first (:workouts (first (:microcycles result))))
          bench-press (first (filter #(= "Bench Press" (:name %)) (:exercises first-workout)))]
      (is (= 2 (count (:sets bench-press))))
      (is (every? #(= 100 (:actual-weight %)) (:sets bench-press))))))

;; Tests for plan + progress merging

(def sample-plan
  {:microcycles
   [{:workouts
     [{:name "Upper"
       :day :monday
       :exercises
       [{:name "Bench Press"
         :sets [{:prescribed-reps 8 :prescribed-weight 100}
                {:prescribed-reps 8 :prescribed-weight 100}]}
        {:name "Barbell Row"
         :sets [{:prescribed-reps 8 :prescribed-weight 80}]}]}
      {:name "Lower"
       :day :wednesday
       :exercises
       [{:name "Squat"
         :sets [{:prescribed-reps 5 :prescribed-weight 140}
                {:prescribed-reps 5 :prescribed-weight 140}]}]}]}]})

(deftest merge-set-test
  (testing "merges planned and actual set data"
    (let [planned {:prescribed-weight 100 :prescribed-reps 8}
          actual {:actual-weight 105 :actual-reps 10}
          result (proj/merge-set planned actual)]
      (is (= 100 (:prescribed-weight result)))
      (is (= 8 (:prescribed-reps result)))
      (is (= 105 (:actual-weight result)))
      (is (= 10 (:actual-reps result))))))

(deftest merge-sets-test
  (testing "merges sets with equal counts"
    (let [planned [{:prescribed-weight 100 :prescribed-reps 8}
                   {:prescribed-weight 100 :prescribed-reps 8}]
          actual [{:actual-weight 105 :actual-reps 10}
                  {:actual-weight 100 :actual-reps 8}]
          result (proj/merge-sets planned actual)]
      (is (= 2 (count result)))
      (is (= 105 (:actual-weight (first result))))
      (is (= 100 (:prescribed-weight (first result))))))

  (testing "handles more actual sets than planned (extra sets)"
    (let [planned [{:prescribed-weight 100 :prescribed-reps 8}]
          actual [{:actual-weight 105 :actual-reps 10}
                  {:actual-weight 100 :actual-reps 8}
                  {:actual-weight 95 :actual-reps 12}]
          result (proj/merge-sets planned actual)]
      (is (= 3 (count result)))
      ;; First set: planned + actual
      (is (= 100 (:prescribed-weight (first result))))
      (is (= 105 (:actual-weight (first result))))
      ;; Extra sets: no planned data, only actual
      (is (nil? (:prescribed-weight (second result))))
      (is (= 100 (:actual-weight (second result))))
      (is (nil? (:prescribed-weight (nth result 2))))
      (is (= 95 (:actual-weight (nth result 2))))))

  (testing "handles more planned sets than actual (incomplete workout)"
    (let [planned [{:prescribed-weight 100 :prescribed-reps 8}
                   {:prescribed-weight 100 :prescribed-reps 8}
                   {:prescribed-weight 100 :prescribed-reps 8}]
          actual [{:actual-weight 105 :actual-reps 10}]
          result (proj/merge-sets planned actual)]
      (is (= 3 (count result)))
      ;; First set: planned + actual
      (is (= 100 (:prescribed-weight (first result))))
      (is (= 105 (:actual-weight (first result))))
      ;; Incomplete sets: planned data, no actual
      (is (= 100 (:prescribed-weight (second result))))
      (is (nil? (:actual-weight (second result))))
      (is (= 100 (:prescribed-weight (nth result 2))))
      (is (nil? (:actual-weight (nth result 2)))))))

(deftest find-by-name-test
  (testing "finds exercise by name"
    (let [exercises [{:name "Squat" :sets []}
                     {:name "Bench Press" :sets []}]
          result (proj/find-by-name exercises "Bench Press")]
      (is (= "Bench Press" (:name result)))))

  (testing "returns nil when exercise not found"
    (let [exercises [{:name "Squat" :sets []}]
          result (proj/find-by-name exercises "Deadlift")]
      (is (nil? result)))))

(deftest merge-exercise-test
  (testing "merges planned exercise with actual performance"
    (let [planned {:name "Bench Press"
                   :sets [{:prescribed-weight 100 :prescribed-reps 8}]}
          actual {:name "Bench Press"
                  :sets [{:actual-weight 105 :actual-reps 10}]}
          result (proj/merge-exercise planned actual)]
      (is (= "Bench Press" (:name result)))
      (is (= 1 (count (:sets result))))
      (is (= 100 (:prescribed-weight (first (:sets result)))))
      (is (= 105 (:actual-weight (first (:sets result)))))))

  (testing "handles planned exercise with no actual performance"
    (let [planned {:name "Bench Press"
                   :sets [{:prescribed-weight 100 :prescribed-reps 8}]}
          result (proj/merge-exercise planned nil)]
      (is (= "Bench Press" (:name result)))
      (is (= 1 (count (:sets result))))
      (is (= 100 (:prescribed-weight (first (:sets result)))))
      (is (nil? (:actual-weight (first (:sets result))))))))

(deftest merge-workout-test
  (testing "merges planned workout with actual performance"
    (let [planned {:name "Upper" :day :monday
                   :exercises [{:name "Bench Press"
                                :sets [{:prescribed-weight 100 :prescribed-reps 8}]}]}
          actual {:name "Upper" :day :monday
                  :exercises [{:name "Bench Press"
                               :sets [{:actual-weight 105 :actual-reps 10}]}]}
          result (proj/merge-workout planned actual)]
      (is (= "Upper" (:name result)))
      (is (= :monday (:day result)))
      (is (= 1 (count (:exercises result))))
      (is (= "Bench Press" (:name (first (:exercises result)))))
      (is (= 100 (:prescribed-weight (first (:sets (first (:exercises result)))))))))

  (testing "handles planned workout with no actual performance"
    (let [planned {:name "Upper" :day :monday
                   :exercises [{:name "Bench Press"
                                :sets [{:prescribed-weight 100 :prescribed-reps 8}]}]}
          result (proj/merge-workout planned nil)]
      (is (= "Upper" (:name result)))
      (is (= :monday (:day result)))
      (is (= 1 (count (:exercises result))))
      (is (nil? (:actual-weight (first (:sets (first (:exercises result)))))))))

  (testing "uses planned name and day as authoritative"
    (let [planned {:name "Upper Body" :day :monday
                   :exercises [{:name "Bench Press"
                                :sets [{:prescribed-weight 100 :prescribed-reps 8}]}]}
          actual {:name "Upper" :day :tuesday  ; different name/day
                  :exercises [{:name "Bench Press"
                               :sets [{:actual-weight 105 :actual-reps 10}]}]}
          result (proj/merge-workout planned actual)]
      (is (= "Upper Body" (:name result)))  ; uses planned name
      (is (= :monday (:day result))))))     ; uses planned day

(deftest merge-plan-with-progress-test
  (testing "merges complete plan with complete progress"
    (let [progress (proj/build-state complete-microcycle-events)
          result (proj/merge-plan-with-progress sample-plan progress)]
      (is (= 1 (count (:microcycles result))))
      (is (= 2 (count (:workouts (first (:microcycles result))))))
      ;; Check that actual performance is merged in
      (let [first-workout (first (:workouts (first (:microcycles result))))
            bench-press (first (filter #(= "Bench Press" (:name %)) (:exercises first-workout)))
            first-set (first (:sets bench-press))]
        (is (= 100 (:prescribed-weight first-set)))  ; from plan
        (is (= 100 (:actual-weight first-set))))))   ; from events

  (testing "handles extra actual sets beyond plan"
    (let [events-with-extra-sets
          [{:type :microcycle-started}
           {:type :workout-started :name "Upper" :day :monday}
           {:type :set-logged :exercise "Bench Press" :weight 100 :reps 8}  ; planned set 1
           {:type :set-logged :exercise "Bench Press" :weight 105 :reps 8}  ; planned set 2  
           {:type :set-logged :exercise "Bench Press" :weight 110 :reps 6}  ; EXTRA set 3
           {:type :set-logged :exercise "Bench Press" :weight 115 :reps 4}  ; EXTRA set 4
           {:type :workout-completed}
           {:type :microcycle-completed}]
          progress (proj/build-state events-with-extra-sets)
          result (proj/merge-plan-with-progress sample-plan progress)
          first-workout (first (:workouts (first (:microcycles result))))
          bench-press (first (filter #(= "Bench Press" (:name %)) (:exercises first-workout)))]
      (is (= 4 (count (:sets bench-press))))  ; 2 planned + 2 extra
      ;; Planned sets
      (is (= 100 (:prescribed-weight (first (:sets bench-press)))))
      (is (= 100 (:actual-weight (first (:sets bench-press)))))
      (is (= 100 (:prescribed-weight (second (:sets bench-press)))))
      (is (= 105 (:actual-weight (second (:sets bench-press)))))
      ;; Extra sets (no prescribed data)
      (is (nil? (:prescribed-weight (nth (:sets bench-press) 2))))
      (is (= 110 (:actual-weight (nth (:sets bench-press) 2))))
      (is (nil? (:prescribed-weight (nth (:sets bench-press) 3))))
      (is (= 115 (:actual-weight (nth (:sets bench-press) 3))))))

  (testing "handles incomplete workouts (fewer actual sets than planned)"
    (let [incomplete-events
          [{:type :microcycle-started}
           {:type :workout-started :name "Upper" :day :monday}
           {:type :set-logged :exercise "Bench Press" :weight 100 :reps 8}  ; only 1 of 2 planned sets
           {:type :workout-completed}
           {:type :microcycle-completed}]
          progress (proj/build-state incomplete-events)
          result (proj/merge-plan-with-progress sample-plan progress)
          first-workout (first (:workouts (first (:microcycles result))))
          bench-press (first (filter #(= "Bench Press" (:name %)) (:exercises first-workout)))]
      (is (= 2 (count (:sets bench-press))))  ; still shows 2 sets (1 done, 1 planned)
      ;; Completed set
      (is (= 100 (:prescribed-weight (first (:sets bench-press)))))
      (is (= 100 (:actual-weight (first (:sets bench-press)))))
      ;; Incomplete set
      (is (= 100 (:prescribed-weight (second (:sets bench-press)))))
      (is (nil? (:actual-weight (second (:sets bench-press)))))))

  (testing "handles completely missing exercises"
    (let [partial-events
          [{:type :microcycle-started}
           {:type :workout-started :name "Upper" :day :monday}
           {:type :set-logged :exercise "Bench Press" :weight 100 :reps 8}
           ;; No Barbell Row performed
           {:type :workout-completed}
           {:type :microcycle-completed}]
          progress (proj/build-state partial-events)
          result (proj/merge-plan-with-progress sample-plan progress)
          first-workout (first (:workouts (first (:microcycles result))))
          barbell-row (first (filter #(= "Barbell Row" (:name %)) (:exercises first-workout)))]
      (is (= "Barbell Row" (:name barbell-row)))
      (is (= 1 (count (:sets barbell-row))))
      (is (= 80 (:prescribed-weight (first (:sets barbell-row)))))
      (is (nil? (:actual-weight (first (:sets barbell-row)))))))

  (testing "handles plan with no actual progress"
    (let [result (proj/merge-plan-with-progress sample-plan (proj/build-state []))]
      (is (= sample-plan result))))  ; should return plan unchanged

  (testing "handles active/incomplete microcycle"
    (let [progress (proj/build-state active-workout-events)
          result (proj/merge-plan-with-progress sample-plan progress)]
      (is (= 1 (count (:microcycles result))))
      (is (= 2 (count (:workouts (first (:microcycles result))))))  ; plan has 2 workouts
      ;; First workout has actual data
      (let [first-workout (first (:workouts (first (:microcycles result))))
            bench-press (first (filter #(= "Bench Press" (:name %)) (:exercises first-workout)))
            first-set (first (:sets bench-press))]
        (is (= 105 (:actual-weight first-set))))
      ;; Second workout has no actual data (only plan)
      (let [second-workout (second (:workouts (first (:microcycles result))))
            squat (first (filter #(= "Squat" (:name %)) (:exercises second-workout)))
            first-set (first (:sets squat))]
        (is (= 140 (:prescribed-weight first-set)))
        (is (nil? (:actual-weight first-set)))))))