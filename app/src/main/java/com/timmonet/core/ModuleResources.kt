package com.timmonet.core

import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.timmonet.R
import java.io.File

/**
 * 把模块 APK 的资源注入宿主(TIM)进程的 Resources。
 *
 * 为什么需要：宿主进程里跑模块自己的 Compose 界面时，界面里的 `R.string.*` 必须
 * 能解析到模块 APK 的资源，否则会抛 Resources.NotFoundException。Android 11
 * (API 30) 起 `ResourcesLoader`/`ResourcesProvider` 是公开 API，用它挂载模块
 * APK 即可（QAuxiliary 的 Parasitics 在 API 30+ 也是同样做法）。
 *
 * 注入是否成功用 [R.string.module_res_marker] 的值做自检——只看 id 会被宿主的
 * 同名 id 撞车误判。
 */
object ModuleResources {

    private const val TAG = "TimMonet"

    /** 必须与 res/values/strings.xml 里 module_res_marker 的值一致。 */
    private const val MARKER = "TimMonet"

    @Volatile
    private var injected = false

    /** 幂等；未成功则每次都会重试。必须在主线程调用（Resources.addLoaders 的要求）。 */
    fun ensureInjected(res: Resources?): Boolean {
        if (res == null) return false
        if (injected) return true
        if (markerOk(res)) {
            injected = true
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "module resources need API 30+ (ResourcesLoader)")
            return false
        }
        return try {
            val path = moduleApkPath()
            if (path == null) {
                Log.w(TAG, "module apk path not found")
                false
            } else {
                val pfd = ParcelFileDescriptor.open(
                    File(path),
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
                val provider = ResourcesProvider.loadFromApk(pfd)
                val loader = ResourcesLoader().apply { addProvider(provider) }
                res.addLoaders(loader)
                val ok = markerOk(res)
                injected = ok
                Log.i(TAG, "module resources injected=$ok from $path")
                ok
            }
        } catch (t: Throwable) {
            Log.e(TAG, "module resources inject failed: ${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    private fun markerOk(res: Resources): Boolean = try {
        res.getString(R.string.module_res_marker) == MARKER
    } catch (t: Throwable) {
        false
    }

    /** 模块 APK 路径。
     *
     *  LSPosed 的模块 ClassLoader 是它自己造的，`protectionDomain.codeSource` 拿不到
     *  路径（实测为 null），所以依次尝试几种办法：
     *  1. codeSource（某些加载器可用）
     *  2. 模块 ClassLoader 里 AndroidManifest.xml 的 `jar:file:<apk>!/` 前缀
     *  3. /proc/self/maps 里被 mmap 的模块 apk（无需 root，本进程可见）
     */
    private fun moduleApkPath(): String? {
        val fromCodeSource = runCatching {
            ModuleResources::class.java.protectionDomain?.codeSource?.location?.path
        }.getOrNull()
        if (!fromCodeSource.isNullOrEmpty()) {
            Log.i(TAG, "module apk path from codeSource: $fromCodeSource")
            return fromCodeSource
        }
        val fromResource = runCatching {
            val url = ModuleResources::class.java.classLoader
                ?.getResource("AndroidManifest.xml")?.toString().orEmpty()
            Regex("jar:file:(.*)!/").find(url)?.groupValues?.get(1)
        }.getOrNull()
        if (!fromResource.isNullOrEmpty()) {
            Log.i(TAG, "module apk path from classloader resource: $fromResource")
            return fromResource
        }
        val fromMaps = runCatching {
            File("/proc/self/maps").useLines { lines ->
                lines.mapNotNull { line ->
                    val path = line.substringAfterLast(' ').trim()
                    if (path.endsWith(".apk") && path.contains("timmonet", ignoreCase = true)) {
                        path
                    } else {
                        null
                    }
                }.firstOrNull()
            }
        }.getOrNull()
        if (!fromMaps.isNullOrEmpty()) {
            Log.i(TAG, "module apk path from /proc/self/maps: $fromMaps")
            return fromMaps
        }
        Log.w(TAG, "module apk path not found (codeSource/resource/maps all failed)")
        return null
    }
}
