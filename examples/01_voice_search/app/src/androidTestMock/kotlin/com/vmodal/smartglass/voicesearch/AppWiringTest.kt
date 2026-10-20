package com.vmodal.smartglass.voicesearch

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppWiringTest {
    @Test fun rotationRetainsOneSessionOwnerBeforeConnection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var owner: VoiceSearchViewModel? = null
            scenario.onActivity { owner = ViewModelProvider(it)[VoiceSearchViewModel::class.java] }
            scenario.recreate()
            scenario.onActivity {
                val current = ViewModelProvider(it)[VoiceSearchViewModel::class.java]
                assertSame(owner, current)
                assertFalse(current.graph.meta.readiness.value.ready)
            }
        }
    }
}
