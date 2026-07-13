(ns telecomeng.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [telecomeng.store :as store]
            [telecomeng.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Trade"})
    (store/register-link! st {:link-id "L-1" :client-id "client-1"
                              :name "rooftop-microwave"
                              :min-margin-db 10 :band-low-mhz 5925 :band-high-mhz 6425})
    st))

(defn- approve [margin freq]
  {:op :approve-link-budget :effect :propose :link-id "L-1"
   :margin-db margin :freq-mhz freq :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-within-margin-and-band
  (let [st (fresh-store)
        v (governor/check req {} (approve 15 6000) st)]
    (is (:ok? v))))

(deftest ok-at-exact-margin-and-band-edges
  (testing "the margin floor and band boundaries are inclusive"
    (let [st (fresh-store)]
      (is (:ok? (governor/check req {} (approve 10 5925) st)))
      (is (:ok? (governor/check req {} (approve 10 6425) st))))))

(deftest hard-on-margin-below-minimum
  (testing "margin arithmetic is not an estimate"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (approve 5 6000) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :margin-below-minimum (:rule %)) (:violations v))))))

(deftest hard-on-frequency-below-band
  (testing "spectrum allocation is a licensed boundary, not a suggestion"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (approve 15 5900) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :out-of-band (:rule %)) (:violations v))))))

(deftest hard-on-frequency-above-band
  (let [st (fresh-store)
        v (governor/check req {} (assoc (approve 15 6500) :confidence 0.99) st)]
    (is (:hard? v))
    (is (some #(= :out-of-band (:rule %)) (:violations v)))))

(deftest hard-on-unknown-link
  (let [st (fresh-store)
        v (governor/check req {} (assoc (approve 15 6000) :link-id "L-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-link (:rule %)) (:violations v)))))

(deftest hard-on-foreign-link
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {} (approve 15 6000) st)]
      (is (:hard? v))
      (is (some #(= :link-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (approve 15 6000) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (approve 15 6000) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-link-activation
  (let [st (fresh-store)
        v (governor/check req {} {:op :activate-link :effect :propose
                                  :link-id "L-1" :confidence 0.9 :stake :high} st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (approve 15 6000) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
