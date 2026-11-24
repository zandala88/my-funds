package com.myfunds.core

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class OcrBatchTest {
    private val first = HoldingCandidate("测试指数A", "017641", BigDecimal("100.00"), BigDecimal("1.20"), BigDecimal("120.00"), null, emptyList(), "2026-11-20")
    @Test fun mergesSameConfirmedSnapshotWithoutAddingShares() {
        val rows = OcrBatch.merge(listOf(listOf(first), listOf(first.copy(name = "测试指数 OCR", shares = BigDecimal("100.0")))))
        assertEquals(1, rows.size)
        assertEquals(listOf(1, 2), rows.single().screenshots)
        assertEquals(BigDecimal("100.00"), rows.single().candidate.shares)
        assertFalse(rows.single().conflict)
    }
    @Test fun keepsDifferentSnapshotsAndWarnsForSameCode() {
        val rows = OcrBatch.merge(listOf(listOf(first), listOf(first.copy(shares = BigDecimal("200.00")))))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.conflict })
        assertEquals(listOf(BigDecimal("100.00"), BigDecimal("200.00")), rows.map { it.candidate.shares })
    }
    @Test fun doesNotGuessIdentityWithoutCodeAndNeverDropsOverflow() {
        assertEquals(2, OcrBatch.merge(listOf(listOf(first.copy(code = null)), listOf(first.copy(code = null)))).size)
        assertThrows(IllegalArgumentException::class.java) { OcrBatch.merge(List(13) { emptyList() }) }
        assertThrows(IllegalArgumentException::class.java) { OcrBatch.merge(listOf(List(51) { first.copy(code = null) })) }
    }
    @Test fun textReparseDoesNotBorrowFieldsAcrossScreenshots() {
        val rows = OcrBatch.parseText("华夏成长混合A 000001\n持有份额 100" + OcrBatch.TEXT_SEPARATOR + "平均成本1.50\n招商中证白酒指数A 161725\n持有份额200\n平均成本2.00")
        assertEquals(2, rows.size)
        assertNull(rows.first().candidate.cost)
        assertEquals(BigDecimal("2.00"), rows.last().candidate.cost)
        assertEquals(listOf(2), rows.last().screenshots)
    }
    @Test fun singleScreenshotOverflowRequiresSplittingInsteadOfSilentlyDroppingFunds() {
        val text = (1..51).joinToString("\n") { "测试指数A ${it.toString().padStart(6, '0')}\n持有份额100\n平均成本1.20" }
        val error = assertThrows(IllegalArgumentException::class.java) { OcrBatch.parseText(text) }
        assertTrue(error.message.orEmpty().contains("50"))
    }
}
