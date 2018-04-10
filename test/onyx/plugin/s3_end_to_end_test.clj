(ns onyx.plugin.s3-end-to-end-test
  (:require [clojure.core.async :refer [<!! >!! alts!! chan close! sliding-buffer timeout]]
            [clojure.test :refer [deftest is testing run-tests]]
            [onyx api
             [job :refer [add-task]]
             [test-helper :refer [add-test-env-peers! feedback-exception! load-config with-test-env]]]
            [onyx.plugin 
             [s3-output]
             [s3-input]
             [core-async :refer [take-segments! get-core-async-channels]]
             [s3-utils :as s]]
            [onyx.tasks.s3 :as task]
            [onyx.tasks.core-async :as ca]
            [taoensso.timbre :as timbre :refer [debug info warn]])
  (:import [com.amazonaws.services.s3.model S3ObjectSummary S3ObjectInputStream]
           [com.amazonaws.services.s3 AmazonS3Client]
           [com.amazonaws.regions RegionUtils]
           [com.amazonaws.services.s3.model ObjectMetadata]))

(def in-chan (atom nil))
(def in-buffer (atom nil))

(defn inject-in-ch [event lifecycle]
  {:core.async/buffer in-buffer
   :core.async/chan @in-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def serializer-fn 
  (fn [vs] 
    (.getBytes (pr-str vs) "UTF-8")))

(def deserializer-fn
  clojure.edn/read-string)

(defn build-s3-output-job [bucket prefix output-batch-size output-batch-timeout]
  (let [batch-size 500]
    (-> {:workflow [[:in :identity] [:identity :out]]
         :task-scheduler :onyx.task-scheduler/balanced
         :catalog [{:onyx/name :in
                    :onyx/plugin :onyx.plugin.core-async/input
                    :onyx/type :input
                    :onyx/medium :core.async
                    :onyx/batch-size batch-size
                    :onyx/max-peers 1
                    :onyx/doc "Reads segments from a core.async channel"}

                   {:onyx/name :identity
                    :onyx/fn :clojure.core/identity
                    :onyx/type :function
                    :onyx/batch-size batch-size}
                   ;; Add :out task later
                   ]
         :lifecycles [{:lifecycle/task :in
                       :lifecycle/calls ::in-calls}]}
        (add-task (task/s3-output :out
                                  bucket
                                  ::serializer-fn
                                  {:onyx/max-peers 1
                                   :s3/prefix prefix
                                   :s3/region "us-east-1"
                                   :s3/encryption :aes256
                                   :s3/serialize-per-element? true
                                   :onyx/batch-timeout output-batch-timeout
                                   :onyx/batch-size output-batch-size})))))

(def crashed (atom false))
(def n-crashes (atom 0))

;; TODO, use windowing to make sure recovery point is correct
(defn crash-it [segment]
  (when (and (zero? (rand-int 500))
             (<= 10 @n-crashes))
    ;(not @crashed)
    ;(reset! crashed true)
    (swap! n-crashes inc)
    (throw (Exception.)))
  segment)

(defn build-s3-input-job [bucket prefix batch-size batch-timeout]
  (let [batch-settings {:onyx/batch-size batch-size 
                        :onyx/batch-timeout batch-timeout}
        base-job {:workflow [[:in :out]]
                  :catalog []
                  :lifecycles []
                  :windows []
                  :triggers []
                  :flow-conditions []
                  :task-scheduler :onyx.task-scheduler/balanced}]
    (-> base-job
        (add-task (task/s3-input :in
                                  bucket
                                  prefix
                                  ::deserializer-fn
                                  {:onyx/fn ::crash-it
                                   :s3/region "us-east-1"
                                   :onyx/max-peers 1}))
        (add-task (ca/output :out batch-settings 1000000)))))

(deftest s3end-to-end-test
  (let [id (java.util.UUID/randomUUID)
        env-config {:onyx/tenancy-id id
                    :zookeeper/address "127.0.0.1:2188"
                    :zookeeper/server? true
                    :zookeeper.server/port 2188}
        peer-config {:onyx/tenancy-id id
                     :zookeeper/address "127.0.0.1:2188"
                     :onyx.peer/job-scheduler :onyx.job-scheduler/greedy
                     :onyx.messaging.aeron/embedded-driver? true
                     :onyx.messaging/impl :aeron
                     :onyx.messaging/peer-port 40200
                     :onyx.messaging/bind-addr "localhost"}
        encryption :aes256
        new-client (partial s/new-client :region "us-east-1")
        client (new-client)
        bucket (str "onyx-s3-test-" (java.util.UUID/randomUUID))
        prefix (str (java.util.UUID/randomUUID) "/")
        _ (.createBucket ^AmazonS3Client client ^String bucket)]
    (try
      (with-test-env [test-env [3 env-config peer-config]]
        (let [batch-size 50
              n-messages 2000
              _ (reset! in-chan (chan (inc n-messages)))
              _ (reset! in-buffer {})
              input-messages (map (fn [v] {:n v
                                           :some-string1 "This is a string that I will increase the length of to test throughput"
                                           :some-string2 "This is a string that I will increase the length of to test throughput"}) 
                                  (range n-messages))]
          (run! #(>!! @in-chan %) input-messages)
          (close! @in-chan)
          (let [output-job (build-s3-output-job bucket prefix 50000 2000)
                input-job (build-s3-input-job bucket prefix 500 500)
                output-job-id (:job-id (onyx.api/submit-job peer-config output-job))
                _ (feedback-exception! peer-config output-job-id)
                input-job-id (:job-id (onyx.api/submit-job peer-config input-job))
                _ (feedback-exception! peer-config input-job-id)
                {:keys [out]} (get-core-async-channels input-job)]
            ;; TODO, test against window results
            (is (= (set (take-segments! out 5000))
                   (set input-messages))))))
      (finally
       (let [ks (s/get-bucket-keys client bucket)]
         (run! (fn [k]
                 (.deleteObject client bucket k))
               ks)
         (.deleteBucket client bucket))))))
