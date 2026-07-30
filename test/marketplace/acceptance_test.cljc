(ns marketplace.acceptance-test
  (:require [clojure.test :refer [deftest is testing]]
            [marketplace.acceptance :as accept]
            [marketplace.settlement :as st]))

(def psp "psp.deployment-named")

(defn- req
  [& {:keys [mode expected expires order]
      :or   {mode :mpm-dynamic expected 38500
             expires "2026-07-30T10:05:00Z" order "order-1"}}]
  (accept/payment-request {:order order :rail :code-payment :mode mode
                           :psp psp :expected-minor expected :currency "JPY"
                           :expires-at expires :reference "psp-ref-1"}))

(defn- att
  [& {:keys [amount at source txid currency]
      :or   {amount 38500 at "2026-07-30T10:01:00Z" source :webhook
             txid "psp-tx-1" currency "JPY"}}]
  (accept/psp-attestation {:psp psp :transaction-id txid :amount-minor amount
                           :currency currency :attested-at at :source source}))

(defn- codes [errors]
  (set (map :accept.error/code errors)))

;; ───────────────────────── rail placement ─────────────────────────

(deftest code-payment-is-not-a-payout-rail
  (testing "the acceptance set and the payout set are not the same set"
    (is (contains? accept/acceptance-rails :code-payment))
    (is (not (contains? st/payout-rails :code-payment))))
  (testing "a payout destination on the code-payment rail is refused"
    (let [d (st/payout-destination {:seller "merchant.a" :rail :code-payment
                                    :address "acct" :verified? true})]
      (is (contains? (set (map :settlement.error/code (st/payout-destination-errors d)))
                     :unknown-rail))))
  (testing "the seller's share of a code payment travels by bank transfer"
    (is (= :bank-transfer (accept/payout-leg-rail :code-payment)))
    (is (contains? st/payout-rails (accept/payout-leg-rail :code-payment)))
    (is (nil? (accept/payout-leg-rail :x402))
        "only code-payment has its payout leg redirected here")))

(deftest this-rail-is-custodial-and-says-so
  (testing "unlike x402 direct-split, funds pass through the operator"
    (is (true? (:accept/operator-custodial? (req))))
    (is (= :bank-transfer (:accept/payout-leg (req))))))

;; ───────────────────────── request validation ─────────────────────────

(deftest a-well-formed-request-has-no-errors
  (is (= [] (accept/payment-request-errors (req)))))

(deftest requests-fail-closed
  (testing "each missing or wrong field is named"
    (is (contains? (codes (accept/payment-request-errors (req :order "")))
                   :missing-order))
    (is (contains? (codes (accept/payment-request-errors (req :expected 0)))
                   :invalid-expected-amount))
    (is (contains? (codes (accept/payment-request-errors (req :expected 38500.5)))
                   :invalid-expected-amount))
    (is (contains? (codes (accept/payment-request-errors
                           (accept/payment-request {:order "o" :rail :paypay :mode :mpm-dynamic
                                                    :psp psp :expected-minor 100 :currency "JPY"
                                                    :expires-at "t"})))
                   :unknown-acceptance-rail)
        "a vendor name is not a rail")
    (is (contains? (codes (accept/payment-request-errors (req :mode :qr)))
                   :unknown-code-mode))
    (is (contains? (codes (accept/payment-request-errors
                           (accept/payment-request {:order "o" :rail :code-payment
                                                    :mode :cpm :psp "  "
                                                    :expected-minor 100 :currency "JPY"
                                                    :expires-at "t"})))
                   :missing-psp))
    (is (contains? (codes (accept/payment-request-errors
                           (accept/payment-request {:order "o" :rail :code-payment
                                                    :mode :cpm :psp psp
                                                    :expected-minor 100 :currency "jpy"
                                                    :expires-at "t"})))
                   :invalid-currency))))

(deftest a-one-time-code-must-state-its-expiry
  (testing "dynamic and CPM codes are one-time; without an expiry they are not"
    (is (contains? (codes (accept/payment-request-errors (req :mode :mpm-dynamic :expires nil)))
                   :missing-expiry))
    (is (contains? (codes (accept/payment-request-errors (req :mode :cpm :expires nil)))
                   :missing-expiry)))
  (testing "a printed placard genuinely has no expiry, and only it is exempt"
    (is (= [] (accept/payment-request-errors (req :mode :mpm-static :expires nil))))
    (is (false? (accept/expired? (req :mode :mpm-static :expires nil)
                                "2099-01-01T00:00:00Z")))))

(deftest static-codes-do-not-bind-the-amount
  (is (false? (accept/amount-bound-to-code? :mpm-static)))
  (is (true? (accept/amount-bound-to-code? :mpm-dynamic)))
  (is (true? (accept/amount-bound-to-code? :cpm)))
  (is (false? (:accept/amount-bound? (req :mode :mpm-static)))))

(deftest a-request-asks-for-exactly-what-the-plan-says-the-buyer-owes
  (let [p (st/settlement-plan
           {:lines [(st/basket-line {:seller "merchant.a" :offer "o" :amount-minor 38000 :qty 1})]
            :currency "JPY"
            :fee-schedule (st/fee-schedule {:commission-bps 1000 :fixed-minor 500})
            :operator "merchant.operator"})]
    (is (= 38500 (:plan/buyer-charge-minor p)))
    (testing "buyer charge, not gross — asking for gross absorbs the operator's own fee"
      (is (true? (accept/covers-plan? (req :expected 38500) p)))
      (is (false? (accept/covers-plan? (req :expected 38000) p))))
    (testing "a currency mismatch is not covered either"
      (is (false? (accept/covers-plan? (req :expected 38500) (assoc p :plan/currency "USD")))))))

;; ───────────────────────── capture ─────────────────────────

(deftest a-psp-attestation-captures
  (let [c (accept/capture (req) (att))]
    (is (= :captured (:accept/state c)))
    (is (= 38500 (:accept/captured-minor c)))
    (is (= "psp-tx-1" (:accept/psp-transaction c)))
    (is (= :webhook (:accept/attested-by c)))
    (is (= :settled (:status (accept/settlement-status c))))
    (is (true? (accept/releasable-to-settlement? c)))))

(deftest a-buyers-completion-screen-is-not-evidence-of-payment
  (doseq [source [:buyer-screen :buyer-screenshot :buyer-claim]]
    (let [errs (accept/capture-errors (req) (att :source source))]
      (is (contains? (codes errs) :buyer-presented-evidence)
          (str "source=" source))
      (is (nil? (accept/capture (req) (att :source source)))))))

(deftest an-unnamed-source-is-refused-too
  (testing "only the PSP speaking counts, and an unrecognised source is not a default"
    (is (contains? (codes (accept/capture-errors (req) (att :source nil)))
                   :unattested-source))
    (is (contains? (codes (accept/capture-errors (req) (att :source :operator-typed-it-in)))
                   :unattested-source))
    (is (= accept/attestation-sources #{:webhook :api-query}))))

(deftest capture-fails-closed-on-every-mismatch
  (is (contains? (codes (accept/capture-errors (req) (att :txid "")))
                 :missing-psp-transaction-id))
  (is (contains? (codes (accept/capture-errors
                         (req)
                         (accept/psp-attestation {:psp "psp.someone-else"
                                                  :transaction-id "t" :amount-minor 38500
                                                  :currency "JPY" :attested-at "2026-07-30T10:01:00Z"
                                                  :source :webhook})))
                 :psp-mismatch))
  (is (contains? (codes (accept/capture-errors (req) (att :currency "USD")))
                 :currency-mismatch))
  (is (contains? (codes (accept/capture-errors (req) (att :amount 0)))
                 :invalid-attested-amount))
  (is (contains? (codes (accept/capture-errors (req) (att :at "")))
                 :missing-attested-at)))

(deftest expiry-is-judged-by-when-the-payment-happened
  (testing "a webhook processed late does not fail a payment that beat the deadline"
    (let [r (req :expires "2026-07-30T10:05:00Z")]
      (is (= :captured (:accept/state (accept/capture r (att :at "2026-07-30T10:04:59Z")))))))
  (testing "a payment made after the code expired is refused, however promptly it arrives"
    (let [errs (accept/capture-errors (req :expires "2026-07-30T10:05:00Z")
                                      (att :at "2026-07-30T10:05:01Z"))]
      (is (contains? (codes errs) :captured-after-expiry)))))

(deftest a-bound-amount-must-match-and-a-typed-one-is-reported
  (testing "the code carried the amount, so a different amount is a different payment"
    (is (contains? (codes (accept/capture-errors (req :mode :mpm-dynamic) (att :amount 100)))
                   :bound-amount-mismatch))
    (is (nil? (accept/capture (req :mode :cpm) (att :amount 100)))))
  (testing "on a static code the buyer typed it, so a shortfall is captured and REPORTED"
    (let [c (accept/capture (req :mode :mpm-static :expires nil) (att :amount 3850))]
      (is (= :captured (:accept/state c)))
      (is (= {:order "order-1" :expected 38500 :captured 3850 :status :short}
             (accept/settlement-status c)))
      (is (false? (accept/releasable-to-settlement? c))
          "paying sellers in full on a short payment pays them from the operator's own money")))
  (testing "an overpayment is reported, not pocketed"
    (let [c (accept/capture (req :mode :mpm-static :expires nil) (att :amount 385000))]
      (is (= :over (:status (accept/settlement-status c))))
      (is (false? (accept/releasable-to-settlement? c))))))

(deftest an-uncaptured-request-is-missing-not-zero
  (let [r (req)]
    (is (= :missing (:status (accept/settlement-status r))))
    (is (false? (accept/releasable-to-settlement? r)))))

;; ───────────────────────── transitions ─────────────────────────

(deftest expired-and-failed-are-terminal
  (let [r (req)]
    (is (= :expired (:accept/state (accept/advance r :expired))))
    (is (= :failed (:accept/state (accept/advance r :failed))))
    (testing "a late webhook cannot walk an expired request into :captured"
      (is (nil? (accept/advance (accept/advance r :expired) :captured)))
      (is (nil? (accept/advance (accept/advance r :failed) :captured))))
    (testing "capture itself refuses a request that is no longer awaiting one"
      (is (contains? (codes (accept/capture-errors (accept/advance r :expired) (att)))
                     :not-awaiting-capture))))
  (testing "capture is not re-enterable"
    (let [c (accept/capture (req) (att))]
      (is (nil? (accept/advance c :captured)))
      (is (contains? (codes (accept/capture-errors c (att :txid "psp-tx-2")))
                     :not-awaiting-capture)))))

;; ───────────────────────── refunds ─────────────────────────

(deftest a-code-payment-is-refunded-through-the-psp
  (let [c (accept/capture (req) (att))
        r (accept/refund-instruction c {:amount-minor 38500 :reason "未着"
                                        :requested-by "jun@gftd.group"
                                        :requested-at "2026-08-01T00:00:00Z"})]
    (is (= :code-payment (:refund/rail r))
        "not :bank-transfer — an out-of-band refund leaves the PSP saying the buyer paid")
    (is (= "psp-tx-1" (:refund/original-transaction r)))
    (is (= :psp-original-transaction (:refund/via r)))
    (is (false? (:refund/partial? r)))
    (is (= "jun@gftd.group" (:refund/requested-by r)))))

(deftest refunds-fail-closed
  (let [c (accept/capture (req) (att))]
    (testing "unattributed"
      (is (nil? (accept/refund-instruction c {:amount-minor 100 :requested-by ""}))))
    (testing "more than was captured"
      (is (nil? (accept/refund-instruction c {:amount-minor 38501 :requested-by "jun"}))))
    (testing "not a positive integer"
      (is (nil? (accept/refund-instruction c {:amount-minor 0 :requested-by "jun"})))
      (is (nil? (accept/refund-instruction c {:amount-minor 100.5 :requested-by "jun"}))))
    (testing "nothing was captured"
      (is (nil? (accept/refund-instruction (req) {:amount-minor 100 :requested-by "jun"}))))
    (testing "a partial refund is marked as one"
      (is (true? (:refund/partial? (accept/refund-instruction
                                    c {:amount-minor 500 :requested-by "jun"})))))))
