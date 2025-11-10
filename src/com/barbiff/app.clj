(ns com.barbiff.app
  (:require [com.biffweb :as biff :refer [q]]
            [com.barbiff.middleware :as mid]
            [com.barbiff.ui :as ui]
            [com.barbiff.settings :as settings]
            [com.barbiff.domain.hardcorefunctionalprojection :as proj]
            [rum.core :as rum]
            [xtdb.api :as xt]
            [ring.websocket :as ws]
            [cheshire.core :as cheshire]))

(defn set-foo [{:keys [session params] :as ctx}]
  (biff/submit-tx ctx
                  [{:db/op :update
                    :db/doc-type :user
                    :xt/id (:uid session)
                    :user/foo (:foo params)}])
  {:status 303
   :headers {"location" "/app"}})

(defn bar-form [{:keys [value]}]
  (biff/form
   {:hx-post "/app/set-bar"
    :hx-swap "outerHTML"}
   [:label.block {:for "bar"} "Bar: "
    [:span.font-mono (pr-str value)]]
   [:.h-1]
   [:.flex
    [:input.w-full#bar {:type "text" :name "bar" :value value}]
    [:.w-3]
    [:button.btn {:type "submit"} "Update"]]
   [:.h-1]
   [:.text-sm.text-gray-600
    "This demonstrates updating a value with HTMX."]))

(defn set-bar [{:keys [session params] :as ctx}]
  (biff/submit-tx ctx
                  [{:db/op :update
                    :db/doc-type :user
                    :xt/id (:uid session)
                    :user/bar (:bar params)}])
  (biff/render (bar-form {:value (:bar params)})))

(defn message [{:msg/keys [text sent-at]}]
  [:.mt-3 {:_ "init send newMessage to #message-header"}
   [:.text-gray-600 (biff/format-date sent-at "dd MMM yyyy HH:mm:ss")]
   [:div text]])

(defn notify-clients [{:keys [com.barbiff/chat-clients]} tx]
  (doseq [[op & args] (::xt/tx-ops tx)
          :when (= op ::xt/put)
          :let [[doc] args]
          :when (contains? doc :msg/text)
          :let [html (rum/render-static-markup
                      [:div#messages {:hx-swap-oob "afterbegin"}
                       (message doc)])]
          ws @chat-clients]
    (ws/send ws html)))

(defn send-message [{:keys [session] :as ctx} {:keys [text]}]
  (let [{:keys [text]} (cheshire/parse-string text true)]
    (biff/submit-tx ctx
                    [{:db/doc-type :msg
                      :msg/user (:uid session)
                      :msg/text text
                      :msg/sent-at :db/now}])))

(defn chat [{:keys [biff/db]}]
  (let [messages (q db
                    '{:find (pull msg [*])
                      :in [t0]
                      :where [[msg :msg/sent-at t]
                              [(<= t0 t)]]}
                    (biff/add-seconds (java.util.Date.) (* -60 10)))]
    [:div {:hx-ext "ws" :ws-connect "/app/chat"}
     [:form.mb-0 {:ws-send true
                  :_ "on submit set value of #message to ''"}
      [:label.block {:for "message"} "Write a message"]
      [:.h-1]
      [:textarea.w-full#message {:name "text"}]
      [:.h-1]
      [:.text-sm.text-gray-600
       "Sign in with an incognito window to have a conversation with yourself."]
      [:.h-2]
      [:div [:button.btn {:type "submit"} "Send message"]]]
     [:.h-6]
     [:div#message-header
      {:_ "on newMessage put 'Messages sent in the past 10 minutes:' into me"}
      (if (empty? messages)
        "No messages yet."
        "Messages sent in the past 10 minutes:")]
     [:div#messages
      (map message (sort-by :msg/sent-at #(compare %2 %1) messages))]]))

;; Workout Tracking UI

(def sample-plan
  "Sample training plan for demo purposes"
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

(defn get-user-events
  "Get all workout events for a user, ordered by timestamp"
  [db uid]
  (sort-by :event/timestamp
           (q db
              '{:find (pull event [*])
                :in [uid]
                :where [[event :event/user uid]
                        [event :event/type]]}
              uid)))

(defn find-workout-for-exercise
  "Find which workout in the plan contains this exercise"
  [exercise-name]
  (some (fn [microcycle]
          (some (fn [workout]
                  (when (some #(= exercise-name (:name %)) (:exercises workout))
                    workout))
                (:workouts microcycle)))
        (:microcycles sample-plan)))

(defn log-event [{:keys [session params biff/db] :as ctx}]
  (let [uid (:uid session)
        events (get-user-events db uid)
        weight-str (get params "event/weight")
        reps-str (get params "event/reps")
        event-type (keyword (get params "event/type"))

        ;; Check current state
        has-active-microcycle? (some #(= :microcycle-started (:event/type %)) events)
        events-vec (vec events)
        last-workout-start-idx (last (keep-indexed #(when (= :workout-started (:event/type %2)) %1) events-vec))
        last-workout-end-idx (last (keep-indexed #(when (= :workout-completed (:event/type %2)) %1) events-vec))
        has-active-workout? (and has-active-microcycle?
                                 (or (nil? last-workout-end-idx)
                                     (and last-workout-start-idx last-workout-end-idx
                                          (> last-workout-start-idx last-workout-end-idx))))

        ;; Auto-start microcycle/workout for set logging
        events-to-submit (if (= event-type :set-logged)
                           (let [exercise-name (get params "event/exercise")
                                 workout-info (find-workout-for-exercise exercise-name)
                                 auto-events (cond-> []
                                               ;; Auto-start microcycle if needed
                                               (not has-active-microcycle?)
                                               (conj {:db/doc-type :event
                                                      :event/user uid
                                                      :event/timestamp :db/now
                                                      :event/type :microcycle-started})

                                               ;; Auto-start workout if needed
                                               (and workout-info (not has-active-workout?))
                                               (conj {:db/doc-type :event
                                                      :event/user uid
                                                      :event/timestamp :db/now
                                                      :event/type :workout-started
                                                      :event/name (:name workout-info)
                                                      :event/day (name (:day workout-info))}))]
                             (conj auto-events
                                   (cond-> {:db/doc-type :event
                                            :event/user uid
                                            :event/timestamp :db/now
                                            :event/type event-type}
                                     (seq exercise-name) (assoc :event/exercise exercise-name)
                                     (and weight-str (seq weight-str)) (assoc :event/weight (Double/parseDouble weight-str))
                                     (and reps-str (seq reps-str)) (assoc :event/reps (Integer/parseInt reps-str)))))

                           ;; For explicit events, just create the single event
                           [(cond-> {:db/doc-type :event
                                     :event/user uid
                                     :event/timestamp :db/now
                                     :event/type event-type}
                              (seq (get params "event/name")) (assoc :event/name (get params "event/name"))
                              (seq (get params "event/day")) (assoc :event/day (get params "event/day")))])]

    (biff/submit-tx ctx events-to-submit)
    {:status 303
     :headers {"location" "/app/workout"}}))

(defn render-set [_set-idx exercise-name set-data]
  (let [has-prescribed (or (:prescribed-weight set-data) (:prescribed-reps set-data))
        has-actual (and (:actual-weight set-data) (:actual-reps set-data))
        weight-match (= (:prescribed-weight set-data) (:actual-weight set-data))
        reps-match (= (:prescribed-reps set-data) (:actual-reps set-data))
        complete-match (and weight-match reps-match has-actual)]
    [:.p-3.rounded.border
     {:class (cond
               complete-match "bg-green-50 border-green-300"
               has-actual "bg-yellow-50 border-yellow-300"
               :else "bg-gray-50 border-gray-200")}
     [:div.flex.justify-between.items-start.gap-4
      [:div.flex-1
       (when has-prescribed
         [:div.text-sm.text-gray-600.mb-1
          "📋 Planned: " (:prescribed-weight set-data) "kg × " (:prescribed-reps set-data) " reps"])
       (if has-actual
         [:div.font-semibold
          (if complete-match "✅ " "💪 ")
          "Actual: " (:actual-weight set-data) "kg × " (:actual-reps set-data) " reps"]
         [:div.text-sm.text-gray-400.italic "Click ✓ to log →"])]
      (when (not has-actual)
        [:div
         (biff/form
          {:action "/app/workout/log-set"
           :class "flex gap-2 items-center"}
          [:input {:type "hidden" :name "event/type" :value "set-logged"}]
          [:input {:type "hidden" :name "event/exercise" :value exercise-name}]
          [:input.w-16.text-sm.px-2.py-1.border.rounded {:type "number" :name "event/weight" :step "0.5"
                                                         :placeholder (str (:prescribed-weight set-data))
                                                         :defaultValue (:prescribed-weight set-data)
                                                         :required true}]
          [:span.text-xs.text-gray-500 "kg ×"]
          [:input.w-12.text-sm.px-2.py-1.border.rounded {:type "number" :name "event/reps"
                                                         :placeholder (str (:prescribed-reps set-data))
                                                         :defaultValue (:prescribed-reps set-data)
                                                         :required true}]
          [:button.bg-green-600.hover:bg-green-700.text-white.px-3.py-1.text-xs.rounded.font-semibold
           {:type "submit"} "✓"])])]]))

(defn render-exercise [exercise]
  [:.mb-4.p-4.bg-white.border.border-gray-200.rounded-lg
   [:h4.text-lg.font-semibold.mb-3 (:name exercise)]
   [:div.space-y-2
    (map-indexed (fn [i set-data]
                   ^{:key i} (render-set i (:name exercise) set-data))
                 (:sets exercise))]])

(defn render-workout [workout]
  [:.mb-6.p-4.bg-gray-50.rounded-lg.border-2.border-gray-300
   [:div.flex.justify-between.items-center.mb-4
    [:h3.text-xl.font-bold (:name workout)]
    [:span.text-sm.text-gray-600 (str "Day: " (name (or (:day workout) :unknown)))]]
   [:div.space-y-3
    (map-indexed (fn [i exercise]
                   ^{:key i} (render-exercise exercise))
                 (:exercises workout))]])

(defn render-microcycle [idx microcycle]
  [:.mb-8.p-6.bg-white.rounded-xl.shadow-md
   [:h2.text-2xl.font-bold.mb-6 (str "Microcycle " (inc idx))]
   [:div.space-y-4
    (map-indexed (fn [i workout]
                   ^{:key i} (render-workout workout))
                 (:workouts microcycle))]])

(defn render-projection [events]
  (let [progress (proj/build-state events)
        merged (proj/merge-plan-with-progress sample-plan progress)
        has-active-microcycle? (some #(= :microcycle-started (:type %)) events)
        has-active-workout? (and has-active-microcycle?
                                 (let [events-vec (vec events)
                                       last-workout-start-idx (last (keep-indexed #(when (= :workout-started (:type %2)) %1) events-vec))
                                       last-workout-end-idx (last (keep-indexed #(when (= :workout-completed (:type %2)) %1) events-vec))]
                                   (or (nil? last-workout-end-idx)
                                       (and last-workout-start-idx last-workout-end-idx (> last-workout-start-idx last-workout-end-idx)))))]
    [:div
     [:h2.text-2xl.font-bold.mb-6 "Training Plan with Progress"]
     [:div.space-y-6
      (map-indexed (fn [i microcycle]
                     ^{:key i} (render-microcycle i microcycle))
                   (:microcycles merged))]]))

(defn workout-controls []
  [:div.mb-6.p-4.bg-blue-50.rounded-lg.shadow
   [:h3.text-lg.font-semibold.mb-3 "Session Controls"]
   [:p.text-sm.text-gray-600.mb-3 "Just log sets by clicking ✓ below. Workouts and microcycles start automatically!"]
   [:div.flex.flex-wrap.gap-3
    (biff/form
     {:action "/app/workout/log-event" :class "inline"}
     [:input {:type "hidden" :name "event/type" :value "workout-completed"}]
     [:button.btn.bg-blue-600.hover:bg-blue-700 {:type "submit"}
      "✅ Complete Workout"])

    (biff/form
     {:action "/app/workout/log-event" :class "inline"}
     [:input {:type "hidden" :name "event/type" :value "microcycle-completed"}]
     [:button.btn.bg-blue-600.hover:bg-blue-700 {:type "submit"}
      "🏆 Complete Microcycle"])]])

(defn render-event [event]
  [:.p-3.bg-white.rounded.border.border-gray-200.text-sm
   [:div.flex.justify-between
    [:span.font-mono.text-blue-600 (:event/type event)]
    [:span.text-gray-500 (biff/format-date (:event/timestamp event) "HH:mm:ss")]]
   (when-let [name (:event/name event)]
     [:div.text-gray-700 "Name: " name])
   (when-let [day (:event/day event)]
     [:div.text-gray-700 "Day: " day])
   (when-let [exercise (:event/exercise event)]
     [:div.text-gray-700 "Exercise: " exercise])
   (when-let [weight (:event/weight event)]
     [:div.text-gray-700 "Weight: " weight "kg"])
   (when-let [reps (:event/reps event)]
     [:div.text-gray-700 "Reps: " reps])])

(defn workout-page [{:keys [session biff/db]}]
  (let [{:user/keys [email]} (xt/entity db (:uid session))
        events (get-user-events db (:uid session))
        event-maps (map #(-> %
                             (update :event/type keyword)
                             (update :event/day (fn [d] (when d (keyword d))))
                             (update :event/weight (fn [w] (when w (Double/parseDouble (str w)))))
                             (update :event/reps (fn [r] (when r (Integer/parseInt (str r))))))
                        events)
        projection-events (map (fn [e]
                                 (cond-> {:type (:event/type e)}
                                   (:event/name e) (assoc :name (:event/name e))
                                   (:event/day e) (assoc :day (:event/day e))
                                   (:event/exercise e) (assoc :exercise (:event/exercise e))
                                   (:event/weight e) (assoc :weight (:event/weight e))
                                   (:event/reps e) (assoc :reps (:event/reps e))))
                               event-maps)]
    (ui/page
     {}
     [:div.max-w-6xl.mx-auto
      [:div.flex.justify-between.items-center.mb-6
       [:h1.text-3xl.font-bold "Workout Tracker"]
       [:div
        [:span.text-gray-600 email " | "]
        (biff/form
         {:action "/auth/signout"
          :class "inline"}
         [:button.text-blue-500.hover:text-blue-800 {:type "submit"}
          "Sign out"])]]

      [:.mb-6
       [:a.text-blue-500.hover:text-blue-800 {:href "/app"} "← Back to main app"]]

      ;; Workout controls
      (workout-controls)

      ;; Debug info
      [:details.mb-4.p-4.bg-yellow-50.rounded
       [:summary.cursor-pointer.font-semibold "Debug: View Projection Events"]
       [:pre.text-xs.overflow-auto (pr-str projection-events)]]

      ;; Main content: Plan + Progress
      [:div.p-6.bg-white.rounded-xl.shadow-md.mb-8
       (render-projection projection-events)]

      ;; Collapsible event log
      [:details.mb-8
       [:summary.cursor-pointer.text-lg.font-semibold.p-4.bg-gray-100.rounded.hover:bg-gray-200
        "📜 View Raw Event Log (" (count events) " events)"]
       [:div.mt-4.p-6.bg-white.rounded-xl.shadow-md
        [:div.space-y-2
         (if (empty? events)
           [:p.text-gray-500 "No events yet"]
           (map-indexed (fn [i event]
                          ^{:key i} (render-event event))
                        (reverse events)))]]]])))

(defn app [{:keys [session biff/db] :as ctx}]
  (let [{:user/keys [email foo bar]} (xt/entity db (:uid session))]
    (ui/page
     {}
     [:div "Signed in as " email ". "
      (biff/form
       {:action "/auth/signout"
        :class "inline"}
       [:button.text-blue-500.hover:text-blue-800 {:type "submit"}
        "Sign out"])
      "."]
     [:.h-6]
     [:div.mb-4
      [:a.text-blue-500.hover:text-blue-800.text-lg {:href "/app/workout"}
       "→ Go to Workout Tracker"]]
     [:.h-6]
     (biff/form
      {:action "/app/set-foo"}
      [:label.block {:for "foo"} "Foo: "
       [:span.font-mono (pr-str foo)]]
      [:.h-1]
      [:.flex
       [:input.w-full#foo {:type "text" :name "foo" :value foo}]
       [:.w-3]
       [:button.btn {:type "submit"} "Update"]]
      [:.h-1]
      [:.text-sm.text-gray-600
       "This demonstrates updating a value with a plain old form."])
     [:.h-6]
     (bar-form {:value bar})
     [:.h-6]
     (chat ctx))))

(defn ws-handler [{:keys [com.barbiff/chat-clients] :as ctx}]
  {:status 101
   :headers {"upgrade" "websocket"
             "connection" "upgrade"}
   ::ws/listener {:on-open (fn [ws]
                             (swap! chat-clients conj ws))
                  :on-message (fn [ws text-message]
                                (send-message ctx {:ws ws :text text-message}))
                  :on-close (fn [ws _status-code _reason]
                              (swap! chat-clients disj ws))}})

(def about-page
  (ui/page
   {:base/title (str "About " settings/app-name)}
   [:p "This app was made with "
    [:a.link {:href "https://biffweb.com"} "Biff"] "."]))

(defn echo [{:keys [params]}]
  {:status 200
   :headers {"content-type" "application/json"}
   :body params})

(def module
  {:static {"/about/" about-page}
   :routes ["/app" {:middleware [mid/wrap-signed-in]}
            ["" {:get app}]
            ["/set-foo" {:post set-foo}]
            ["/set-bar" {:post set-bar}]
            ["/chat" {:get ws-handler}]
            ["/workout" {:get workout-page}]
            ["/workout/log-event" {:post log-event}]
            ["/workout/log-set" {:post log-event}]]
   :api-routes [["/api/echo" {:post echo}]]
   :on-tx notify-clients})
