(ns clojars.s3
  (:require
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as credentials])
  (:import
   (java.io ByteArrayInputStream)
   (org.apache.commons.io IOUtils)))

(defprotocol S3
  (-get-object-stream [client bucket-name key])
  (-list-objects [client bucket-name prefix])
  (-put-object [client bucket-name key stream]))

(defn- list-objects-chunk
  [client bucket-name prefix marker]
  (let [request (cond-> {:Bucket bucket-name}
                  prefix (assoc :Prefix prefix)
                  marker (assoc :Marker marker))]
    (aws/invoke client
                {:op :ListObjects
                 :request request})))

(defn- list-objects-seq
  "Generates a lazy seq of objects, chunked by the API's paging."
  [client bucket-name prefix marker]
  (let [{:keys [Contents IsTruncated]}
        (list-objects-chunk client bucket-name prefix marker)]
    (if IsTruncated
      (lazy-seq
        (concat Contents
                (list-objects-seq client bucket-name prefix
                                  (-> Contents last :Key))))
      Contents)))

(defrecord S3Client [s3]
  S3
  (-get-object-stream [_ bucket-name key]
    (-> s3
        (aws/invoke {:op :GetObject
                     :request {:Bucket bucket-name
                               :Key key}})
        :Body))
  (-list-objects [_ bucket-name prefix]
    (map :Key (list-objects-seq s3 bucket-name prefix nil)))
  (-put-object [_ bucket-name key stream]
    (aws/invoke s3 {:op :PutObject
                    :request {:Bucket bucket-name
                              :Key key
                              :Body stream}})))

(defn s3-client
  [access-key-id secret-access-key region]
  (->S3Client
    (doto
        (aws/client
          {:api :s3
           :credentials-provider (credentials/basic-credentials-provider
                                   {:access-key-id     access-key-id
                                    :secret-access-key secret-access-key})
           :region region})
      (aws/validate-requests true))))

(defrecord MockS3Client [state]
  S3
  (-get-object-stream [_ bucket-name key]
    (when-let [data (get-in @state [bucket-name key])]
      (ByteArrayInputStream. data)))
  (-list-objects [_ bucket-name _prefix]
    (keys (get @state bucket-name)))
  (-put-object [_ bucket-name key stream]
    (swap! state assoc-in [bucket-name key] (IOUtils/toByteArray stream))))

(defn mock-s3-client []
  (->MockS3Client (atom {})))

(defn get-object-stream
  [s3 bucket-name key]
  (-get-object-stream s3 bucket-name key))

(defn list-objects
  ([s3 bucket-name]
   (list-objects s3 bucket-name nil))
  ([s3 bucket-name prefix]
   (-list-objects s3 bucket-name prefix)))

(defn put-object
  [s3 bucket-name key stream]
  (-put-object s3 bucket-name key stream))