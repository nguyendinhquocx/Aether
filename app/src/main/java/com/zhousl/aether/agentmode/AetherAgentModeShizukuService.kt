package com.zhousl.aether.agentmode

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import android.view.Surface
import androidx.annotation.Keep
import androidx.core.content.getSystemService
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

private const val VirtualDisplayFlagPublic = 1 shl 0
private const val VirtualDisplayFlagOwnContentOnly = 1 shl 3
private const val VirtualDisplayFlagSupportsTouch = 1 shl 6
private const val VirtualDisplayFlagDestroyContentOnRemoval = 1 shl 8
private const val VirtualDisplayFlagTrusted = 1 shl 10
private const val VirtualDisplayFlagTouchFeedbackDisabled = 1 shl 13
private const val VirtualDisplayFlagOwnFocus = 1 shl 14
private const val VirtualDisplayFlagStealTopFocusDisabled = 1 shl 16

private const val ShellPackageName = "com.android.shell"
private const val SystemPackageName = "android"
private const val RootUid = 0

/**
 * The privileged Virtual Display lifecycle service. UI operations use PhoneUseAccessibilityService. It runs in a separate shell (Shizuku) or root process started
 * by [AgentModeServiceStarter], which passes a [context] for Aether in the launching Android user.
 */
class AetherAgentModeShizukuService @Keep constructor(
    private val context: Context,
) : IAetherAgentModeService.Stub() {
    private val privilegedContext: Context by lazy { contextForCurrentProcess(context) }
    private val displayManager: DisplayManager by lazy {
        privilegedContext.getSystemService<DisplayManager>()!!
    }

    /**
     * This process runs as shell (Shizuku) or root, both in user 0, while [context] belongs to the
     * Android user that launched it. Package queries, activity launches are
     * per-user, so each call targets the Android user that owns the calling Aether process, never
     * the process's own user nor the user [context] happens to be bound to.
     */
    private val userContexts = ConcurrentHashMap<UserHandle, Context>()

    /** Must be called on the binder thread of the incoming call. */
    private fun callerUserHandle(): UserHandle = UserHandle.getUserHandleForUid(Binder.getCallingUid())

    private fun callerUserContext(): Context = userContextFor(callerUserHandle())

    private fun userContextFor(user: UserHandle): Context =
        userContexts.computeIfAbsent(user) { createPackageContextAsUser(it) }

    @SuppressLint("DiscouragedPrivateApi")
    private fun createPackageContextAsUser(user: UserHandle): Context {
        val packageName = privilegedContext.packageName
        return runCatching {
            Context::class.java
                .getMethod(
                    "createPackageContextAsUser",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    UserHandle::class.java,
                )
                .invoke(privilegedContext, packageName, Context.CONTEXT_IGNORE_SECURITY, user) as Context
        }.getOrElse { throwable ->
            val cause = (throwable as? java.lang.reflect.InvocationTargetException)?.targetException ?: throwable
            error("Phone Use cannot act on behalf of Android user $user: ${cause.message ?: cause.javaClass.simpleName}")
        }
    }
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val imageReaders = ConcurrentHashMap<Int, ImageReader>()
    private val previewSurfaces = ConcurrentHashMap<Int, Surface>()
    private val displayLocks = ConcurrentHashMap<Int, Any>()

    override fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface,
    ): Int {
        val display = displayManager.createVirtualDisplay(
            name,
            width,
            height,
            density,
            surface,
            agentVirtualDisplayFlags(),
        )
        val displayId = display.display.displayId
        displays[displayId] = display
        displayLocks[displayId] = Any()
        return displayId
    }

    override fun createOwnedDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
    ): Int {
        val reader = ImageReader.newInstance(
            width,
            height,
            android.graphics.PixelFormat.RGBA_8888,
            2,
        )
        // Drain the off-screen render surface even when no live preview is attached.
        // Otherwise its buffer queue fills and the background app stops producing frames.
        reader.setOnImageAvailableListener({ available ->
            runCatching { available.acquireLatestImage()?.close() }
        }, Handler(android.os.Looper.getMainLooper()))
        val display = displayManager.createVirtualDisplay(
            name,
            width,
            height,
            density,
            reader.surface,
            agentVirtualDisplayFlags(),
        )
        val displayId = display.display.displayId
        displays[displayId] = display
        imageReaders[displayId] = reader
        displayLocks[displayId] = Any()
        return displayId
    }

    override fun attachPreviewSurface(displayId: Int, surface: Surface) {
        val display = displays[displayId]
            ?: error("Display $displayId is not managed by Aether Phone Use.")
        synchronized(displayLock(displayId)) {
            previewSurfaces[displayId] = surface
            display.setSurface(surface)
        }
    }

    override fun detachPreviewSurface(displayId: Int) {
        val display = displays[displayId]
            ?: error("Display $displayId is not managed by Aether Phone Use.")
        synchronized(displayLock(displayId)) {
            previewSurfaces.remove(displayId)
            display.setSurface(imageReaders[displayId]?.surface)
        }
    }

    override fun releaseDisplay(displayId: Int) {
        synchronized(displayLock(displayId)) {
            previewSurfaces.remove(displayId)
            displays.remove(displayId)?.release()
            imageReaders.remove(displayId)?.close()
            displayLocks.remove(displayId)
        }
    }

    override fun destroy() {
        displays.keys.toList().forEach { displayId ->
            runCatching { releaseDisplay(displayId) }
        }
        previewSurfaces.clear()
        imageReaders.clear()
        displayLocks.clear()
        System.exit(0)
    }

    override fun linkClient(client: IBinder) {
        client.linkToDeath({ destroy() }, 0)
    }

    override fun launchPackage(packageName: String, displayId: Int) {
        ensureManagedDisplay(displayId)
        val userContext = callerUserContext()
        val intent = userContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("No launchable activity for $packageName.")
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            options.launchDisplayId = displayId
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)

        val targetDisplay = displayManager.getDisplay(displayId)
            ?: error("Display $displayId is not available.")
        val displayContext = userContext.createDisplayContext(targetDisplay)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        PendingIntent.getActivity(
            displayContext,
            intent.hashCode(),
            intent,
            flags,
        ).send(
            userContext,
            0,
            null,
            null,
            null,
            null,
            options.toBundle(),
        )
    }

    private fun displayLock(displayId: Int): Any =
        displayLocks.computeIfAbsent(displayId) { Any() }

    override fun listDisplaysJson(): String =
        JSONArray().apply {
            displayManager.displays.forEach { display ->
                val size = Point()
                @Suppress("DEPRECATION")
                display.getSize(size)
                put(
                    JSONObject().apply {
                        put("display_id", display.displayId)
                        put("name", display.name.orEmpty())
                        put("width", display.mode?.physicalWidth ?: size.x)
                        put("height", display.mode?.physicalHeight ?: size.y)
                        put("is_aether_display", displays.containsKey(display.displayId))
                    }
                )
            }
        }.toString()

    @Suppress("DEPRECATION")
    override fun listInstalledAppsJson(): String {
        val packageManager = callerUserContext().packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchables = packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        val uniqueApps = linkedMapOf<String, JSONObject>()
        launchables
            .sortedWith(
                compareBy(
                    { it.loadLabel(packageManager).toString().lowercase() },
                    { it.activityInfo.packageName },
                )
            )
            .forEach { info ->
                val activityInfo = info.activityInfo ?: return@forEach
                val applicationInfo = activityInfo.applicationInfo ?: return@forEach
                val packageName = activityInfo.packageName.orEmpty()
                if (packageName.isBlank() || uniqueApps.containsKey(packageName)) return@forEach
                uniqueApps[packageName] = JSONObject().apply {
                    put("package_name", packageName)
                    put("app_name", info.loadLabel(packageManager).toString())
                    put("activity_name", activityInfo.name.orEmpty())
                    put("enabled", activityInfo.enabled && applicationInfo.enabled)
                    put("system", applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                    put("launchable", true)
                }
            }
        return JSONArray(uniqueApps.values).toString()
    }

    private fun contextForCurrentProcess(baseContext: Context): Context {
        val packageName = packageNameForCurrentProcess(baseContext.packageName)
        if (packageName == baseContext.packageName) return baseContext
        return baseContext.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
    }

    private fun packageNameForCurrentProcess(defaultPackageName: String): String =
        when (Process.myUid()) {
            Process.SHELL_UID -> ShellPackageName
            // Root owns no package; act as the platform, as the root service always has.
            RootUid, Process.SYSTEM_UID -> SystemPackageName
            else -> defaultPackageName
        }

    private fun agentVirtualDisplayFlags(): Int =
        VirtualDisplayFlagPublic or
            VirtualDisplayFlagOwnContentOnly or
            VirtualDisplayFlagSupportsTouch or
            VirtualDisplayFlagDestroyContentOnRemoval or
            VirtualDisplayFlagTrusted or
            VirtualDisplayFlagTouchFeedbackDisabled or
            VirtualDisplayFlagOwnFocus or
            VirtualDisplayFlagStealTopFocusDisabled

    private fun ensureManagedDisplay(displayId: Int) {
        if (!displays.containsKey(displayId)) {
            error("Display $displayId is not managed by Aether Phone Use.")
        }
    }


}

/**
 * Extracts the focused application and window for [displayId] from `dumpsys input` output. The
 * InputDispatcher section lists one `displayId=N, name='...'` line per display under the
 * `FocusedApplications:` and `FocusedWindows:` headers. `focused_window` is present (possibly empty)
 * only when the headers were found, so callers can tell "nothing focused" from "unknown".
 */
internal fun parseInputDispatcherFocus(dump: String, displayId: Int): JSONObject {
    val lines = dump.lines()
    fun sectionEntry(header: String): String? {
        val start = lines.indexOfFirst { it.trim().startsWith(header) }
        if (start < 0) return null
        // e.g. "FocusedWindows: <none>"
        if (lines[start].trim().removePrefix(header).isNotBlank()) return ""
        val prefix = "displayId=$displayId,"
        for (line in lines.drop(start + 1)) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("displayId=")) break
            if (trimmed.startsWith(prefix)) {
                return trimmed.substringAfter("name='", "").substringBefore("'")
            }
        }
        return ""
    }
    return JSONObject().apply {
        put("display_id", displayId)
        sectionEntry("FocusedApplications:")?.takeIf { it.isNotEmpty() }?.let { put("focused_application", it) }
        sectionEntry("FocusedWindows:")?.let { put("focused_window", it) }
    }
}
