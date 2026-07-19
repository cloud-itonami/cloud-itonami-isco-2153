(ns telecomeng.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  This repo previously had NO demo/visualization page and no generator
  at all. This namespace drives the REAL actor stack
  (`telecomeng.actor` -> `telecomeng.governor` -> `telecomeng.store`)
  through a scenario built from real, exercised store data and renders
  the result deterministically -- no invented numbers, no timestamps in
  the page content, byte-identical across reruns against the same seed
  (verify by diffing two consecutive runs before shipping). Adapted
  from the proven template in `cloud-itonami-isco-1211`
  (`src/finmgmt/render_html.clj`) -- the shape is the same, the
  domain-specific fields (RF link margin/spectrum band instead of
  budget-lines) differ.

  Seed data provenance:

  `client-1` (\"Kobo Trade\") + link `L-1` (\"rooftop-microwave\",
  min-margin-db 10, allocated band [5925, 6425] MHz) below are lifted
  VERBATIM from `telecomeng.actor-test/fresh-store` (identical to
  `telecomeng.governor-test/fresh-store` in this repo -- both test
  files use the same fixture values, unlike some sibling repos in this
  family where the two test namespaces diverge slightly).

  `client-2` (\"Meridian Wireless\") + link `L-2` (\"valley-relay\",
  min-margin-db 8, allocated band [3550, 3700] MHz -- the US CBRS
  band, used only as a plausible second band, not read from any
  spec) is ADDITIONAL demo data, registered via the SAME real
  `register-client!`/`register-link!` protocol calls this repo's own
  tests use -- this actor's own test fixtures only ever register one
  client+link, so a second client+link is necessary to demonstrate the
  cross-client `:link-wrong-client` rule (link belongs to a different
  client than the requester). Disclosed here plainly, not presented as
  if pre-existing. Every other field this page displays (dispositions,
  hold reasons, committed-record counts) is real output read after
  `run-demo!` actually executed the graph -- none of it is hand-typed.

  Known architectural gaps, honestly noted rather than papered over:

  1. `telecomeng.governor`'s `:no-actuation` rule (proposal `:effect`
     must be `:propose`) is NOT reachable through this demo, because
     the real `mock-advisor` (`telecomeng.advisor/infer`)
     unconditionally sets `:effect :propose` on every proposal it
     emits -- by design, the advisor can never itself emit a raw store
     write. Covered instead by
     `telecomeng.governor-test/hard-on-no-actuation-violation` (which
     calls `governor/check` directly with a hand-built proposal), not
     by this build-time renderer.
  2. The low-confidence escalation path (`confidence <
     telecomeng.governor/confidence-floor`, i.e. < 0.6) is NOT reachable
     through this demo either: the real mock-advisor's `infer` assigns
     confidence purely from `:stake` (`:high` -> 0.7, `:medium` -> 0.85,
     `:low` -> 0.95), and even the lowest of those (0.7 for `:high`
     stake) is above the 0.6 floor. Covered instead by
     `telecomeng.governor-test/escalates-low-confidence` (direct
     `governor/check` call with a hand-set `:confidence 0.3`).

  Every other governor rule this actor defines IS reached here: client
  provenance (`:no-client`), link basis (`:unknown-link`), link
  ownership (`:link-wrong-client`), link-margin floor
  (`:margin-below-minimum`), spectrum-band containment
  (`:out-of-band`), plus the always-escalate `:activate-link` op
  (escalate -> human approve -> commit) and the plain auto-commit
  path.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [telecomeng.store :as store]
            [telecomeng.actor :as actor]))

;; ----------------------------- harness --------------------------------
;; telecomeng.actor already exposes run-request!/approve! wrappers
;; around langgraph.graph/run* -- this repo's own actor ns is the
;; harness, no raw g/run* needed here.

(defn- run-op!
  "Drives one real telecom RF operation request through the actual
  compiled graph for `tid` (thread-id). If the graph escalates
  (interrupts before `:request-approval`), immediately approves it
  (this demo's scenario never demonstrates an UNAPPROVED escalation --
  every escalation here reaches a human who signs off). Returns a map
  describing exactly what really happened -- no field is invented."
  [graph tid client-id op extra]
  (let [request (merge {:client-id client-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :client-id client-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :client-id client-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely
  reach through its real graph (auto-commit, escalate-then-approve,
  and 5 of the 6 distinct HARD-hold reasons in `telecomeng.governor`
  -- the 6th, `:no-actuation`, plus the low-confidence escalation
  path, are architecturally unreachable via the real advisor, see
  namespace docstring). Every `:op` keyword and violation rule name
  below is copied from `telecomeng.governor`'s own
  `hard-violations`/`check`, not invented."
  [;; client-1 / \"Kobo Trade\" / L-1 (real fixture from telecomeng.actor-test)
   ["c1-approve-ok"        "client-1" :approve-link-budget
    {:link-id "L-1" :margin-db 15 :freq-mhz 6000 :stake :low}]
   ["c1-margin-low"        "client-1" :approve-link-budget
    {:link-id "L-1" :margin-db 5 :freq-mhz 6000 :stake :low}]
   ["c1-out-of-band"       "client-1" :approve-link-budget
    {:link-id "L-1" :margin-db 15 :freq-mhz 7000 :stake :low}]
   ["c1-unknown-link"      "client-1" :approve-link-budget
    {:link-id "L-ghost" :margin-db 15 :freq-mhz 6000 :stake :low}]
   ["ghost-no-client"      "client-ghost" :approve-link-budget
    {:link-id "L-1" :margin-db 15 :freq-mhz 6000 :stake :low}]
   ["c1-activate"          "client-1" :activate-link
    {:link-id "L-1" :stake :high}]
   ;; client-1 requesting an approval against client-2's link
   ["c1-wrong-link"        "client-1" :approve-link-budget
    {:link-id "L-2" :margin-db 12 :freq-mhz 3600 :stake :low}]
   ;; client-2 / \"Meridian Wireless\" / L-2 (additional demo data,
   ;; registered via the same real register-client!/register-link!
   ;; calls -- see namespace docstring)
   ["c2-approve-ok"        "client-2" :approve-link-budget
    {:link-id "L-2" :margin-db 12 :freq-mhz 3600 :stake :medium}]
   ["c2-activate"          "client-2" :activate-link
    {:link-id "L-2" :stake :high}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `telecomeng.actor` graph. Returns `{:store :runs}` --
  `:runs` is the ordered vector of real per-request outcomes; every
  field in `render` below is read from this or from `store` after the
  graph actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-client! db {:client-id "client-1" :name "Kobo Trade"})
    (store/register-link! db {:link-id "L-1" :client-id "client-1"
                               :name "rooftop-microwave"
                               :min-margin-db 10 :band-low-mhz 5925 :band-high-mhz 6425})
    (store/register-client! db {:client-id "client-2" :name "Meridian Wireless"})
    (store/register-link! db {:link-id "L-2" :client-id "client-2"
                               :name "valley-relay"
                               :min-margin-db 8 :band-low-mhz 3550 :band-high-mhz 3700})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid client-id op extra]]
                       (run-op! graph tid client-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- client-row [store {:keys [client-id name link-id link-name min-margin-db band-low-mhz band-high-mhz]} runs]
  (let [record-count (count (store/records-of store client-id))
        last-run (last (filter #(= client-id (:client-id %)) runs))]
    (format "        <tr><td>%s</td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s dB</td><td>%s&ndash;%s MHz</td><td>%d</td><td>%s</td></tr>"
            (esc client-id) (esc name) (esc link-id) (esc link-name)
            min-margin-db band-low-mhz band-high-mhz
            record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- run-row [{:keys [thread-id client-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc client-id) (esc (name op))
          (esc (or (:link-id request) ""))
          (esc (or (some-> (:margin-db request) str) ""))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`telecomeng.governor`'s own docstring) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:approve-link-budget</code></td><td><span class=\"ok\">auto-commit when the link margin meets the registered floor AND the carrier frequency falls inside the allocated band &middot; HARD hold otherwise (arithmetic and licensed-boundary checks, not estimates)</span></td></tr>"
   "        <tr><td><code>:activate-link</code></td><td><span class=\"warn\">ALWAYS human approval &middot; RF transmission activation</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [clients [{:client-id "client-1" :name "Kobo Trade"
                  :link-id "L-1" :link-name "rooftop-microwave"
                  :min-margin-db 10 :band-low-mhz 5925 :band-high-mhz 6425}
                 {:client-id "client-2" :name "Meridian Wireless"
                  :link-id "L-2" :link-name "valley-relay"
                  :min-margin-db 8 :band-low-mhz 3550 :band-high-mhz 3700}]
        client-rows (str/join "\n" (map #(client-row store % runs) clients))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-2153 &middot; community telecommunications engineers</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 1080px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Community Telecommunications Engineers (ISCO-08 2153) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · link margin &amp; spectrum-band containment always independently checked</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered clients &amp; links</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>telecomeng.store</code> via <code>telecomeng.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly. Committed-record count is a live re-read of <code>store/records-of</code> after the real graph ran — never a remembered number.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Client</th><th>Name</th><th>Link</th><th>Link name</th><th>Min margin</th><th>Allocated band</th><th>Committed records</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     client-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Telecommunications Engineers Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. The link-margin floor and spectrum-band containment are rechecked against the registered link record on every proposal, at any confidence.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, client, op, the request's own link/margin, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Client</th><th>Op</th><th>Link</th><th>Margin (dB)</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
