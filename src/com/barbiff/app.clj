(ns com.barbiff.app
  (:require [com.biffweb :as biff :refer [q]]
            [com.barbiff.middleware :as mid]
            [com.barbiff.ui :as ui]
            [com.barbiff.domain.hardcorefunctionalprojection :as proj]
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

(defn get-user-events [db uid]
  (sort-by :event/timestamp
           (q db '{:find (pull event [*]) :in [uid]
                   :where [[event :event/user uid] [event :event/type]]} uid)))

(defn find-workout-for-exercise [exercise-name]
  (some (fn [mc]
          (some #(when (some (fn [e] (= exercise-name (:name e))) (:exercises %)) %)
                (:workouts mc)))
        (:microcycles sample-plan)))

(defn has-active-session? [events session-type]
  (let [start-type (keyword (str (name session-type) "-started"))
        end-type (keyword (str (name session-type) "-completed"))
        events-vec (vec events)
        last-start (last (keep-indexed #(when (= start-type (:event/type %2)) %1) events-vec))
        last-end (last (keep-indexed #(when (= end-type (:event/type %2)) %1) events-vec))]
    (or (nil? last-end) (and last-start last-end (> last-start last-end)))))

(defn make-event [uid type & {:as extras}]
  (merge {:db/doc-type :event :event/user uid :event/timestamp :db/now :event/type type} extras))

(defn parse-params [params]
  {:type (keyword (get params "event/type"))
   :exercise (get params "event/exercise")
   :weight (when-let [w (get params "event/weight")] (when (seq w) (Double/parseDouble w)))
   :reps (when-let [r (get params "event/reps")] (when (seq r) (Integer/parseInt r)))
   :name (get params "event/name")
   :day (get params "event/day")})

(defn log-event [{:keys [session params biff/db] :as ctx}]
  (let [uid (:uid session)
        events (get-user-events db uid)
        {:keys [type exercise weight reps name day]} (parse-params params)

        events-to-submit
        (if (= type :set-logged)
          (let [workout (find-workout-for-exercise exercise)
                auto-events (cond-> []
                              (not (has-active-session? events :microcycle))
                              (conj (make-event uid :microcycle-started))
                              (and workout (not (has-active-session? events :workout)))
                              (conj (make-event uid :workout-started
                                                :event/name (:name workout)
                                                :event/day (name (:day workout)))))]
            (conj auto-events
                  (make-event uid type :event/exercise exercise :event/weight weight :event/reps reps)))
          [(make-event uid type :event/name name :event/day day)])]

    (biff/submit-tx ctx events-to-submit)
    {:status 303 :headers {"location" "/app/workout"}}))

(defn set-status [set-data]
  (let [complete? (and (= (:prescribed-weight set-data) (:actual-weight set-data))
                       (= (:prescribed-reps set-data) (:actual-reps set-data))
                       (:actual-weight set-data) (:actual-reps set-data))]
    (cond complete? {:class "bg-green-50 border-green-300" :icon "✅"}
          (and (:actual-weight set-data) (:actual-reps set-data)) {:class "bg-yellow-50 border-yellow-300" :icon "💪"}
          :else {:class "bg-gray-50 border-gray-200" :icon nil})))

(defn set-input [exercise-name set-data]
  (biff/form
   {:action "/app/workout/log-set" :class "flex gap-2 items-center"}
   [:input {:type "hidden" :name "event/type" :value "set-logged"}]
   [:input {:type "hidden" :name "event/exercise" :value exercise-name}]
   [:input.w-16.text-sm.px-2.py-1.border.rounded
    {:type "number" :name "event/weight" :step "0.5" :required true
     :placeholder (str (:prescribed-weight set-data))
     :defaultValue (:prescribed-weight set-data)}]
   [:span.text-xs.text-gray-500 "kg ×"]
   [:input.w-12.text-sm.px-2.py-1.border.rounded
    {:type "number" :name "event/reps" :required true
     :placeholder (str (:prescribed-reps set-data))
     :defaultValue (:prescribed-reps set-data)}]
   [:button.bg-green-600.hover:bg-green-700.text-white.px-3.py-1.text-xs.rounded.font-semibold
    {:type "submit"} "✓"]))

(defn render-set [_idx exercise-name set-data]
  (let [{:keys [class icon]} (set-status set-data)
        has-actual? (and (:actual-weight set-data) (:actual-reps set-data))]
    [:.p-3.rounded.border {:class class}
     [:div.flex.justify-between.items-start.gap-4
      [:div.flex-1
       (when (or (:prescribed-weight set-data) (:prescribed-reps set-data))
         [:div.text-sm.text-gray-600.mb-1
          "📋 Planned: " (:prescribed-weight set-data) "kg × " (:prescribed-reps set-data) " reps"])
       (if has-actual?
         [:div.font-semibold
          (when icon [:<> icon " "])
          "Actual: " (:actual-weight set-data) "kg × " (:actual-reps set-data) " reps"]
         [:div.text-sm.text-gray-400.italic "Click ✓ to log →"])]
      (when-not has-actual? [:div (set-input exercise-name set-data)])]]))

(defn render-items [items render-fn]
  (map-indexed (fn [i item] ^{:key i} (render-fn i item)) items))

(defn render-exercise [exercise]
  [:.mb-4.p-4.bg-white.border.border-gray-200.rounded-lg
   [:h4.text-lg.font-semibold.mb-3 (:name exercise)]
   [:div.space-y-2 (render-items (:sets exercise) #(render-set %1 (:name exercise) %2))]])

(defn render-workout [workout]
  [:.mb-6.p-4.bg-gray-50.rounded-lg.border-2.border-gray-300
   [:div.flex.justify-between.items-center.mb-4
    [:h3.text-xl.font-bold (:name workout)]
    [:span.text-sm.text-gray-600 "Day: " (name (or (:day workout) :unknown))]]
   [:div.space-y-3 (render-items (:exercises workout) (fn [_ e] (render-exercise e)))]])

(defn render-microcycle [idx microcycle]
  [:.mb-8.p-6.bg-white.rounded-xl.shadow-md
   [:h2.text-2xl.font-bold.mb-6 "Microcycle " (inc idx)]
   [:div.space-y-4 (render-items (:workouts microcycle) (fn [_ w] (render-workout w)))]])

(defn render-projection [events]
  (let [merged (proj/merge-plan-with-progress sample-plan (proj/build-state events))]
    [:div
     [:h2.text-2xl.font-bold.mb-6 "Training Plan with Progress"]
     [:div.space-y-6 (render-items (:microcycles merged) render-microcycle)]]))

(defn control-button [type label icon]
  (biff/form
   {:action "/app/workout/log-event" :class "inline"}
   [:input {:type "hidden" :name "event/type" :value type}]
   [:button.btn.bg-blue-600.hover:bg-blue-700 {:type "submit"} icon " " label]))

(defn workout-controls []
  [:div.mb-6.p-4.bg-blue-50.rounded-lg.shadow
   [:h3.text-lg.font-semibold.mb-3 "Session Controls"]
   [:p.text-sm.text-gray-600.mb-3 "Just log sets by clicking ✓ below. Workouts and microcycles start automatically!"]
   [:div.flex.flex-wrap.gap-3
    (control-button "workout-completed" "Complete Workout" "✅")
    (control-button "microcycle-completed" "Complete Microcycle" "🏆")]])

(defn render-event-field [label value & [suffix]]
  (when value [:div.text-gray-700 label ": " value suffix]))

(defn render-event [event]
  [:.p-3.bg-white.rounded.border.border-gray-200.text-sm
   [:div.flex.justify-between
    [:span.font-mono.text-blue-600 (:event/type event)]
    [:span.text-gray-500 (biff/format-date (:event/timestamp event) "HH:mm:ss")]]
   (render-event-field "Name" (:event/name event))
   (render-event-field "Day" (:event/day event))
   (render-event-field "Exercise" (:event/exercise event))
   (render-event-field "Weight" (:event/weight event) "kg")
   (render-event-field "Reps" (:event/reps event))])

(defn ->projection-event [e]
  (cond-> {:type (:event/type e)}
    (:event/name e) (assoc :name (:event/name e))
    (:event/day e) (assoc :day (:event/day e))
    (:event/exercise e) (assoc :exercise (:event/exercise e))
    (:event/weight e) (assoc :weight (:event/weight e))
    (:event/reps e) (assoc :reps (:event/reps e))))

(defn normalize-event [e]
  (-> e
      (update :event/type keyword)
      (update :event/day (fn [d] (when d (keyword d))))
      (update :event/weight (fn [w] (when w (Double/parseDouble (str w)))))
      (update :event/reps (fn [r] (when r (Integer/parseInt (str r)))))))

(defn workout-page [{:keys [session biff/db]}]
  (let [{:user/keys [email]} (xt/entity db (:uid session))
        events (get-user-events db (:uid session))
        projection-events (->> events (map normalize-event) (map ->projection-event))]
    (ui/page
     {}
     [:div.max-w-6xl.mx-auto
      [:div.flex.justify-between.items-center.mb-6
       [:h1.text-3xl.font-bold "Workout Tracker"]
       [:div
        [:span.text-gray-600 email " | "]
        (biff/form {:action "/auth/signout" :class "inline"}
                   [:button.text-blue-500.hover:text-blue-800 {:type "submit"} "Sign out"])]]

      (workout-controls)

      [:details.mb-4.p-4.bg-yellow-50.rounded
       [:summary.cursor-pointer.font-semibold "Debug: View Projection Events"]
       [:pre.text-xs.overflow-auto (pr-str projection-events)]]

      [:div.p-6.bg-white.rounded-xl.shadow-md.mb-8
       (render-projection projection-events)]

      [:details.mb-8
       [:summary.cursor-pointer.text-lg.font-semibold.p-4.bg-gray-100.rounded.hover:bg-gray-200
        "📜 View Raw Event Log (" (count events) " events)"]
       [:div.mt-4.p-6.bg-white.rounded-xl.shadow-md
        [:div.space-y-2
         (if (empty? events)
           [:p.text-gray-500 "No events yet"]
           (render-items (reverse events) (fn [_ e] (render-event e))))]]]])))

(def module
  {:routes ["/app" {:middleware [mid/wrap-signed-in]}
            ["/workout" {:get workout-page}]
            ["/workout/log-event" {:post log-event}]
            ["/workout/log-set" {:post log-event}]]})
