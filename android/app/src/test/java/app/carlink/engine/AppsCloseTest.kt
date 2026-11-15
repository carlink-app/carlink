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
        assertEquals("social", Apps.category("org.telegram.messenger", android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY))
    }

    @Test fun declaredCategory() {
        assertEquals("music", Apps.category("x.y", android.content.pm.ApplicationInfo.CATEGORY_AUDIO))
        assertEquals("nav", Apps.category("x.y", android.content.pm.ApplicationInfo.CATEGORY_MAPS))
        assertEquals("other", Apps.category("x.y", android.content.pm.ApplicationInfo.CATEGORY_GAME))
    }

    @Test fun noDisplay() = assertEquals(emptyList<Int>(), Apps.rootTasks(list, 7, null))
}
