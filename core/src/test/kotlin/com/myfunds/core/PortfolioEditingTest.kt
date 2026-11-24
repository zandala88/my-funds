package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class PortfolioEditingTest {
    private val a = Holding("a", "000001", "测试基金A", order = 0, starred = true)
    private val b = Holding("b", "000002", "测试基金B", "other", order = 1)
    private val c = Holding("c", "000003", "测试基金C", order = 2)
    private val trade = Trade("trade-a", "a", "BUY", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "2026-11-24")
    private val p = Portfolio(holdings = listOf(a,b,c), groups = listOf(Group("default","默认"),Group("other","另一组")), trades = listOf(trade))
    @Test fun exactCustomOrderIsDurableAndDoesNotClearStarsOrFinancialRecords() {
        val changed = PortfolioEditing.reorder(p, listOf("c", "b", "a"))
        assertEquals(listOf("c","b","a"), PortfolioMath.sorted(changed, emptyMap(), "2026-11-24").map { it.id })
        assertTrue(changed.holdings.first { it.id == "a" }.starred)
        assertEquals(p.trades, changed.trades)
        assertEquals(changed, BackupCodec.decode(BackupCodec.encode(changed)))
    }
    @Test fun groupReorderKeepsOtherGroupsInTheirSlotsAndCanMoveAcrossPages() {
        val changed = PortfolioEditing.reorder(p, listOf("c","a"), "default")
        assertEquals(listOf("c","b","a"), changed.holdings.sortedBy { it.order }.map { it.id })
        assertEquals(b, changed.holdings.single { it.id == "b" })
        val many = Portfolio(holdings = (0..54).map { i -> a.copy(id="h$i",code=i.toString().padStart(6,'0'),order=i) })
        val moved = PortfolioEditing.move(many,"h50",49)
        assertEquals("h50", moved.holdings.sortedBy { it.order }[49].id)
    }
    @Test fun bulkDeletionRemovesOnlySelectedFundsAndTheirOwnRecords() {
        val otherTrade = trade.copy(id = "trade-b", holdingId = "b")
        val changed = PortfolioEditing.delete(p.copy(trades = listOf(trade,otherTrade)), setOf("a","c"))
        assertEquals(listOf(b.copy(order = 0)), changed.holdings)
        assertEquals(listOf(otherTrade), changed.trades)
        assertEquals(p.groups, changed.groups)
        assertEquals(p.settings, changed.settings)
        assertTrue(PortfolioEditing.delete(p,setOf("a","b","c")).holdings.isEmpty())
    }
    @Test fun malformedEditsAreRejectedWithoutChangingSourcePortfolio() {
        assertThrows(IllegalArgumentException::class.java) { PortfolioEditing.reorder(p,listOf("a","a","b")) }
        assertThrows(IllegalArgumentException::class.java) { PortfolioEditing.reorder(p,listOf("a")) }
        assertThrows(IllegalArgumentException::class.java) { PortfolioEditing.delete(p,setOf("missing")) }
        assertThrows(IllegalArgumentException::class.java) { PortfolioEditing.move(p,"a",99) }
        assertEquals(listOf(a,b,c), p.holdings)
    }
    @Test fun deletionKeepsDenseRanksSoLaterAdditionsStayAtTheEnd() {
        val changed = PortfolioEditing.delete(p,setOf("a"))
        assertEquals(listOf(0,1), changed.holdings.sortedBy { it.order }.map { it.order })
    }
}
