package com.myfunds.android

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView

/** One shared treatment for every secondary operation, including platform pickers. */
class RoundedDialogBuilder(private val ui: Ui) : AlertDialog.Builder(ui.dialogContext) {
    override fun setItems(items: Array<out CharSequence>?, listener: DialogInterface.OnClickListener?): AlertDialog.Builder {
        val adapter = object : ArrayAdapter<CharSequence>(ui.dialogContext, android.R.layout.simple_list_item_1, items ?: emptyArray()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                ui.text(getItem(position).toString(), 15f).apply {
                    minHeight = ui.dp(54)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12))
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(ui.soft), ColorDrawable(ui.card), null)
                }
        }
        return setAdapter(adapter, listener)
    }
    override fun create(): AlertDialog = super.create().also { ui.prepareDialog(it) }
    override fun show(): AlertDialog = create().also { it.show() }
}

fun Ui.prepareDialog(dialog: Dialog) {
    val decor = dialog.window?.decorView ?: return
    fun style() {
        val window = dialog.window ?: return
        window.setBackgroundDrawable(InsetDrawable(shape(card, 28), dp(12)))
        // The platform panel otherwise paints its own square background over the window.
        val panelId = context.resources.getIdentifier("parentPanel", "id", "android")
        decor.findViewById<View>(panelId)?.apply {
            background = shape(card, 28)
            clipToOutline = true
        }
        val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
        decor.findViewById<TextView>(titleId)?.apply { setTextColor(ink); textSize = 20f }
        decor.findViewById<TextView>(android.R.id.message)?.apply { setTextColor(muted); textSize = 14f }
        if (dialog is AlertDialog) {
            listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL).forEach { id ->
                dialog.getButton(id)?.apply {
                    isAllCaps = false; textSize = 13f
                    setTextColor(if (id == DialogInterface.BUTTON_POSITIVE) Color.WHITE else accent)
                    background = shape(if (id == DialogInterface.BUTTON_POSITIVE) accent else soft, 22)
                    minHeight = dp(44); minimumHeight = dp(44)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    (layoutParams as? LinearLayout.LayoutParams)?.apply {
                        setMargins(dp(4), dp(6), dp(4), dp(6)); layoutParams = this
                    }
                    stateListAnimator = null
                }
            }
            dialog.listView?.apply { divider = ColorDrawable(border); dividerHeight = dp(1); setBackgroundColor(card) }
        }
    }
    decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { style() }
        override fun onViewDetachedFromWindow(v: View) = Unit
    })
}
