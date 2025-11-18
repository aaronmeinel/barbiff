(ns com.barbiff.app
  (:require [com.biffweb :as biff :refer [q]]
            [com.barbiff.middleware :as mid]
            [com.barbiff.ui :as ui]
            [com.barbiff.domain.hardcorefunctionalprojection :as proj]
            [com.barbiff.domain.setlogging :as setlog]
            [com.barbiff.domain.events :as events]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [rum.core :as rum]
            [xtdb.api :as xt]))


;; TODO Move this to dedicated file, clean up a bit, write tests
;; Loading template from an edn and transforming into an actual plan. Not working perfectly yet.
(defn expand-sets-in-exercise
  "Takes a map that represents an exercise in a template and returns exactly that with a sequence of actual sets, not only the number of sets.
  "
  [{:keys [name n-sets muscle-groups equipment]}]
  (let [sets (repeat n-sets {:prescribed-weight nil :prescribed-reps nil :actual-weight nil :actual-reps nil})]
    {:name name :sets sets :muscle-groups muscle-groups :equipment equipment}))



(defn expand-exercises [{:keys [name day exercises]}]
  {:name name :day day :exercises (mapv expand-sets-in-exercise exercises)})


(defn ->plan [template]
  {:microcycles (repeat (:n-microcycles template) (update template :workouts #(mapv expand-exercises %)))})

(def template (->> (io/resource "sample-plan.edn")
                   slurp
                   edn/read-string))


;; Workout Tracking



;; Database Queries

(defn get-user-events [db uid]
  (sort-by :event/timestamp
           (q db '{:find (pull event [*]) :in [uid]
                   :where [[event :event/user uid] [event :event/type]]} uid)))

;; HTTP Handler

(defn log-event
  "HTTP handler for logging workout events.

   Event transformation pipeline:
   1. Parse HTTP params into simple map (:type, :exercise-id, :weight, :reps)
   2. Fetch user's existing events from DB
   3. Normalize DB events → domain events (strip DB namespace prefixes)
   4. Pass domain events to pure business logic (setlog/events-for-set-log)
   5. Business logic generates all required events (startup + set-logged)
   6. Convert domain events → DB events (add :db/doc-type, :event/ namespace)
   7. Persist DB events via biff/submit-tx

   Why normalize DB → domain → DB?
   - Business logic is pure and DB-agnostic
   - Domain events are simple maps: {:type :set-logged :exercise \"Bench\" :weight 100}
   - DB events have persistence metadata: {:db/doc-type :event :event/type :set-logged ...}
   - This separation allows testing domain logic without DB"
  [{:keys [session params biff/db] :as ctx}]
  (let [uid (:uid session)
        events (get-user-events db uid)
        {:keys [type exercise-id weight reps]} (events/parse-params params)]

    (cond
      ;; Handle set logging
      (and (= type :set-logged) exercise-id weight reps)
      (let [;; Convert DB events to domain events for business logic
            projection-events (->> events
                                   (map setlog/normalize-event)
                                   (map setlog/->projection-event))

            ;; Generate all needed events using pure domain logic
            domain-events (setlog/events-for-set-log projection-events
                                                     sample-plan
                                                     exercise-id
                                                     weight
                                                     reps)

            ;; Convert back to DB events for persistence
            ;; Use millisecond offsets to preserve ordering within batch
            db-events (map-indexed (fn [idx event]
                                     (events/->db-event uid event idx))
                                   domain-events)]

        (biff/submit-tx ctx db-events))

      ;; Handle workout completion
      (= type :workout-completed)
      (let [domain-event {:type :workout-completed}
            db-event (events/->db-event uid domain-event)]
        (biff/submit-tx ctx [db-event])))

    {:status 303 :headers {"location" "/app/workout"}}))

(defn get-active-plan
  "Get user's active plan structure. Falls back to sample-plan if none exists."
  []
  ->plan template)

(defn workout-page [{:keys [session biff/db]}]
  (let [{:user/keys [email]} (xt/entity db (:uid session))
        events (get-user-events db (:uid session))
        projection-events (->> events
                               (map setlog/normalize-event)
                               (map setlog/->projection-event))
        progress (proj/build-state projection-events)
        plan (->plan template)
        merged-plan (proj/merge-plan-with-progress plan progress)]
    (ui/workout-page {:email email
                      :merged-plan merged-plan
                      :projection-events projection-events
                      :progress progress  ; Add for debugging
                      :events events})))



(def module
  {:routes ["/app" {:middleware [mid/wrap-signed-in]}
            ["/workout" {:get workout-page}]
            ["/workout/log-event" {:post log-event}]
            ["/workout/log-set" {:post log-event}]]})
