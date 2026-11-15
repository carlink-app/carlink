package app.carlink.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class AppsCloseTest {
    // Trimmed `am stack list` output from a Pixel (Android 16).
    private val list = """
        RootTask id=37873 bounds=[0,0][3648,1922] displayId=3 userId=0
         configuration={1.0 ldltr sw639dp land night}
          taskId=37873: com.iternio.abrpapp/com.iternio.abrpapp.MainActivity bounds=[0,0][3648,1922] userId=0 visible=true topActivity=ComponentInfo{com.iternio.abrpapp/com.iternio.abrpapp.MainActivity}

        RootTask id=37878 bounds=[0,0][3648,1922] displayId=3 userId=0
         configuration={1.0 ldltr sw639dp land night}
          taskId=37878: com.waze/com.waze.FreeMapAppActivity bounds=[0,0][3648,1922] userId=0 visible=false topActivity=ComponentInfo{com.waze/com.waze.MainActivity}

        RootTask id=37870 bounds=[0,0][1008,2244] displayId=0 userId=0
         configuration={1.0 ldltr sw448dp port night}
          taskId=37870: com.waze/com.waze.FreeMapAppActivity bounds=[0,0][1008,2244] userId=0 visible=true

        RootTask id=1 bounds=[0,0][1008,2244] displayId=0 userId=0
          taskId=2: com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity
    """.trimIndent()

    @Test fun allOnDisplay() = assertEquals(listOf(37873, 37878), Apps.rootTasks(list, 3, null))

    @Test fun onePackageOnlyOnThatDisplay() = assertEquals(listOf(37878), Apps.rootTasks(list, 3, "com.waze"))

    @Test fun phoneDisplayUntouched() = assertEquals(listOf(37870), Apps.rootTasks(list, 0, "com.waze"))

    @Test fun unknownPackage() = assertEquals(emptyList<Int>(), Apps.rootTasks(list, 3, "com.spotify.music"))

    @Test fun prefixIsNotAMatch() = assertEquals(emptyList<Int>(), Apps.rootTasks(list, 3, "com.waz"))

    @Test fun knownAppsBeatDeclaredCategory() {
        assertEquals("nav", Apps.category("com.waze", android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED))
        // Charging/fuel apps declare "maps" too, but get their own group.
        assertEquals("energy", Apps.category("com.xatori.Plugshare", android.content.pm.ApplicationInfo.CATEGORY_MAPS))
        assertEquals("energy", Apps.category("com.iternio.abrpapp", android.content.pm.ApplicationInfo.CATEGORY_MAPS))
        assertEquals("social", Apps.category("org.telegram.messenger", android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY))
        assertEquals("other", Apps.category("com.ubercab", android.content.pm.ApplicationInfo.CATEGORY_MAPS))
        assertEquals("other", Apps.category("ee.mtakso.client", android.content.pm.ApplicationInfo.CATEGORY_MAPS))
    }

    @Test fun declaredCategory() {
        assertEquals("music", Apps.category("x.y", android.content.pm.ApplicationInfo.CATEGORY_AUDIO))
        assertEquals("nav", Apps.category("x.y", android.content.pm.ApplicationInfo.CATEGORY_MAPS))
        assertEquals("other", Apps.category("x.y", android.content.pm.ApplicationInfo.CATEGORY_GAME))
    }

    @Test fun noDisplay() = assertEquals(emptyList<Int>(), Apps.rootTasks(list, 7, null))

    @Test fun emptyOrJunkOutput() {
        assertEquals(emptyList<Int>(), Apps.rootTasks("", 3, null))
        assertEquals(emptyList<Int>(), Apps.rootTasks("Error: unknown command\n", 3, "com.waze"))
    }

    @Test fun taskLinesBeforeAnyRootAreIgnored() {
        val l = "  taskId=5: com.waze/com.waze.Main\nRootTask id=9 bounds=[0,0][1,1] displayId=3 userId=0\n  taskId=9: x.y/x.y.Main"
        assertEquals(emptyList<Int>(), Apps.rootTasks(l, 3, "com.waze"))
        assertEquals(listOf(9), Apps.rootTasks(l, 3, null))
    }

    @Test fun matchInOneRootDoesNotLeakIntoTheNext() {
        // The match flag resets at each header: only the root holding com.waze is closed.
        val l = """
            RootTask id=10 bounds=[0,0][1,1] displayId=3 userId=0
              taskId=11: com.waze/com.waze.Main
            RootTask id=20 bounds=[0,0][1,1] displayId=3 userId=0
              taskId=21: com.spotify.music/com.spotify.Main
        """.trimIndent()
        assertEquals(listOf(10), Apps.rootTasks(l, 3, "com.waze"))
        assertEquals(listOf(20), Apps.rootTasks(l, 3, "com.spotify.music"))
    }

    @Test fun packageInLaterTaskOfARootMatches() {
        val l = """
            RootTask id=10 bounds=[0,0][1,1] displayId=3 userId=0
              taskId=11: x.first/x.first.Main
              taskId=12: com.waze/com.waze.Main
        """.trimIndent()
        assertEquals(listOf(10), Apps.rootTasks(l, 3, "com.waze"))
    }

    @Test fun displayIdIsMatchedWhole() {
        // displayId=13 must not count as display 3 (nor 1).
        val l = "RootTask id=10 bounds=[0,0][1,1] displayId=13 userId=0\n  taskId=11: com.waze/com.waze.Main"
        assertEquals(emptyList<Int>(), Apps.rootTasks(l, 3, null))
        assertEquals(emptyList<Int>(), Apps.rootTasks(l, 1, null))
        assertEquals(listOf(10), Apps.rootTasks(l, 13, "com.waze"))
    }

    @Test fun packageNamedOnlyInTopActivityDoesNotMatch() {
        // Only the task's own component counts, not a mention elsewhere on the line.
        val l = "RootTask id=10 bounds=[0,0][1,1] displayId=3 userId=0\n  taskId=11: x.y/x.y.Main topActivity=ComponentInfo{com.waze/com.waze.Main}"
        assertEquals(emptyList<Int>(), Apps.rootTasks(l, 3, "com.waze"))
    }

    @Test fun declaredSocialAndProductivity() {
        assertEquals("social", Apps.category("x.y", android.content.pm.ApplicationInfo.CATEGORY_SOCIAL))
        // Productivity alone isn't work (password managers, browsers...): only known work apps are.
        assertEquals("other", Apps.category("x.y", android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY))
        assertEquals("work", Apps.category("com.Slack", android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY))
        assertEquals("video", Apps.category("com.netflix.mediaclient", android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED))
        assertEquals("video", Apps.category("x.y", android.content.pm.ApplicationInfo.CATEGORY_VIDEO))
        assertEquals("other", Apps.category("x.y", android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED))
        assertEquals("music", Apps.category("com.spotify.music", android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED))
        assertEquals("work", Apps.category("com.google.android.gm", android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED))
    }
}
