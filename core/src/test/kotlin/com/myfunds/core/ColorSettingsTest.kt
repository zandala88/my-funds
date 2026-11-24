package com.myfunds.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ColorSettingsTest {
    @Test fun colorPreferenceSurvivesBackupAndLegacyDefaultsToRedUp() {
        val original = Portfolio(settings = AppSettings(darkMode = true, redUp = false))
        val raw = BackupCodec.encode(original)
        assertFalse(JSONObject(raw).getJSONObject("settings").getBoolean("redUp"))
        assertEquals(original, BackupCodec.decode(raw))
        val old = JSONObject(raw).apply { getJSONObject("settings").remove("redUp") }
        assertTrue(BackupCodec.decode(old.toString()).settings.redUp)
    }
    @Test fun malformedColorPreferenceDoesNotSilentlyChangeUserSettings() {
        val raw = JSONObject(BackupCodec.encode(Portfolio())).apply { getJSONObject("settings").put("redUp", "false") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(raw.toString()) }
    }
}
