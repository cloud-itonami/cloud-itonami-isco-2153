(ns telecomeng.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [telecomeng.actor :as actor]
            [telecomeng.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-link! st {:link-id "L-1" :client-id "client-1"
                              :name "rooftop-microwave"
                              :min-margin-db 10 :band-low-mhz 5925 :band-high-mhz 6425})
    st))

(deftest commits-an-in-margin-in-band-budget
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-link-budget :stake :low
                 :link-id "L-1" :margin-db 15 :freq-mhz 6000}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-out-of-band-budget
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-link-budget :stake :low
                 :link-id "L-1" :margin-db 15 :freq-mhz 7000}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-activates-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :activate-link :stake :high
                 :link-id "L-1"}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
