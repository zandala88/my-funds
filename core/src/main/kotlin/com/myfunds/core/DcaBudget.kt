package com.myfunds.core

import java.math.BigDecimal

/** Budget for one execution day across all plans, independent of catch-up ledger entries. */
data class DailyDcaBudget(val knownAmount: BigDecimal, val plans: Int, val missingPrices: Int) {
    val total: BigDecimal? get() = knownAmount.takeIf { missingPrices == 0 }
}
object DcaBudget {
    fun daily(holdings: List<Holding>, quotes: Map<String, FundQuote>): DailyDcaBudget {
        var amount = BigDecimal.ZERO
        var plans = 0
        var missing = 0
        for (h in holdings) {
            // Matches execution precedence for existing backups containing both fields.
            val shares = h.dcaShares?.takeIf { it > BigDecimal.ZERO }
            val fixedAmount = h.dcaAmount?.takeIf { it > BigDecimal.ZERO }
            if (shares == null && fixedAmount == null) continue
            plans++
            if (shares != null) {
                val nav = quotes[h.code]?.nav?.takeIf { it > BigDecimal.ZERO }
                if (nav == null) missing++ else amount += shares * nav
            } else amount += fixedAmount!!
        }
        return DailyDcaBudget(amount, plans, missing)
    }
}
