(ns telecomeng.advisor
  "TelecommunicationsEngineersAdvisor — proposes an RF link operation
  (approve a link budget, activate a link) for a registered
  organization. Swappable mock/llm; the advisor ONLY proposes —
  `telecomeng.governor` checks the link-margin floor and spectrum
  containment independently. Modeled on cloud-itonami-isco-4311's
  advisor.

  A proposal: {:op :approve-link-budget|:activate-link
               :effect :propose :link-id str :margin-db number
               :freq-mhz number :stake kw :confidence n :rationale str}")

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake link-id margin-db freq-mhz] :as request}]
  {:op op
   :effect :propose
   :link-id link-id
   :margin-db margin-db
   :freq-mhz freq-mhz
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a telecommunications engineering advisor. Given a request,
   propose an :op, the :link-id, :margin-db and :freq-mhz, an honest
   :confidence and a :stake. Never call a below-minimum link margin or
   an out-of-band frequency conforming — the governor checks both
   against the registered link parameters.")

(defn- parse-proposal [content]
  (try
    (let [p (read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
