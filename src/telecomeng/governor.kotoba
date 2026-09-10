(ns telecomeng.governor
  "TelecommunicationsEngineersGovernor — the independent safety/
  traceability layer for the ISCO-08 2153 community telecommunications
  engineers actor (itonami actor pattern, ADR-2607011000 / CLAUDE.md
  Actors section). Modeled on cloud-itonami-isco-4311's
  bookkeeping.governor. RF twist: a proposed link margin is checked
  arithmetically against the registered minimum, and a proposed
  carrier frequency is interval containment against the registered
  allocated spectrum band — spectrum allocation is not a suggestion,
  it is a licensed boundary.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance — the organization must be registered.
    2. no-actuation      — proposal :effect must be :propose.
    3. link basis         — an approval must cite a REGISTERED link
                           belonging to this client.
    4. link-margin floor  — the proposed link margin (dB) must be >=
                           the link's registered :min-margin-db
                           (arithmetic comparison, not an estimate).
    5. spectrum containment — the proposed carrier frequency must
                           satisfy band-low <= freq <= band-high
                           against the link's registered allocated
                           band (a licensed boundary, not a
                           suggestion).
  ESCALATION invariants (:escalate? true, human sign-off):
    6. :op :activate-link (RF transmission activation).
    7. low confidence (< `confidence-floor`)."
  (:require [telecomeng.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [request proposal]} client-record l]
  (let [{:keys [op margin-db freq-mhz]} proposal
        approve? (= :approve-link-budget op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and approve? (nil? l))
      (conj {:rule :unknown-link :detail "未登録 link への承認は不可"})

      (and approve? l (not= (:client-id l) (:client-id request)))
      (conj {:rule :link-wrong-client :detail "link が別 client のもの"})

      (and approve? l (number? margin-db) (< margin-db (:min-margin-db l)))
      (conj {:rule :margin-below-minimum
             :detail (str "リンクマージン " margin-db "dB < 登録済み最低要求 "
                          (:min-margin-db l) "dB（マージン算術は見積もりではない）")})

      (and approve? l (number? freq-mhz)
           (or (< freq-mhz (:band-low-mhz l)) (> freq-mhz (:band-high-mhz l))))
      (conj {:rule :out-of-band
             :detail (str "搬送波周波数 " freq-mhz "MHz が登録済み割当帯域 ["
                          (:band-low-mhz l) ", " (:band-high-mhz l)
                          "]MHz の外（スペクトラム割当は免許境界であって提案ではない）")}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `telecomeng.store/Store`. Pure — never mutates
  the store."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        l (some->> (:link-id proposal) (store/link store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record l)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (= :activate-link (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
