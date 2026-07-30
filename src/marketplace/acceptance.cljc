(ns marketplace.acceptance
  "コード決済 — how the BUYER's money arrives, which is the leg
  `marketplace.settlement` assumes has already happened.

  `marketplace.settlement` starts from `:plan/gross-minor`, 'what the
  buyer pays', and describes only the PAYOUT direction: platform →
  seller. A QR / barcode code payment is the opposite direction, buyer →
  platform, and modelling it as one more entry in
  `settlement/payout-rails` would be wrong in a way that compiles: a
  code-payment PSP settles to ONE merchant's bank account, so it can
  never be a per-seller payout destination. The payout leg of a code
  payment is `:bank-transfer` — see `payout-leg-rail`.

  ## Why this rail is custodial and `:x402` is not

  `settleops.rail` records that the x402 rail is `:direct-split`: the
  buyer pays each seller's own treasury directly, in the plan's
  proportions, and the operator is never in the path. A code payment
  CANNOT do that. The PSP knows one merchant, pays one bank account, and
  cannot be told to split at pay time. So the seller's share passes
  through the operator, and this rail makes the operator custodial for
  money it does not own — `:accept/operator-custodial? true`, stated on
  every request rather than left to be discovered.

  That is a regulatory fact, not a style preference: collecting a
  buyer's money and forwarding it to a seller is 収納代行 territory and,
  depending on structure, 資金移動業 under 資金決済法. This namespace does
  not decide whether a given deployment may do it. It makes the flag
  impossible to miss so the question gets asked before code ships.

  ## Nothing here talks to a PSP

  Pure: no clock, no network, no randomness. There is no PSP client in
  this namespace and there will not be one — a vendor-specific API
  client (PayPay, 楽天ペイ, au PAY, …) fails the workspace layer test
  (ADR-2606302300) and belongs in a vendor repo, not in a kotoba-lang
  capability library. `:accept/psp` is therefore a name the DEPLOYMENT
  supplies; this library validates that it is present, never which one
  it is.

  ## The one thing a reader must not get wrong

  A buyer's completion screen is not evidence of payment. Presenting a
  faked 決済完了画面 is the standard attack on code payment, and the
  merchant's own record must come from the PSP. `capture` therefore
  accepts only `attestation-sources` — a webhook the PSP sent, or an
  answer to a query the merchant made — and refuses a buyer-presented
  claim by NAME (`:buyer-presented-evidence`), not as a generic unknown."
  (:require [clojure.string :as str]))

;; ───────────────────────────── rails and modes ─────────────────────────────

(def acceptance-rails
  "Rails on which a BUYER's payment can arrive. Deliberately not the same
  set as `settlement/payout-rails`, and deliberately short: `:code-payment`
  is the one this namespace models. Card and x402 acceptance are real but
  unmodelled here, and an absent rail refuses rather than being waved
  through as 'probably like the others'."
  #{:code-payment})

(def code-modes
  "EMVCo's two interaction modes, and the reason the distinction is not
  cosmetic.

    :mpm-static   the merchant shows a fixed code — a placard, a sticker —
                  that carries NO amount. The BUYER types the amount. The
                  amount is therefore not bound to the code and cannot be
                  trusted until the PSP states it.
    :mpm-dynamic  the merchant shows a per-order code carrying the amount
                  and an order reference. The amount is bound.
    :cpm          the buyer shows a one-time code and the merchant's
                  terminal scans it. The amount is bound, and the code is
                  short-lived by construction."
  #{:mpm-static :mpm-dynamic :cpm})

(def ^:private amount-bound-modes
  #{:mpm-dynamic :cpm})

(defn amount-bound-to-code?
  "Is the amount carried by the code itself? False for `:mpm-static`,
  where the buyer types it — the underpayment surface this namespace
  exists to make visible."
  [mode]
  (contains? amount-bound-modes mode))

(defn payout-leg-rail
  "Which `settlement/payout-rails` rail carries the seller's share of
  money accepted on `rail`.

  For `:code-payment` this is `:bank-transfer`: the PSP settles to the
  merchant's bank account, and the seller is paid from there. This
  function exists so nobody writes `:code-payment` into a
  `payout-destination` — that is refused as an unknown rail there, and
  refused by name in `settleops.rail`."
  [rail]
  (when (= :code-payment rail) :bank-transfer))

;; ───────────────────────────── payment request ─────────────────────────────

(def capture-states
  "Lifecycle of one acceptance request. `:expired` and `:failed` are
  distinct terminal states, not a shared 'not paid' — a code that ran out
  of time and a payment the PSP declined need different answers to the
  buyer."
  #{:requested :captured :expired :failed :cancelled :refunded})

(def capture-transitions
  "Allowed transitions, as an explicit table (`kotoba.okaimono/transitions`
  style). Note what is ABSENT: nothing leads from `:expired` or `:failed`
  back to `:captured`. A late webhook for an expired request is a
  reconciliation problem for a human, not a state this table will enter."
  {:requested #{:captured :expired :failed :cancelled}
   :captured  #{:refunded}
   :expired   #{}
   :failed    #{}
   :cancelled #{}
   :refunded  #{}})

(defn payment-request
  "Ask a buyer to pay `expected-minor` on a code-payment rail.

  `expected-minor` is what the ORDER says is owed — always required, in
  the currency's minor units, and never inferred from the code. For a
  `:mpm-static` request the code cannot carry it, so the amount the buyer
  actually sends may differ; that is what `settlement-status` reports
  instead of assuming.

  No code payload is stored. What a scannable code contains is a PSP
  credential-shaped string, and this record keeps only the PSP's own
  reference to it (`:accept/reference`) — enough to reconcile, not enough
  to present someone else's payment code as your own."
  [{:keys [order rail mode psp expected-minor currency expires-at reference]}]
  {:accept/order              (str order)
   :accept/rail               rail
   :accept/mode               mode
   :accept/psp                (some-> psp str str/trim not-empty)
   :accept/expected-minor     expected-minor
   :accept/currency           currency
   :accept/expires-at         expires-at
   :accept/reference          (some-> reference str str/trim not-empty)
   :accept/state              :requested
   :accept/amount-bound?      (amount-bound-to-code? mode)
   :accept/operator-custodial? true
   :accept/payout-leg         (payout-leg-rail rail)})

(defn payment-request-errors
  "Everything that must be true before a buyer is shown a code."
  [r]
  (vec
   (concat
    (when (str/blank? (str (:accept/order r)))
      [{:accept.error/code :missing-order}])
    (when-not (contains? acceptance-rails (:accept/rail r))
      [{:accept.error/code :unknown-acceptance-rail
        :accept.error/detail (pr-str (:accept/rail r))}])
    (when-not (contains? code-modes (:accept/mode r))
      [{:accept.error/code :unknown-code-mode
        :accept.error/detail (pr-str (:accept/mode r))}])
    (when (str/blank? (str (:accept/psp r)))
      [{:accept.error/code :missing-psp}])
    (when-not (and (integer? (:accept/expected-minor r))
                   (pos? (:accept/expected-minor r)))
      [{:accept.error/code :invalid-expected-amount}])
    (when-not (and (string? (:accept/currency r))
                   (re-matches #"^[A-Z]{3}$" (str (:accept/currency r))))
      [{:accept.error/code :invalid-currency}])
    ;; A one-time code with no stated expiry is a one-time code in name
    ;; only. A static placard genuinely has none, so it is exempt — and
    ;; only it.
    (when (and (contains? amount-bound-modes (:accept/mode r))
               (str/blank? (str (:accept/expires-at r))))
      [{:accept.error/code :missing-expiry}]))))

(defn covers-plan?
  "Does this request ask the buyer for exactly what the settlement plan
  says the buyer owes (goods plus the operator's fixed fee)?

  Checked against `:plan/buyer-charge-minor` rather than
  `:plan/gross-minor`, because the fixed fee is charged to the buyer on
  top of goods — asking for gross would silently absorb the operator's
  own fee, and asking for more than the plan states is overcharging."
  [r plan]
  (and (= (:accept/expected-minor r) (:plan/buyer-charge-minor plan))
       (= (:accept/currency r) (:plan/currency plan))))

(defn expired?
  "Has the code's window passed at `now`? A request with no stated expiry
  (`:mpm-static`) never expires. `now` comes from the caller."
  [r now]
  (let [at (:accept/expires-at r)]
    (boolean (and (not (str/blank? (str at)))
                  (pos? (compare (str now) (str at)))))))

(defn advance
  "Move a request to `to` when the transition table allows it; nil
  otherwise (an illegal transition is a refusal, not an exception)."
  [r to]
  (when (contains? (get capture-transitions (:accept/state r) #{}) to)
    (assoc r :accept/state to)))

;; ───────────────────────────── PSP attestation ─────────────────────────────

(def attestation-sources
  "Where a claim that money arrived may come from: a webhook the PSP
  sent, or the PSP's answer to a query the merchant made. Both are the
  PSP speaking."
  #{:webhook :api-query})

(def buyer-presented-sources
  "Sources that are the BUYER speaking, refused by name so the log says
  what happened. A screenshot of a completion screen is trivially faked
  and is the standard attack on this rail."
  #{:buyer-screen :buyer-screenshot :buyer-claim})

(defn psp-attestation
  "The PSP's own statement that a payment settled."
  [{:keys [psp transaction-id amount-minor currency attested-at source]}]
  {:psp/name           (some-> psp str str/trim not-empty)
   :psp/transaction-id (some-> transaction-id str str/trim not-empty)
   :psp/amount-minor   amount-minor
   :psp/currency       currency
   :psp/attested-at    attested-at
   :psp/source         source})

(defn capture-errors
  "Why this attestation may not be recorded against this request.

  The expiry check uses the attestation's OWN `:psp/attested-at`, not a
  clock: whether the payment beat the deadline is a question about when
  it happened, and answering it with 'now' would let a request pass or
  fail depending on when someone got around to processing the webhook."
  [r a]
  (vec
   (concat
    (when-not (= :requested (:accept/state r))
      [{:accept.error/code :not-awaiting-capture
        :accept.error/detail (pr-str (:accept/state r))}])
    (cond
      (contains? buyer-presented-sources (:psp/source a))
      [{:accept.error/code :buyer-presented-evidence
        :accept.error/detail (pr-str (:psp/source a))}]

      (not (contains? attestation-sources (:psp/source a)))
      [{:accept.error/code :unattested-source
        :accept.error/detail (pr-str (:psp/source a))}])
    (when (str/blank? (str (:psp/transaction-id a)))
      [{:accept.error/code :missing-psp-transaction-id}])
    (when-not (and (some? (:psp/name a))
                   (= (str (:psp/name a)) (str (:accept/psp r))))
      [{:accept.error/code :psp-mismatch}])
    (when-not (= (:psp/currency a) (:accept/currency r))
      [{:accept.error/code :currency-mismatch}])
    (when-not (and (integer? (:psp/amount-minor a))
                   (pos? (:psp/amount-minor a)))
      [{:accept.error/code :invalid-attested-amount}])
    (when (str/blank? (str (:psp/attested-at a)))
      [{:accept.error/code :missing-attested-at}])
    (when (expired? r (:psp/attested-at a))
      [{:accept.error/code :captured-after-expiry
        :accept.error/detail (str (:psp/attested-at a) " > " (:accept/expires-at r))}])
    ;; A code that BOUND the amount and settled for a different one did
    ;; not settle the payment that was authorised. Only `:mpm-static`,
    ;; where the buyer typed the figure, may differ — and there the
    ;; difference is reported, not accepted silently.
    (when (and (:accept/amount-bound? r)
               (integer? (:psp/amount-minor a))
               (not= (:psp/amount-minor a) (:accept/expected-minor r)))
      [{:accept.error/code :bound-amount-mismatch
        :accept.error/detail (str "expected=" (:accept/expected-minor r)
                                  " attested=" (:psp/amount-minor a))}]))))

(defn capture
  "Record a PSP attestation against a request. nil when `capture-errors`
  has anything to say — the refusal is the point, and the caller asks for
  the reasons explicitly."
  [r a]
  (when (empty? (capture-errors r a))
    (assoc r
           :accept/state          :captured
           :accept/captured-minor (:psp/amount-minor a)
           :accept/captured-at    (:psp/attested-at a)
           :accept/psp-transaction (:psp/transaction-id a)
           :accept/attested-by    (:psp/source a))))

(defn settlement-status
  "What arrived against what was asked, in `settleops.rail/reconcile`'s
  vocabulary so the two layers describe money with the same four words:
  `:settled` / `:short` / `:over` / `:missing`.

  `:over` is reported rather than pocketed. On a `:mpm-static` code an
  overpayment is a buyer who typed one zero too many, and treating it as
  'settled, with extra' keeps money that must be refunded."
  [r]
  (let [want (:accept/expected-minor r)
        got  (:accept/captured-minor r)]
    {:order    (:accept/order r)
     :expected want
     :captured got
     :status   (cond
                 (not= :captured (:accept/state r)) :missing
                 (nil? got)   :missing
                 (= got want) :settled
                 (< got want) :short
                 :else        :over)}))

(defn releasable-to-settlement?
  "May a settlement plan be released against this acceptance? Only when
  the exact expected amount was captured and attested by the PSP.

  `:short` must not release — a partially paid order that pays sellers in
  full pays them out of the operator's own money — and neither must
  `:over`, which owes the buyer a refund first."
  [r]
  (= :settled (:status (settlement-status r))))

;; ───────────────────────────── refunds ─────────────────────────────

(defn refund-instruction
  "How to give a code payment back: through the PSP, against the ORIGINAL
  transaction.

  Not by bank transfer. A code payment refunded out-of-band leaves the
  PSP's record saying the buyer paid and nothing saying they were repaid,
  which is both an accounting break and, for a buyer whose wallet balance
  never returns, an unresolved complaint. `:refund/rail` is therefore
  always the acceptance rail, and the original transaction id is
  mandatory — nil without it.

  Refuses (nil) an unattributed refund: `requested-by` must name someone,
  the same discipline `settlement/resolve-dispute` applies to disputes."
  [r {:keys [amount-minor reason requested-by requested-at]}]
  (when (and (= :captured (:accept/state r))
             (not (str/blank? (str (:accept/psp-transaction r))))
             (not (str/blank? (str requested-by)))
             (integer? amount-minor)
             (pos? amount-minor)
             (<= amount-minor (:accept/captured-minor r 0)))
    {:refund/rail                 (:accept/rail r)
     :refund/psp                  (:accept/psp r)
     :refund/original-transaction (:accept/psp-transaction r)
     :refund/order                (:accept/order r)
     :refund/amount-minor         amount-minor
     :refund/currency             (:accept/currency r)
     :refund/partial?             (< amount-minor (:accept/captured-minor r))
     :refund/via                  :psp-original-transaction
     :refund/reason               reason
     :refund/requested-by         requested-by
     :refund/requested-at         requested-at}))
