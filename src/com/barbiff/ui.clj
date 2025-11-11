(ns com.barbiff.ui
  (:require [cheshire.core :as cheshire]
            [clojure.java.io :as io]
            [com.barbiff.settings :as settings]
            [com.biffweb :as biff]
            [ring.middleware.anti-forgery :as csrf]
            [ring.util.response :as ring-response]
            [rum.core :as rum]))

(defn static-path [path]
  (if-some [last-modified (some-> (io/resource (str "public" path))
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str path "?t=" last-modified)
    path))

(defn base [{:keys [::recaptcha] :as ctx} & body]
  (apply
   biff/base-html
   (-> ctx
       (merge #:base{:title settings/app-name
                     :lang "en-US"
                     :icon "/img/glider.png"
                     :description (str settings/app-name " Description")
                     :image "https://clojure.org/images/clojure-logo-120b.png"})
       (update :base/head (fn [head]
                            (concat [[:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/daisyui@4.12.14/dist/full.min.css"}]
                                     [:link {:rel "stylesheet" :href (static-path "/css/main.css")}]
                                     [:script {:src (static-path "/js/main.js")}]
                                     [:script {:src "https://unpkg.com/htmx.org@2.0.7"}]
                                     [:script {:src "https://unpkg.com/htmx-ext-ws@2.0.2/ws.js"}]
                                     [:script {:src "https://unpkg.com/hyperscript.org@0.9.14"}]
                                     (when recaptcha
                                       [:script {:src "https://www.google.com/recaptcha/api.js"
                                                 :async "async" :defer "defer"}])]
                                    head))))
   body))

(defn page [ctx & body]
  (base
   ctx
   [:div.flex.h-full.w-full
    (when (bound? #'csrf/*anti-forgery-token*)
      {:hx-headers (cheshire/generate-string
                    {:x-csrf-token csrf/*anti-forgery-token*})})
    body]))

(defn on-error [{:keys [status] :as ctx}]
  {:status status
   :headers {"content-type" "text/html"}
   :body (rum/render-static-markup
          (page
           ctx
           [:h1.text-lg.font-bold
            (if (= status 404)
              "Page not found."
              "Something went wrong.")]))})

;; ====================================
;; PRIMITIVES - Low-level UI elements
;; ====================================

(defn card [class & content]
  (into [:div.shadow-sm {:class (str "bg-base-100 dark:bg-base-200 " class)}] content))

(defn input-number [attrs]
  [:input.rounded-none.px-1.text-center.outline-none.transition-opacity
   (merge {:type "number"
           :inputmode "decimal"
           :class "h-8 w-full bg-base-200/30 dark:bg-base-300/40 border dark:border-base-100 text-base-content"}
          attrs)])

(defn button [class text]
  [:button.btn.btn-sm.uppercase {:type "submit" :class class} text])

(defn hidden [name value]
  [:input {:type "hidden" :name name :value value}])

(defn text-muted [text]
  [:span {:class "text-sm text-base-content/60"} text])

(defn badge [class text]
  [:span.badge.badge-sm {:class class} text])

(defn spacer [size]
  [(keyword (str "div.space-y-" size))])

(defn render-items [items render-fn]
  (map-indexed (fn [i item] ^{:key i} (render-fn i item)) items))

(defn log-checkbox [enabled?]
  [:div.flex.h-9.w-9.items-center.justify-center
   [:div.relative.flex.items-center.justify-center.rounded-sm.h-7.w-7
    {:class (if enabled? "bg-emerald-600 dark:bg-emerald-500" "bg-base-200 dark:bg-base-content/30")}
    [:div.absolute.inset-1.bg-base-100.dark:bg-base-200 {:class (when-not enabled? "hidden")}]]])

;; ====================================
;; SET COMPONENTS - Individual set rendering
;; ====================================

(def set-grid-layout "grid.w-full.grid-cols-8.pr-4")
(def set-col-weight "col-span-3.flex.items-center.justify-center")
(def set-col-reps "col-span-3.flex.items-center.justify-center")
(def set-col-spacer "col-span-1")
(def set-col-log "col-span-1.flex.items-center.justify-end")
(def set-input-width "w-4/5 desktop:w-2/3")

(defn set-grid-cell [col-class & content]
  (into [(keyword (str "div." col-class))] content))

(defn set-value-wrapper [value]
  [:div.relative.flex.w-full.items-center.justify-center
   [:div.relative.flex.items-center.justify-center {:class set-input-width}
    [:div.h-8.flex.items-center.justify-center.font-mono.text-sm value]]])

(defn set-input-wrapper [name attrs]
  [:div.relative.flex.w-full.items-center.justify-center
   [:div.relative.flex.items-center.justify-center {:class set-input-width}
    (input-number (assoc attrs :name name))]])

(defn set-input-row [exercise-name set-data enabled?]
  (biff/form
   {:action "/app/workout/log-set"}
   (hidden "event/type" "set-logged")
   (hidden "event/exercise" exercise-name)
   [:div.flex.items-center
    [:div.w-8.shrink-0]
    [(keyword (str "div." set-grid-layout))
     (set-grid-cell set-col-weight
                    (set-input-wrapper "event/weight"
                                       {:step "0.5" :required true :disabled (not enabled?)
                                        :placeholder "kg" :value (str (:prescribed-weight set-data))}))
     (set-grid-cell set-col-reps
                    (set-input-wrapper "event/reps"
                                       {:required true :disabled (not enabled?)
                                        :placeholder (str (:prescribed-reps set-data)) :value ""}))
     (set-grid-cell set-col-spacer)
     (set-grid-cell set-col-log
                    [:button.disabled:cursor-not-allowed {:type "submit" :disabled (not enabled?)}
                     (log-checkbox enabled?)])]]))

(defn set-completed-row [set-data]
  [:div.flex.items-center
   [:div.w-8.shrink-0]
   [(keyword (str "div." set-grid-layout))
    (set-grid-cell set-col-weight (set-value-wrapper (:actual-weight set-data)))
    (set-grid-cell set-col-reps (set-value-wrapper (:actual-reps set-data)))
    (set-grid-cell set-col-spacer)
    (set-grid-cell set-col-log (log-checkbox true))]])

(defn render-set [idx exercise-name set-data]
  (let [has-actual? (and (:actual-weight set-data) (:actual-reps set-data))
        is-first? (zero? idx)]
    [:li.ml-4.py-2.5 {:data-set-id (str "set-" idx)}
     (if has-actual?
       (set-completed-row set-data)
       (set-input-row exercise-name set-data is-first?))]))

;; ====================================
;; EXERCISE COMPONENTS - Exercise cards
;; ====================================

(defn exercise-header [exercise]
  [:div.px-4
   [:div.flex.items-start.justify-between
    [:h3.line-clamp-2.font-medium.text-base-content (:name exercise)]
    [:div.flex.items-center.gap-4]] ; Info icon placeholder
   [:h4 {:class "mt-1 text-xs uppercase text-base-content/60"}
    (or (:equipment exercise) "bodyweight")]])

(defn exercise-column-headers []
  [:div.relative.mt-2
   [:div.ml-4.flex.items-center
    [:div.h-8.w-8.shrink-0]
    [(keyword (str "div." set-grid-layout))
     (set-grid-cell set-col-weight [:div.text-center.text-sm.font-medium.uppercase "weight"])
     (set-grid-cell set-col-reps [:div.text-center.text-sm.font-medium.uppercase "reps"])
     (set-grid-cell set-col-spacer)
     (set-grid-cell set-col-log [:div.pr-1.text-sm.font-medium.uppercase "log"])]]])

(defn exercise-sets-list [exercise]
  [:ol {:class "divide-y divide-base-200/80 dark:divide-base-100"}
   (render-items (:sets exercise) #(render-set %1 (:name exercise) %2))])

(defn render-exercise [exercise]
  (card "pb-2 pt-4 mb-2"
        (exercise-header exercise)
        (exercise-column-headers)
        (exercise-sets-list exercise)))

;; ====================================
;; WORKOUT COMPONENTS - Workout & plan structure
;; ====================================

(defn muscle-group-badge [name]
  [:span.-mb-2.ml-4.mt-4.flex.w-fit.items-center.gap-4.bg-base-100.dark:bg-base-200
   [:span.flex.items-center.gap-2.rounded-sm.px-2.py-0.5
    {:style {:background-color "rgba(224, 62, 195, 0.314)"
             :border "1px solid rgba(224, 62, 195, 0.314)"}}
    [:span.text-xs.font-semibold.uppercase name]]])

(defn render-workout [workout]
  [:div
   (muscle-group-badge (:name workout))
   (into [:div] (render-items (:exercises workout) (fn [_ e] (render-exercise e))))])

(defn render-microcycle [idx microcycle]
  [:div.mb-6
   [:h2.px-4.text-xs.uppercase.mb-2 {:class "text-base-content/60"}
    "Week " (inc idx)]
   (into [:div] (render-items (:workouts microcycle) (fn [_ w] (render-workout w))))])

(defn render-projection [merged-plan]
  [:div.relative.mx-auto.max-w-3xl.pb-4
   [:div.sticky.inset-x-0.top-0.z-30.mb-4
    [:div.relative.z-10.border-t.pt-3.shadow.dark:shadow-2xl
     {:class "border-base-200/30 bg-base-100 dark:bg-base-200"}
     [:h2.px-4.text-xs.uppercase {:class "text-base-content/60"} "Training Plan"]]]
   (into [:div] (render-items (:microcycles merged-plan) render-microcycle))])

;; ====================================
;; PAGE COMPONENTS - Controls & event log
;; ====================================

(defn control-button [type label icon]
  (biff/form
   {:action "/app/workout/log-event" :class "inline"}
   (hidden "event/type" type)
   [:button.btn.btn-sm.btn-primary.uppercase {:type "submit"} icon " " label]))

(defn render-event-field [label value & [suffix]]
  (when value
    [:div.text-xs
     [:span {:class "text-base-content/50"} label ": "]
     [:span.font-mono value suffix]]))

(defn render-event [event]
  (card "bg-base-200"
        [:div.flex.justify-between.items-start.mb-2
         (badge "badge-primary" (:event/type event))
         [:span {:class "text-xs font-mono text-base-content/50"}
          (biff/format-date (:event/timestamp event) "HH:mm:ss")]]
        [:div.space-y-1
         (render-event-field "Name" (:event/name event))
         (render-event-field "Day" (:event/day event))
         (render-event-field "Exercise" (:event/exercise event))
         (render-event-field "Weight" (:event/weight event) "kg")
         (render-event-field "Reps" (:event/reps event))]))

;; ====================================
;; PAGE SECTIONS - Extracted helpers
;; ====================================

(defn page-header [email]
  [:div.flex.justify-between.items-center.p-4.border-b {:class "border-base-200/30"}
   [:h1.text-sm.font-bold.uppercase "Workout Tracker"]
   [:div.flex.items-center.gap-3
    [:span {:class "text-xs text-base-content/60"} email]
    (biff/form {:action "/auth/signout" :class "inline"}
               [:button.btn.btn-ghost.btn-xs.uppercase {:type "submit"} "Sign out"])]])

(defn page-controls []
  [:div.px-4.py-2.flex.gap-2
   (control-button "workout-completed" "Complete Workout" "✓")
   (control-button "microcycle-completed" "Complete Microcycle" "✓✓")])

(defn debug-section [projection-events]
  [:details.mx-4.mb-4 {:class "bg-warning/10 border border-warning p-2"}
   [:summary.cursor-pointer.text-xs.uppercase "Debug"]
   [:pre.mt-2.text-xs.overflow-auto.bg-base-100.p-2 (pr-str projection-events)]])

(defn event-log-section [events]
  [:details.mx-4.mb-6
   [:summary.cursor-pointer.text-sm.font-bold.p-4.bg-base-200.uppercase
    "Event Log (" (count events) ")"]
   [:div.p-4.border.border-base-200.border-t-0
    (if (empty? events)
      [:p {:class "text-center text-base-content/40 py-4"} "No events yet"]
      (into (spacer 2) (render-items (reverse events) (fn [_ e] (render-event e)))))]])

(defn workout-page [{:keys [email merged-plan projection-events events]}]
  (page
   {}
   [:main.relative.flex.h-full.w-full.flex-col
    [:div.flex.min-h-0.grow.flex-col
     [:div.flex.min-h-0.grow.flex-col
      [:div.min-h-0.grow.overflow-auto.overscroll-contain.outline-none.bg-base-200.dark:bg-base-300 {:tabindex "1"}
       (page-header email)
       (page-controls)
       (render-projection merged-plan)
       (debug-section projection-events)
       (event-log-section events)]]]]))
