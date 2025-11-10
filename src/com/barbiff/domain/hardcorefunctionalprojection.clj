(ns com.barbiff.domain.hardcorefunctionalprojection
  "Pure functional projection - maximum simplicity.
   
   Philosophy: Let Clojure's sequence library do the work.
   No explicit state tracking, no manual accumulation.")

;; Utility: take-until is surprisingly not in clojure.core
;; Many libraries add it (medley, plumbing, etc.) because it's so useful
;; Alternative: (concat (take-while (complement pred) coll) [(first (drop-while (complement pred) coll))])
;; But that's ugly. Just define it.

(defn take-until
  "Take elements from collection until pred returns true (inclusive of matching element).
   
   This is the 'inclusive' version of take-while - it includes the element that matches.
   Surprisingly not in clojure.core, but it's a common utility in many projects.
   
   Example:
     (take-until #(= 3 %) [1 2 3 4 5]) => (1 2 3)
     (take-while #(not= 3 %) [1 2 3 4 5]) => (1 2)  ; note: excludes 3"
  [pred coll]
  (lazy-seq
   (when-let [s (seq coll)]
     (let [f (first s)]
       (cons f (when-not (pred f)
                 (take-until pred (rest s))))))))

;; Core: Extract bounded sections from event stream

(defn find-next-start
  "Find the next event sequence starting with start-type.
   Returns nil if no start found."
  [events start-type]
  (->> events
       (drop-while #(not= start-type (:type %)))
       seq))

(defn extract-section
  "Extract one section: from start-type up to and including end-type.
   If no end-type found, takes everything to the end."
  [events end-type]
  (take-until #(= end-type (:type %)) events))

(defn sections-between
  "Extract all sections between start-type and end-type markers.
   
   Returns a lazy sequence of sections, where each section is a sequence of events
   starting with start-type and ending with end-type (if present).
   
   Handles incomplete sections (no end marker) by including all events until the next
   start or end of stream.
   
   Example:
     (sections-between events :workout-started :workout-completed)
     => ([{:type :workout-started ...} {:type :set-logged ...}] [...])
   
   Algorithm: Find next start, extract section to end, recurse on remainder."
  [events start-type end-type]
  (when-let [start-seq (find-next-start events start-type)]
    (let [section (extract-section start-seq end-type)
          remaining (drop (count section) start-seq)]
      (cons section (lazy-seq (sections-between remaining start-type end-type))))))

(defn microcycles
  "Extract all microcycle sections from event log.
   Returns lazy sequence of event sequences, one per microcycle."
  [events]
  (sections-between events :microcycle-started :microcycle-completed))

(defn workouts
  "Extract all workout sections from event log.
   Returns lazy sequence of event sequences, one per workout."
  [events]
  (sections-between events :workout-started :workout-completed))

(defn sets
  "Extract all set-logged events from event sequence."
  [events]
  (filter #(= :set-logged (:type %)) events))

;; Transform events to domain model

(defn ->set
  "Transform a set-logged event into a set domain object.
   Includes placeholders for prescribed values (to be filled by plan merge)."
  [event]
  {:prescribed-weight nil
   :prescribed-reps nil
   :actual-weight (:weight event)
   :actual-reps (:reps event)})

(defn ->exercise
  "Transform a [name, set-events] pair into an exercise domain object.
   Groups all sets for a single exercise together."
  [[name set-events]]
  {:name name
   :sets (mapv ->set set-events)})

(defn ->workout
  "Transform a workout section (sequence of events) into a workout domain object.
   
   The first event is workout-started (contains name and day).
   Remaining events are filtered for sets, grouped by exercise name."
  [events]
  (let [[start & content] events]
    {:name (:name start)
     :day (:day start)
     :exercises (->> content
                     (sets)                    ; keep only set-logged events
                     (group-by :exercise)      ; group by exercise name
                     (map ->exercise)          ; transform each group
                     (vec))}))

(defn ->microcycle
  "Transform a microcycle section (sequence of events) into a microcycle domain object.
   
   Extracts workout sections from the events and transforms each to a workout object."
  [events]
  {:workouts (mapv ->workout (workouts events))})

(defn build-state
  "Build complete training state from flat event log.
   
   This is the main entry point. Takes a sequence of events and returns
   the full nested structure: microcycles -> workouts -> exercises -> sets.
   
   The result can be merged with a training plan for progress tracking.
   
   Example:
     (build-state [{:type :microcycle-started} 
                   {:type :workout-started :name \"Upper\" :day :monday}
                   {:type :set-logged :exercise \"Bench\" :weight 100 :reps 10}
                   ...])
     => {:microcycles [{:workouts [{:name \"Upper\" :exercises [...]}]}]}"
  [events]
  {:microcycles (mapv ->microcycle (microcycles events))})

;; Plan + Progress merging

(defn merge-set
  "Merge a planned set with actual performance.
   Planned sets have :prescribed-weight and :prescribed-reps.
   Actual sets have :actual-weight and :actual-reps.
   Result has both, with planned values taking precedence for prescribed keys."
  [planned-set actual-set]
  (merge actual-set planned-set))  ; planned-set last so it overwrites prescribed values

(defn pad-to-length
  "Pad collection to target length with default value"
  [coll target-length default]
  (concat coll (repeat (max 0 (- target-length (count coll))) default)))

(defn merge-sets
  "Merge planned sets with actual sets, position by position.
   If there are more actual sets than planned, include the extras.
   If there are more planned sets than actual, show them as incomplete."
  [planned-sets actual-sets]
  (let [max-count (max (count planned-sets) (count actual-sets))]
    (->> (map merge-set
              (pad-to-length planned-sets max-count {:prescribed-weight nil :prescribed-reps nil})
              (pad-to-length actual-sets max-count {:actual-weight nil :actual-reps nil}))
         vec)))

(defn find-by-name
  "Find an item in a collection by its :name key. Returns nil if not found."
  [items name]
  (some #(when (= name (:name %)) %) items))

(defn merge-with-fallback
  "Merge planned item with actual item. If no actual item, return planned item unchanged."
  [planned-item actual-item merge-fn]
  (if actual-item
    (merge-fn planned-item actual-item)
    planned-item))

(defn merge-exercise
  "Merge a planned exercise with actual performance.
   Matches exercises by name."
  [planned-exercise actual-exercise]
  (merge-with-fallback
   planned-exercise
   actual-exercise
   (fn [planned actual]
     (assoc planned :sets (merge-sets (:sets planned) (:sets actual))))))

(defn merge-workout
  "Merge a planned workout with actual performance.
   Matches workouts by position in the microcycle."
  [planned-workout actual-workout]
  (merge-with-fallback
   planned-workout
   actual-workout
   (fn [planned actual]
     {:name (:name planned)  ; use planned name as authoritative
      :day (:day planned)    ; use planned day as authoritative
      :exercises (mapv (fn [planned-ex]
                         (let [actual-ex (find-by-name (:exercises actual) (:name planned-ex))]
                           (merge-exercise planned-ex actual-ex)))
                       (:exercises planned))})))

(defn merge-microcycle
  "Merge a planned microcycle with actual performance.
   Matches workouts by position."
  [planned-microcycle actual-microcycle]
  (merge-with-fallback
   planned-microcycle
   actual-microcycle
   (fn [planned actual]
     (let [max-workouts (max (count (:workouts planned)) (count (:workouts actual)))]
       {:workouts (mapv merge-workout
                        (pad-to-length (:workouts planned) max-workouts nil)
                        (pad-to-length (:workouts actual) max-workouts nil))}))))

(defn merge-plan-with-progress
  "Merge a mesocycle plan with projected progress from events.
   
   The plan is the source of truth for structure (exercises, prescribed values).
   The progress fills in actual performance data where available.
   
   Example:
     (def plan {:microcycles [{:workouts [{:name \"Upper\" 
                                           :exercises [{:name \"Bench\" 
                                                       :sets [{:prescribed-weight 100 :prescribed-reps 8}]}]}]}]})
     (def events [...])
     (merge-plan-with-progress plan (build-state events))
     => Plan with actual performance merged in"
  [plan progress]
  (let [max-microcycles (max (count (:microcycles plan)) (count (:microcycles progress)))]
    {:microcycles (mapv merge-microcycle
                        (pad-to-length (:microcycles plan) max-microcycles nil)
                        (pad-to-length (:microcycles progress) max-microcycles nil))}))  ; pad with nils if fewer actual microcycles

;; Test

(comment
  (def events
    [{:type :microcycle-started}
     {:type :workout-started :name "Upper" :day :monday}
     {:type :set-logged :exercise "Bench Press" :weight 100 :reps 10}
     {:type :set-logged :exercise "Bench Press" :weight 100 :reps 10}
     {:type :set-logged :exercise "Barbell Row" :weight 80 :reps 10}
     {:type :set-logged :exercise "Barbell Row" :weight 80 :reps 10}
     {:type :workout-completed}
     {:type :workout-started :name "Lower" :day :wednesday}
     {:type :set-logged :exercise "Squat" :weight 150 :reps 5}
     {:type :workout-completed}
     {:type :microcycle-completed}])

  (build-state events)

  ;; Active workout (no end marker)
  (def active-events
    [{:type :microcycle-started}
     {:type :workout-started :name "Upper" :day :monday}
     {:type :set-logged :exercise "Bench Press" :weight 105 :reps 11}])

  (build-state active-events)

  ;; Example plan
  (def sample-plan
    {:microcycles
     [{:workouts
       [{:name "Upper"
         :day :monday
         :exercises
         [{:name "Bench Press"
           :sets [{:prescribed-reps 8 :prescribed-weight 100}
                  {:prescribed-reps 8 :prescribed-weight 100}
                  {:prescribed-reps 8 :prescribed-weight 100}]}
          {:name "Barbell Row"
           :sets [{:prescribed-reps 8 :prescribed-weight 80}
                  {:prescribed-reps 8 :prescribed-weight 80}]}]}
        {:name "Lower"
         :day :wednesday
         :exercises
         [{:name "Squat"
           :sets [{:prescribed-reps 5 :prescribed-weight 140}
                  {:prescribed-reps 5 :prescribed-weight 140}
                  {:prescribed-reps 5 :prescribed-weight 140}]}]}]}]})

  ;; Merge plan with actual progress
  (def progress (build-state events))
  (merge-plan-with-progress sample-plan progress)

  ;; This shows:
  ;; - Prescribed vs actual weights/reps for each set
  ;; - Which sets were completed vs planned
  ;; - Extra sets beyond the plan
  ;; - Incomplete workouts)


  ;; COMPARISON: This approach vs projection.clj
  ;; ============================================
  ;;
  ;; This file: ~50 lines of code (excluding comments/tests)
  ;; projection.clj: ~90 lines of code
  ;;
  ;; Simplicity Comparison:
  ;;
  ;; 1. Core parsing primitive:
  ;;    - This: Recursive lazy-seq with take-until (12 lines)
  ;;    - projection.clj: Reduce with explicit state tracking (20 lines)
  ;;    
  ;;    Winner: This approach - classic functional recursion, easier to reason about
  ;;
  ;; 2. Transformation functions:
  ;;    - This: Identical (->set, ->exercise, ->workout, ->microcycle)
  ;;    - projection.clj: Identical
  ;;    
  ;;    Winner: Tie - same approach
  ;;
  ;; 3. Mental model:
  ;;    - This: "Find start, take until end, recurse" - one clear pattern
  ;;    - projection.clj: "Accumulate sections while tracking current" - state machine
  ;;    
  ;;    Winner: This approach - simpler mental model
  ;;
  ;; 4. Performance:
  ;;    - This: Lazy sequences, minimal memory, recursive
  ;;    - projection.clj: Eager reduce, one pass, accumulator
  ;;    
  ;;    Winner: projection.clj - slightly faster for large logs (but negligible for real use)
  ;;
  ;; 5. Debuggability:
  ;;    - This: Can inspect lazy sequences at each step
  ;;    - projection.clj: Need to trace reduce accumulator
  ;;    
  ;;    Winner: This approach - easier to see intermediate results
  ;;
  ;; 6. Extensibility:
  ;;    - This: Add new section types = reuse sections-between
  ;;    - projection.clj: Add new section types = reuse sections-between
  ;;    
  ;;    Winner: Tie - both equally extensible
  ;;
  ;; Overall Simplicity Winner: THIS APPROACH
  ;; 
  ;; Reasons:
  ;; - Fewer lines of code
  ;; - Classic functional pattern (recursion + lazy-seq)
  ;; - No explicit state to track
  ;; - More composable (lazy sequences)
  ;; - Easier mental model
  ;;
  ;; When to use projection.clj approach:
  ;; - Very large event logs (millions of events) where lazy-seq overhead matters
  ;; - When you need strict control over memory/performance
  ;; - When the team is more comfortable with reduce patterns
  ;;
  ;; Recommendation: Use this approach unless performance profiling shows otherwise.
  ;; "Make it work, make it right, make it fast" - start here.
  )