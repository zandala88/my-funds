package com.myfunds.android

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import com.myfunds.core.*
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class HoldingsEditorUiTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun button(a: MainActivity, text: String) = views(a.window.decorView).filterIsInstance<Button>().firstOrNull { it.text.toString() == text }
    private fun check(a: MainActivity, code: String) = views(a.window.decorView).filterIsInstance<CheckBox>().first { it.contentDescription == "选择基金：$code" }
    private fun seed(a: MainActivity) {
        val holdings = (1..3).map { i -> Holding("h$i",i.toString().padStart(6,'0'),"测试基金$i",order=i-1,starred=i==1) }
        val p = Portfolio(holdings=holdings,trades=holdings.map { Trade("t-${it.id}",it.id,"BUY",BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO,"2026-11-24") })
        LocalStore(a).use { it.save(p) }
        MainActivity::class.java.getDeclaredField("portfolio").apply { isAccessible=true }.set(a,p)
        MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(a)
    }
    @Test fun homepageEditCanReorderThenDeleteOnlyConfirmedSelection() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();seed(a)
        assertNotNull("Homepage needs direct editing entry",button(a,"编辑"));button(a,"编辑")!!.performClick()
        val up=views(a.window.decorView).first { it.contentDescription == "上移：000002" };up.performClick()
        LocalStore(a).use { assertEquals(listOf("h2","h1","h3"),PortfolioMath.sorted(it.load(),emptyMap(),"2026-11-24").map { h->h.id }) }
        check(a,"000002").performClick();check(a,"000003").performClick()
        button(a,"删除 2")!!.performClick()
        var dialog=ShadowAlertDialog.getLatestAlertDialog()
        LocalStore(a).use { assertEquals(3,it.load().holdings.size) }
        dialog.getButton(-2).performClick();shadowOf(Looper.getMainLooper()).idle()
        LocalStore(a).use { assertEquals(3,it.load().holdings.size) }
        button(a,"删除 2")!!.performClick();dialog=ShadowAlertDialog.getLatestAlertDialog();dialog.getButton(-1).performClick();shadowOf(Looper.getMainLooper()).idle()
        LocalStore(a).use { assertEquals(listOf("h1"),it.load().holdings.map { h->h.id });assertEquals(listOf("t-h1"),it.load().trades.map { t->t.id }) }
        button(a,"完成")!!.performClick();assertNotNull(button(a,"编辑"))
        c.pause().stop().destroy()
    }
    @Test fun selectionSurvivesRecreationAndSelectAllCanBeCleared() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();seed(a)
        assertNotNull(button(a,"编辑"));button(a,"编辑")!!.performClick();check(a,"000001").performClick()
        val saved=Bundle();c.saveInstanceState(saved).pause().stop().destroy()
        val restored=Robolectric.buildActivity(MainActivity::class.java)
        MainActivity::class.java.getDeclaredField("initialRequestDone").apply { isAccessible=true }.setBoolean(restored.get(),true)
        restored.create(saved).start().resume().visible();val b=restored.get()
        assertTrue(check(b,"000001").isChecked)
        val all=views(b.window.decorView).filterIsInstance<CheckBox>().first { it.contentDescription=="全选基金" }
        all.performClick();assertTrue((1..3).all { check(b,it.toString().padStart(6,'0')).isChecked })
        all.performClick();assertTrue((1..3).none { check(b,it.toString().padStart(6,'0')).isChecked })
        assertFalse(button(b,"删除 0")!!.isEnabled)
        restored.pause().stop().destroy()
    }
    @Test fun allSelectionIncludesOtherPagesAndIndividualChoicesRemainStable() {
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get()
        val p=Portfolio(holdings=(1..55).map { i -> Holding("h$i",i.toString().padStart(6,'0'),"测试基金$i",order=i-1) })
        LocalStore(a).use { it.save(p) }
        MainActivity::class.java.getDeclaredField("portfolio").apply { isAccessible=true }.set(a,p)
        MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(a)
        button(a,"编辑")!!.performClick()
        views(a.window.decorView).filterIsInstance<CheckBox>().first { it.contentDescription=="全选基金" }.performClick()
        assertNotNull(button(a,"删除 55"))
        button(a,"下一页")!!.performClick()
        assertTrue(check(a,"000051").isChecked)
        check(a,"000055").performClick();assertNotNull(button(a,"删除 54"))
        button(a,"上一页")!!.performClick()
        assertTrue(check(a,"000001").isChecked)
        c.pause().stop().destroy()
    }
}
