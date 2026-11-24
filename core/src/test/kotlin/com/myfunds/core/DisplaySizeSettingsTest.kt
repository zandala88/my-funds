package com.myfunds.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DisplaySizeSettingsTest {
    @Test fun allDisplaySizesRoundTripAndOldBackupsKeepStandardSize() {
        for (size in DisplaySize.entries) {
            val p = Portfolio(settings = AppSettings(darkMode = true, redUp = false, displaySize = size))
            val raw = BackupCodec.encode(p)
            assertEquals(size.name, JSONObject(raw).getJSONObject("settings").getString("displaySize"))
            assertEquals(p, BackupCodec.decode(raw))
        }
        val legacy = JSONObject(BackupCodec.encode(Portfolio())).apply { getJSONObject("settings").remove("displaySize") }
        assertEquals(DisplaySize.STANDARD, BackupCodec.decode(legacy.toString()).settings.displaySize)
    }
    @Test fun unknownSizeIsRejectedRatherThanChangingOtherSettings() {
        val raw = JSONObject(BackupCodec.encode(Portfolio())).apply { getJSONObject("settings").put("displaySize", "HUGE") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(raw.toString()) }
    }
}
