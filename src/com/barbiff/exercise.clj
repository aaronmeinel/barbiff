(ns com.barbiff.exercise
  "Exercise library management - CRUD operations for exercises."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; Exercise Creation

(defn make-exercise
  "Create a new exercise entity for the database.
   
   Required:
   - name: Exercise name string
   - muscle-groups: Vector of muscle group keywords
   - user-id: UUID of the user
   
   Optional:
   - equipment: Vector of equipment keywords
   - notes: String notes
   - max-recoverable-sets: Integer"
  [{:keys [name muscle-groups user-id equipment notes max-recoverable-sets]}]
  (cond-> {:xt/id (random-uuid)
           :exercise/name name
           :exercise/muscle-groups muscle-groups
           :exercise/user user-id
           :exercise/created-at (java.util.Date.)}
    equipment (assoc :exercise/equipment equipment)
    notes (assoc :exercise/notes notes)
    max-recoverable-sets (assoc :exercise/max-recoverable-sets max-recoverable-sets)))

;; Seeding

(defn load-exercise-seed
  "Load exercise seed data from resources/exercise-seed.edn"
  []
  (-> "exercise-seed.edn"
      io/resource
      slurp
      edn/read-string))

(defn seed-exercises-for-user
  "Convert seed data to exercise entities for a specific user.
   Returns a vector of exercise maps ready for database insertion."
  [user-id]
  (let [seed-data (load-exercise-seed)]
    (mapv (fn [exercise-template]
            (make-exercise (assoc exercise-template :user-id user-id)))
          seed-data)))

;; Queries

(defn get-user-exercises
  "Get all exercises for a user from the database."
  [db user-id]
  (map first
       (com.biffweb/q db
                      '{:find [(pull exercise [*])]
                        :in [user-id]
                        :where [[exercise :exercise/user user-id]]}
                      user-id)))

(defn get-exercise-by-name
  "Find an exercise by name for a specific user."
  [db user-id exercise-name]
  (ffirst
   (com.biffweb/q db
                  '{:find [(pull exercise [*])]
                    :in [user-id exercise-name]
                    :where [[exercise :exercise/user user-id]
                            [exercise :exercise/name exercise-name]]}
                  user-id
                  exercise-name)))
