(ns com.barbiff.schema)

(def schema
  {:user/id :uuid
   :user [:map {:closed true}
          [:xt/id          :user/id]
          [:user/email     :string]
          [:user/joined-at inst?]]

   :event/id :uuid
   :event [:map {:closed true}
           [:xt/id                           :event/id]
           [:event/user                      :user/id]
           [:event/type                      :keyword]
           [:event/timestamp                 inst?]
           [:event/name {:optional true}     :string]
           [:event/day {:optional true}      :string]
           [:event/exercise {:optional true} :string]
           [:event/weight {:optional true}   :double]
           [:event/reps {:optional true}     :int]]})

(def module
  {:schema schema})
