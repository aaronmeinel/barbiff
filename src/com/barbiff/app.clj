(ns com.barbiff.app
  (:require [com.biffweb :as biff :refer [q]]
            [com.barbiff.middleware :as mid]
            [com.barbiff.ui :as ui]
            [com.barbiff.domain.hardcorefunctionalprojection :as proj]
            [com.barbiff.domain.setlogging :as setlog]
            [xtdb.api :as xt]))

;; Workout Tracking

(def sample-plan
  {:microcycles
   [{:workouts
     [{:name "Upper" :day :monday
       :exercises [{:name "Bench Press" :sets [{:prescribed-reps 8 :prescribed-weight 100}
                                               {:prescribed-reps 8 :prescribed-weight 100}
                                               {:prescribed-reps 8 :prescribed-weight 100}]}
                   {:name "Barbell Row" :sets [{:prescribed-reps 8 :prescribed-weight 80}
                                               {:prescribed-reps 8 :prescribed-weight 80}]}]}
      {:name "Lower" :day :wednesday
       :exercises [{:name "Squat" :sets [{:prescribed-reps 5 :prescribed-weight 140}
                                         {:prescribed-reps 5 :prescribed-weight 140}
                                         {:prescribed-reps 5 :prescribed-weight 140}]}]}]}]})

;; HTTP Parameter Parsing

(defn parse-params [params]
  {:type (keyword (get params "event/type"))
   :exercise-id (get params "event/exercise")
   :weight (when-let [w (get params "event/weight")] (when (seq w) (Double/parseDouble w)))
   :reps (when-let [r (get params "event/reps")] (when (seq r) (Integer/parseInt r)))})

;; Database Queries

(defn get-user-events [db uid]
  (sort-by :event/timestamp
           (q db '{:find (pull event [*]) :in [uid]
                   :where [[event :event/user uid] [event :event/type]]} uid)))

;; Domain Event to DB Event Conversion

(defn ->db-event
  "Convert a domain event to a database event document for persistence.
   Domain events: {:type :event-type :exercise name :weight w ...}
   DB events: {:db/doc-type :event :event/type :event-type :event/exercise name ...}"
  [uid domain-event]
  (let [base {:db/doc-type :event
              :event/user uid
              :event/timestamp :db/now
              :event/type (:type domain-event)}]
    (cond-> base
      (:exercise domain-event) (assoc :event/exercise (:exercise domain-event))
      (:weight domain-event) (assoc :event/weight (:weight domain-event))
      (:reps domain-event) (assoc :event/reps (:reps domain-event))
      (:name domain-event) (assoc :event/name (:name domain-event))
      (:day domain-event) (assoc :event/day (name (:day domain-event))))))

;; HTTP Handler

(defn log-event [{:keys [session params biff/db] :as ctx}]
  (let [uid (:uid session)
        events (get-user-events db uid)
        {:keys [type exercise-id weight reps]} (parse-params params)]

    (when (and (= type :set-logged) exercise-id weight reps)
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
            db-events (map #(->db-event uid %) domain-events)]

        (biff/submit-tx ctx db-events)))

    {:status 303 :headers {"location" "/app/workout"}}))

(defn workout-page [{:keys [session biff/db]}]
  (let [{:user/keys [email]} (xt/entity db (:uid session))
        events (get-user-events db (:uid session))
        projection-events (->> events
                               (map setlog/normalize-event)
                               (map setlog/->projection-event))
        merged-plan (proj/merge-plan-with-progress sample-plan (proj/build-state projection-events))]
    (ui/workout-page {:email email
                      :merged-plan merged-plan
                      :projection-events projection-events
                      :events events})))

(def module
  {:routes ["/app" {:middleware [mid/wrap-signed-in]}
            ["/workout" {:get workout-page}]
            ["/workout/log-event" {:post log-event}]
            ["/workout/log-set" {:post log-event}]]})
