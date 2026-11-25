package com.myfunds.android

import android.os.Looper
import com.myfunds.core.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EstimationRefreshUiTest {
    @Test fun primaryNavOutageStillObtainsAndPersistsListedMarketEstimate() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val day = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val f = MutableList(40) { "" }; f[1] = "ETF"; f[3] = "102"; f[4] = "100"
        f[30] = day.format(DateTimeFormatter.BASIC_ISO_DATE) + LocalTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("HHmmss"))
        val client = MarketClient { u -> when {
            u.contains("FundMNFInfo") -> "{\"Datas\":null}"
            u.contains("FundMNNBasicInformation") -> """{"Datas":{"FCODE":"510300","SHORTNAME":"沪深300ETF","FTYPE":"指数型-股票","ISLIST":"1","LISTTEXCHMARK":"SH"}}"""
            u.contains("qt.gtimg.cn") -> "v_sh510300=\"${f.joinToString("~")}\";"
            else -> throw IllegalStateException("备用服务不可用")
        } }
        try {
            MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, client)
            MainActivity::class.java.getDeclaredField("portfolio").apply { isAccessible = true }.set(activity,
                Portfolio(holdings = listOf(Holding("h", "510300", "沪深300ETF", shares = BigDecimal("100"), cost = BigDecimal.ONE))))
            MainActivity::class.java.getDeclaredMethod("refreshFunds").apply { isAccessible = true }.invoke(activity)
            val refreshing = MainActivity::class.java.getDeclaredField("refreshing").apply { isAccessible = true }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (refreshing.getBoolean(activity) && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("Refresh did not finish", refreshing.getBoolean(activity))
            val saved = LocalStore(activity).use { it.loadQuotes()["510300"] }
            assertNotNull("Model was never obtained/persisted after primary failure", saved?.model)
            assertEquals(EstimateMethod.MARKET, saved!!.model!!.method)
            assertNull(saved.nav)
            assertEquals(BigDecimal("200.00"), DailyProfitPresentation.forHolding(Holding("h", "510300", "ETF", shares = BigDecimal("100")), saved, day.toString())!!.amount)
        } finally { client.cancelAll(); controller.pause().stop().destroy() }
    }
}
