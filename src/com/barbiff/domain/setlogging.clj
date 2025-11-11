(ns com.barbiff.domain.setlogging
  "Pure functional domain logic for set logging.
   
   Philosophy: Pure functions, no side effects, explicit data flow.
   Separates business logic from HTTP handler concerns.")

;; Event Creation

(defn make-event
  "Create a flat event map with type and attributes.
   Matches the projection event structure: {:type :event-type :attr1 val1 ...}"
  [type attrs]
  (merge {:type type} attrs))

;; Session State Queries

(defn active-session?
  "Check if events contain an active session of given type.
   A session is active if it has a start event but no corresponding end event."
  [events session-type]
  (let [start-type (keyword (str (name session-type) "-started"))
        end-type (keyword (str (name session-type) "-completed"))
        starts (filter #(= start-type (:type %)) events)
        ends (filter #(= end-type (:type %)) events)]
    (> (count starts) (count ends))))

;; Plan Navigation

(defn find-workout-for-exercise
  "Find the workout containing the given exercise-name in the plan."
  [plan exercise-name]
  (->> plan
       :microcycles
       (mapcat :workouts)
       (filter #(some (fn [ex] (= exercise-name (:name ex))) (:exercises %)))
       first))

;; Event Generation for Set Logging

(defn required-startup-events
  "Generate startup events needed before logging a set.
   Returns a sequence of events required to establish session context.
   
   Logic:
   - If no active microcycle, create microcycle-started event
   - If no active workout, create workout-started event with name/day from plan
   - Otherwise, return empty sequence"
  [events plan exercise-name]
  (let [needs-microcycle? (not (active-session? events :microcycle))
        needs-workout? (not (active-session? events :workout))
        workout (when needs-workout?
                  (find-workout-for-exercise plan exercise-name))]
    (cond-> []
      needs-microcycle?
      (conj (make-event :microcycle-started {}))

      (and needs-workout? workout)
      (conj (make-event :workout-started
                        {:name (:name workout)
                         :day (:day workout)})))))

(defn set-logged-event
  "Create a set-logged event from parameters.
   Matches projection structure: {:type :set-logged :exercise name :weight w :reps r}"
  [exercise-name weight reps]
  (make-event :set-logged
              {:exercise exercise-name
               :weight weight
               :reps reps}))

(defn events-for-set-log
  "Generate all events needed to log a set.
   Returns sequence of events: startup events + set-logged event."
  [events plan exercise-name weight reps]
  (let [startup (required-startup-events events plan exercise-name)
        set-log (set-logged-event exercise-name weight reps)]
    (conj startup set-log)))

;; Event Transformation

(defn normalize-event
  "Normalize DB event type keywords and data types.
   Ensures consistent types for domain logic processing."
  [e]
  (-> e
      (update :event/type keyword)
      (update :event/day (fn [d] (when d (keyword d))))
      (update :event/weight (fn [w] (when w (Double/parseDouble (str w)))))
      (update :event/reps (fn [r] (when r (Integer/parseInt (str r)))))))

(defn ->projection-event
  "Convert a normalized database event to a projection event.
   Extracts type and relevant data fields into simple event map."
  [e]
  (cond-> {:type (:event/type e)}
    (:event/name e) (assoc :name (:event/name e))
    (:event/day e) (assoc :day (:event/day e))
    (:event/exercise e) (assoc :exercise (:event/exercise e))
    (:event/weight e) (assoc :weight (:event/weight e))
    (:event/reps e) (assoc :reps (:event/reps e))
    (:event/rir e) (assoc :rir (:event/rir e))
    (:event/set e) (assoc :set-num (:event/set e))))
