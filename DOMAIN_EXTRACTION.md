# Domain Logic Extraction: Set Logging

## Overview

Extracted business logic from `app.clj` (HTTP handler layer) into a new domain module: `domain/setlogging.clj`.

## What Was Extracted

### From `app.clj` → `domain/setlogging.clj`

1. **Session State Management**
   - `has-active-session?` → `active-session?`
   - Pure function checking if a session type (microcycle/workout) is currently active

2. **Plan Navigation**
   - `find-workout-for-exercise` → `find-workout-for-exercise`
   - Pure function finding workout containing an exercise name

3. **Event Creation**
   - `make-event` → `make-event`
   - Pure function creating domain event maps

4. **Auto-Start Logic**
   - Complex auto-start logic from `log-event` → `required-startup-events`
   - Pure function generating startup events (microcycle-started, workout-started)
   - Encapsulates the business rule: "automatically start sessions if needed"

5. **Event Composition**
   - Logic from `log-event` → `events-for-set-log`
   - Pure function composing all events needed to log a set
   - Combines startup events + set-logged event

6. **Event Transformation**
   - `normalize-event` and `->projection-event`
   - Pure functions converting DB events → domain events

## What Remains in `app.clj`

1. **HTTP Concerns**
   - `parse-params` - HTTP parameter parsing
   - Route definitions
   - HTTP response creation

2. **Database Concerns**
   - `get-user-events` - Database queries
   - `->db-event` - Domain event → DB event conversion
   - Transaction submission

3. **Integration**
   - `log-event` - Now a thin handler orchestrating domain logic
   - `workout-page` - Page rendering handler

## Architecture Benefits

### Before
```
app.clj (handler)
├── HTTP parsing
├── Business logic (mixed in!)
├── Auto-start logic (mixed in!)
├── DB queries
└── HTTP response
```

### After
```
app.clj (handler)                     domain/setlogging.clj (pure functions)
├── HTTP parsing                      ├── active-session?
├── DB queries                        ├── find-workout-for-exercise
├── Call domain functions ────────────├── required-startup-events
├── Convert domain → DB events        ├── events-for-set-log
└── HTTP response                     ├── set-logged-event
                                      ├── normalize-event
                                      └── ->projection-event
```

## Code Style

The domain module follows the same style as `hardcorefunctionalprojection.clj`:
- **Clean**: Clear function names, single responsibility
- **Functional**: Pure functions, no side effects
- **Concise**: Minimal code, maximum expressiveness
- **Idiomatic**: Leverages Clojure idioms (threading macros, lazy seqs, destructuring)

## Example Usage

```clojure
;; Handler layer (app.clj)
(defn log-event [{:keys [session params biff/db] :as ctx}]
  (let [uid (:uid session)
        events (get-user-events db uid)
        {:keys [type exercise-id set-num weight reps rir]} (parse-params params)]
    
    (when (and (= type :set-logged) exercise-id set-num weight reps rir)
      (let [;; Convert DB events to domain events
            projection-events (->> events
                                   (map setlog/normalize-event)
                                   (map setlog/->projection-event))
            
            ;; Generate all needed events using pure domain logic
            domain-events (setlog/events-for-set-log projection-events
                                                      sample-plan
                                                      exercise-id
                                                      set-num
                                                      weight
                                                      reps
                                                      rir)
            
            ;; Convert back to DB events for persistence
            db-events (map #(->db-event uid %) domain-events)]
        
        (biff/submit-tx ctx db-events)))
    
    {:status 303 :headers {"location" "/app/workout"}}))
```

The handler is now a thin orchestration layer:
1. Parse HTTP input
2. Query database
3. **Call pure domain functions** (no business logic here!)
4. Convert domain events to DB format
5. Persist and respond

All business logic is in the domain layer, making it:
- **Testable**: No HTTP or DB dependencies
- **Reusable**: Can be called from anywhere
- **Maintainable**: Business rules in one place
- **Understandable**: Clear separation of concerns
