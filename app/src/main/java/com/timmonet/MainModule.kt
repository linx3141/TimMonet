package com.timmonet

import android.util.Log
import com.timmonet.hooks.TimMonetHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * LSPosed 模块入口。
 *
 * 入口类通过 META-INF/xposed/java_init.list 声明，
 * 由框架自动调用 attachFramework()，随后触发下面的回调。
 */
class MainModule : XposedModule() {

    companion object {
        const val TAG = "TimMonet"
        const val TARGET_PACKAGE = "com.tencent.tim"
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Log.i(TAG, "onModuleLoaded, process=${param.processName}, isSystemServer=${param.isSystemServer}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        Log.i(TAG, "onPackageReady ${param.packageName}, first=${param.isFirstPackage}")
        try {
            TimMonetHooks.install(this, param.classLoader)
        } catch (t: Throwable) {
            Log.e(TAG, "install hooks failed", t)
        }
    }
}
