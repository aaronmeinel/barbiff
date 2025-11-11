(ns com.barbiff.domain.workout-filtering
  "Pure functional logic for filtering and selecting workouts from a training plan.
   
   This namespace contains the business logic for:
   - Determining which workout is currently active
   - Finding the next incomplete workout
   - Checking workout and set completion status
   
   Philosophy: Pure functions, no side effects, explicit data flow.
   Separates workout selection logic from UI rendering concerns.")

;; Set completion queries

(defn set-complete?
  "Check if a set has been completed (has actual weight and reps logged).
   
   A set is complete when both :actual-weight and :actual-reps are present.
   This is the single source of truth for set completion status."
  [set-data]
  (and (:actual-weight set-data)
       (:actual-reps set-data)))

(defn set-incomplete?
  "Check if a set needs completion (inverse of set-complete?)."
  [set-data]
  (not (set-complete? set-data)))

;; Workout completion queries

(defn workout-has-incomplete-sets?
  "Check if a workout has any incomplete sets.
   
   Returns true if any exercise in the workout has at least one incomplete set.
   Used to determine if a workout still needs work."
  [workout]
  (some (fn [exercise]
          (some set-incomplete? (:sets exercise)))
        (:exercises workout)))

;; Event queries

(defn active-workout-name
  "Extract the name of the currently active workout from projection events.
   
   A workout is active if there's a workout-started event without a corresponding
   workout-completed event after it.
   
   Returns the workout name, or nil if no workout is currently active.
   
   Example:
     (active-workout-name [{:type :microcycle-started}
                          {:type :workout-started :name \"Upper\"}
                          {:type :set-logged ...}])
     => \"Upper\"
     
     (active-workout-name [{:type :workout-started :name \"Upper\"}
                          {:type :workout-completed}])
     => nil"
  [projection-events]
  (let [starts (filter #(= :workout-started (:type %)) projection-events)
        ends (filter #(= :workout-completed (:type %)) projection-events)]
    (when (> (count starts) (count ends))
      (:name (last starts)))))

;; Workout selection

(defn find-current-workout
  "Find the current active workout or the next workout to do.
   
   Returns a map with :microcycle-idx and :workout, or nil if all workouts are complete.
   
   Logic:
   1. If there's an active workout (from events), return it
   2. Otherwise, return the first incomplete workout
   3. If all workouts are complete, return nil
   
   Example:
     (find-current-workout merged-plan projection-events)
     => {:microcycle-idx 0 :workout {:name \"Upper\" :exercises [...]}}
     
     (find-current-workout all-complete-plan [])
     => nil"
  [merged-plan projection-events]
  (let [active-name (active-workout-name projection-events)
        all-workouts (for [[mc-idx mc] (map-indexed vector (:microcycles merged-plan))
                           workout (:workouts mc)]
                       {:microcycle-idx mc-idx :workout workout})]
    (or
     ;; Find active workout by name
     (when active-name
       (some #(when (= active-name (get-in % [:workout :name])) %) all-workouts))
     ;; Find next incomplete workout
     (some #(when (workout-has-incomplete-sets? (:workout %)) %) all-workouts))))