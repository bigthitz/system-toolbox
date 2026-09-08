package com.system.toolbox.core

import android.os.Build
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 特权进程（uid.system）WebView 解禁。
 *
 * AOSP 在 WebViewFactory.getProvider() 中对 SYSTEM_UID / PHONE_UID 等特权 uid
 * 直接抛出 "For security reasons, WebView is not allowed in privileged processes"。
 * 本工具在进程内通过反射提前构造 WebViewProvider 实例并写入 sProviderInstance，
 * 使 getProvider() 在 uid 检查前短路返回，从而在 uid.system 下正常使用 WebView。
 *
 * 适配 Android 6.0 ~ 14（AOSP）。不同厂商 ROM 的类名 / 方法签名可能有差异，
 * 全程 catch Throwable，失败时静默降级（浏览器页会显示初始化失败提示，不会崩溃）。
 *
 * 调用时机：必须在任何 WebView 创建之前，最佳位置为 Application.onCreate()。
 */
object WebViewHacker {

    @Volatile
    private var tried = false

    fun hackWebView() {
        if (tried) return
        tried = true

        val sdk = Build.VERSION.SDK_INT
        if (sdk < Build.VERSION_CODES.M) return // 低于 6.0 无此限制

        try {
            // 1. WebViewFactory 类
            val factoryClass = Class.forName("android.webkit.WebViewFactory")

            // 2. 静态字段 sProviderInstance
            val providerField: Field = factoryClass.getDeclaredField("sProviderInstance")
            providerField.isAccessible = true
            if (providerField.get(null) != null) return // 已初始化，无需 hack

            // 3. 获取 Provider 类（不同版本方法名不同）
            val getProviderClassMethod: Method = if (sdk == 22) {
                factoryClass.getDeclaredMethod("getFactoryClass")
            } else {
                factoryClass.getDeclaredMethod("getProviderClass")
            }
            getProviderClassMethod.isAccessible = true
            val providerClass = getProviderClassMethod.invoke(factoryClass) as Class<*>

            // 4. 构造 WebViewDelegate 实例
            val delegateClass = Class.forName("android.webkit.WebViewDelegate")
            val delegateCtor = delegateClass.getDeclaredConstructor()
            delegateCtor.isAccessible = true
            val delegate = delegateCtor.newInstance()

            // 5. 构造 Provider 实例并写入 sProviderInstance
            val providerCtor = providerClass.getConstructor(delegateClass)
            providerCtor.isAccessible = true
            val provider = providerCtor.newInstance(delegate)

            providerField.set(null, provider)
        } catch (t: Throwable) {
            t.printStackTrace()
            // 降级：浏览器页会捕获 WebView 创建失败并提示，不影响其它功能
        }
    }
}
