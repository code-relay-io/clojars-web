(ns clojars.system
  (:require [clojars
             [email :refer [simple-mailer]]
             [ring-servlet-patch :as patch]
             [search :refer [lucene-component]]
             [stats :refer [artifact-stats]]
             [storage :as storage]
             [web :as web]]
            [clojars.s3 :as s3]
            [clucy.core :as clucy]
            [com.stuartsierra.component :as component]
            [duct.component
             [endpoint :refer [endpoint-component]]
             [handler :refer [handler-component]]
             [hikaricp :refer [hikaricp]]]
            [meta-merge.core :refer [meta-merge]]
            [ring.component.jetty :refer [jetty-server]]))

(def base-env
  {:app {:middleware []}
   :http {:configurator patch/use-status-message-header}})

(defrecord StorageComponent [delegate on-disk-repo cloudfiles cdn-token cdn-url]
  storage/Storage
  (-write-artifact [_ path file force-overwrite?]
    (storage/write-artifact delegate path file force-overwrite?))
  (remove-path [_ path]
    (storage/remove-path delegate path))
  (path-exists? [_ path]
    (storage/path-exists? delegate path))
  (path-seq [_ path]
    (storage/path-seq delegate path))
  (artifact-url [_ path]
    (storage/artifact-url delegate path))
  
  component/Lifecycle
  (start [t]
    (if delegate
      t
      (assoc t
        :delegate (storage/full-storage on-disk-repo cloudfiles cdn-token cdn-url))))
  (stop [t]
    (assoc t :delegate nil)))

(defn storage-component [on-disk-repo cdn-token cdn-url]
  (map->StorageComponent {:on-disk-repo on-disk-repo
                          :cdn-token cdn-token
                          :cdn-url cdn-url}))

(defn s3-client [{:keys [access-key-id secret-access-key region]}]
  (s3/s3-client access-key-id secret-access-key region))

(defn new-system [config]
  (let [config (meta-merge base-env config)]
    (-> (component/system-map
         :app           (handler-component (:app config))
         :http          (jetty-server (:http config))
         :db            (hikaricp (:db config))
         :s3            (s3-client (:s3 config))
         :stats         (artifact-stats (get-in config [:s3 :stats-bucket]))
         :index-factory #(clucy/disk-index (:index-path config))
         :search        (lucene-component)
         :mailer        (simple-mailer (:mail config))
         :storage       (storage-component (:repo config) (:cdn-token config) (:cdn-url config))
         :clojars-app   (endpoint-component web/handler-optioned))
        (component/system-using
         {:http [:app]
          :app  [:clojars-app]
          :stats [:s3]
          :search [:index-factory :stats]
          :storage [:cloudfiles]
          :clojars-app [:storage :db :error-reporter :stats :search :mailer]}))))
