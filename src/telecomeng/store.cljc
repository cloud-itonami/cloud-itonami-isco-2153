(ns telecomeng.store
  "SSoT for the ISCO-08 2153 community telecommunications engineers
  actor (itonami actor pattern, ADR-2607011000 / CLAUDE.md Actors
  section). Modeled on cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client — a registered organization (:client-id, :name)
    link   — a registered RF link {:link-id :client-id :name
             :min-margin-db number :band-low-mhz number
             :band-high-mhz number}. `:min-margin-db` is the
             registered minimum acceptable link margin (received
             power above receiver sensitivity); `:band-low-mhz`/
             `:band-high-mhz` is the registered allocated spectrum
             interval a proposed carrier frequency must fall within.
    record — a committed operating record (approved link budget) —
             written ONLY via commit-record!.
    ledger — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (link [s link-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-link! [s l])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (link [_ link-id] (get-in @a [:links link-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-link! [s l]
    (swap! a assoc-in [:links (:link-id l)] l) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :links {} :records [] :ledger []}
                                   seed)))))
