(ns com.barbiff.domain.projection

  "Annotates training plans with actual progress from event logs.
   
   Design principle: Sets are the source of truth.
   Workout and microcycle boundaries enforce structure.
   Exercise grouping is derived from set metadata.
   
   The top-level data structure represents a mesocycle - a multi-week training block.
   A mesocycle contains microcycles (typically weeks), which contain workouts,
   which contain exercises, which contain sets.
   
   
   ARCHITECTURE NOTE: Event Validation Strategy
   =============================================
   
   Q: Should we use build-state to validate events before logging?
   A: NO - that would mix concerns. Here's why:
   
   Projection (this namespace):
   - Purpose: Transform valid event log -> domain state
   - Assumption: Events are already valid
   - Role: Read-side optimization
   
   Event Logging (separate namespace):
   - Purpose: Validate and persist events
   - Responsibility: Ensure only valid events enter the log
   - Validation approach: Business rules, not projection success
   
   Why not use build-state as a guard?
   1. Projection is about interpretation, not validation
      - An event might be structurally valid but fail projection due to:
        * Missing previous events (out of order)
        * Projection bugs
        * Different projection strategies
   
   2. Business rules ≠ Projection rules
      - Valid business: 'Can I log this set given current workout state?'
      - Projection: 'Can I build a tree from this event stream?'
      - These are different questions!
   
   3. Multiple projections from same log
      - You might have different projections (UI, analytics, reporting)
      - Each might handle events differently
      - Event validity shouldn't depend on any particular projection
   
   Better approach for event logging:
   
   (defn log-set! [current-state exercise weight reps]
     ;; Validate business rules against current aggregate state
     (when-not (:active-workout current-state)
       (throw (ex-info \"No active workout\" {})))
     (when-not (pos? weight)
       (throw (ex-info \"Weight must be positive\" {:weight weight})))
     
     ;; Emit valid event
     (append-event! {:type :set-logged
                     :exercise exercise
                     :weight weight
                     :reps reps
                     :timestamp (now)}))
   
   The aggregate's current state (built from events) is used to validate
   commands, but the events themselves are validated on structure/business rules,
   not on whether a particular projection can handle them.
   
   TL;DR: Validate events with business logic and specs, not with projections.
   
   
   \"But won't I write similar code twice?\"
   =========================================
   
   Yes! And that's actually good. Here's why:
   
   Aggregate (for validation):
   - Tracks minimal state needed for business rules
   - Example: {:active-workout? true, :current-exercise \"Bench Press\"}
   - Optimized for: Fast validation, small memory footprint
   - May discard old data you don't need for validation
   
   Projection (this namespace):
   - Builds rich UI-friendly structure
   - Example: Full nested tree with all historical sets
   - Optimized for: Easy querying, display, merging with plans
   - Keeps everything for display
   
   They're similar but serve different masters:
   
   ;; Aggregate in events.clj
   (defn apply-event-for-validation [state event]
     (case (:type event)
       :workout-started (assoc state :active-workout? true)
       :workout-completed (assoc state :active-workout? false)
       :set-logged state  ; don't need to track sets for validation!
       state))
   
   ;; Projection in projection.clj  
   (defn sections-between [events ...]
     ;; Complex tree-building for UI display
     ...)
   
   The aggregate might be 10 lines, projection is 60+ lines.
   Different concerns, different complexity, different evolution.
   
   Benefits of separation:
   1. Aggregate can stay simple (only validation logic)
   2. Projection can get complex (sorting, grouping, formatting) without slowing down writes
   3. Multiple projections possible (analytics, reports, UI) without affecting validation
   4. Can rebuild projections anytime, but aggregate state affects what commands are valid NOW
   
   Think of it as: Aggregate = \"Can I do this?\" vs Projection = \"Show me what happened\"
   
   You might even skip the aggregate if validation is simple (just spec on events),
   and only use projections. But for complex rules (\"can't start workout if one is active\"),
   you need aggregate state.")




;; Example usage
(comment


  ;; A mesocycle is the top-level structure representing a multi-week training block
  (def plan
    {:name "Hypertrophy Block"
     :microcycles
     [{:workouts
       [{:name "Upper"
         :day :monday
         :exercises
         [{:name "Bench Press"
           :sets [{:prescribed-reps nil :prescribed-weight nil}
                  {:prescribed-reps nil :prescribed-weight nil}]}
          {:name "Barbell Row"
           :sets [{:prescribed-reps nil :prescribed-weight nil}
                  {:prescribed-reps nil :prescribed-weight nil}]}]}
        {:name "Lower"
         :day :wednesday
         :exercises
         [{:name "Squat"
           :sets [{:prescribed-reps nil :prescribed-weight nil}]}]}]}
      {:workouts
       [{:name "Upper"
         :day :monday
         :exercises
         [{:name "Bench Press"
           :sets [{:prescribed-reps nil :prescribed-weight nil}
                  {:prescribed-reps nil :prescribed-weight nil}]}
          {:name "Barbell Row"
           :sets [{:prescribed-reps nil :prescribed-weight nil}
                  {:prescribed-reps nil :prescribed-weight nil}]}]}
        {:name "Lower"
         :day :wednesday
         :exercises
         [{:name "Squat"
           :sets [{:prescribed-reps nil :prescribed-weight nil}]}]}]}]})

  (def plain-event-log
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
     {:type :microcycle-completed}
     {:type :microcycle-started}
     {:type :workout-started :name "Upper" :day :monday}
     {:type :set-logged :exercise "Bench Press" :weight 105 :reps 11}])

  ;; Core parsing primitive: extract bounded sections from event stream
  (defn sections-between
    "Extract sections between start and end markers from a flat event stream.
     
     This is the key parsing function that handles boundary events:
     - Starts accumulating when it sees a start-type event
     - Adds content events to the current section
     - Closes the section when it sees an end-type event
     - Handles incomplete sections (no end marker) by including them anyway
     
     Example: Given events [start-A, content, content, end-A, start-B, content]
              Returns [[start-A, content, content], [start-B, content]]
     
     This allows active/incomplete microcycles and workouts to be projected."
    [events start-type end-type]
    (let [{:keys [current sections]}
          (reduce (fn [{:keys [current sections]} event]
                    (cond
                      ;; Start a new section - save the start event
                      (= start-type (:type event))
                      {:current [event] :sections sections}

                      ;; End current section - save it and clear current
                      (= end-type (:type event))
                      {:current nil :sections (conj sections current)}

                      ;; Content event - add to current section if one is active
                      current
                      {:current (conj current event) :sections sections}

                      ;; Orphaned event (no active section) - ignore it
                      :else
                      {:current nil :sections sections}))
                  {:current nil :sections []}
                  events)]
      ;; After processing all events, include any incomplete section
      (if current
        (conj sections current)
        sections)))

  ;; Transformation functions: event -> domain model
  ;; These use the `->` naming convention to indicate pure transformations

  (defn ->set
    "Transform a set-logged event into a set domain object"
    [event]
    {:prescribed-weight nil
     :prescribed-reps nil
     :actual-weight (:weight event)
     :actual-reps (:reps event)})

  (defn ->exercise
    "Transform grouped set events into an exercise domain object"
    [[name events]]
    {:name name
     :sets (mapv ->set events)})

  (defn ->workout
    "Transform a workout section (start event + set events) into a workout domain object.
     Groups sets by exercise name to create the exercise hierarchy."
    [[start-event & set-events]]
    {:name (:name start-event)
     :day (:day start-event)
     :exercises (->> set-events
                     (group-by :exercise)  ; group sets by exercise name
                     (mapv ->exercise))})

  (defn ->microcycle
    "Transform a microcycle section into a microcycle domain object.
     Recursively extracts workout sections and transforms them."
    [events]
    {:workouts (->> (sections-between events :workout-started :workout-completed)
                    (mapv ->workout))})

  (defn build-state
    "Build the complete training state from a flat event log.
     
     This is the top-level projection function. It:
     1. Extracts microcycle sections from the event stream
     2. Transforms each section into a microcycle domain object
     3. Returns the full nested structure: microcycles -> workouts -> exercises -> sets
     
     The result can be easily merged with a training plan for progress tracking."
    [events]
    {:microcycles (->> (sections-between events :microcycle-started :microcycle-completed)
                       (mapv ->microcycle))})

  (build-state plain-event-log)

  ;; APPROACH REVIEW
  ;; ===============
  ;;
  ;; Idiomacy Rating: 9.5/10
  ;; - Uses standard Clojure patterns: reduce for stateful parsing, threading macros, pure transformations
  ;; - The `->` naming convention clearly signals data transformations
  ;; - group-by is the perfect tool for organizing sets into exercises
  ;; - No manual index manipulation or imperative updates
  ;; - Minimal deduction: reduce with explicit state is slightly imperative, but appropriate here
  ;;
  ;; Maintainability Rating: 10/10
  ;; - Single responsibility: each function does one clear thing
  ;; - Easy to test: all functions are pure (input -> output)
  ;; - Easy to extend: add new event types by adding new cases to sections-between
  ;; - Easy to debug: can inspect intermediate results at each step
  ;; - The recursive structure (microcycle -> workout) is explicit and easy to follow
  ;; - Validation at boundaries: Assumes valid events (enforced elsewhere), no defensive coding clutter
  ;;
  ;; Key Design Decisions:
  ;; 1. Separate parsing (sections-between) from transformation (->workout, etc.)
  ;;    - Makes the boundary-handling logic reusable
  ;;    - Transformation functions can assume they have complete sections
  ;;
  ;; 2. Handle incomplete sections gracefully
  ;;    - Critical for showing "current" state during active training
  ;;    - No special cases needed in transformation functions
  ;;
  ;; 3. group-by for exercise grouping instead of manual upsert
  ;;    - Leverages Clojure's strength: transforming collections
  ;;    - No need to track whether an exercise already exists
  ;;
  ;; 4. Trust the event log (validation elsewhere)
  ;;    - Events are validated when logged, not when projected
  ;;    - Keeps projection code focused and simple
  ;;    - Follows "parse, don't validate" and "make illegal states unrepresentable" principles
  ;;
  ;; Comparison to original approach:
  ;; - Original: ~40 lines with index arithmetic, manual state tracking
  ;; - This version: ~60 lines with comments, but much clearer intent
  ;; - No (dec (count ...)), no update-in paths, no index hunting
  ;; - Each function is independently understandable
  ;;
  ;; Overall: This is clean, functional, idiomatic Clojure. The structure mirrors
  ;; the problem domain (hierarchical sections in an event stream) and uses the
  ;; right tools for each job. The trust-boundaries approach is architecturally sound.
  )