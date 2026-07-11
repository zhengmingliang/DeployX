package com.alianga.idea.deploy

import com.alianga.idea.deploy.config.FileSyncSettings
import java.io.InputStream
import java.text.MessageFormat
import java.util.Locale
import java.util.PropertyResourceBundle
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

/**
 * DeployX 国际化消息访问器。
 *
 * 基于 JDK 标准 [ResourceBundle]，不依赖 IntelliJ [com.intellij.DynamicBundle]，
 * 因此兼容 223+ 全部目标平台，且支持运行时免重启切换语言。
 *
 * 每次调用都从 [FileSyncSettings] 实时读取当前语言设置，新构建的 UI 组件
 * 会自动使用新语言。已构建的组件需重新打开才会刷新。
 *
 * 不使用 [ResourceBundle.getBundle] 的默认 fallback 机制（它会把 JVM 默认
 * locale 混入候选链，导致在中文系统上选英文时仍返回中文 bundle）。
 * 而是根据语言设置直接加载对应的 properties 文件，精确控制 locale。
 */
object DeployXBundle {

    private const val BUNDLE_BASE_NAME = "messages.DeployXBundle"
    private const val DEFAULT_BUNDLE_PATH = "/messages/DeployXBundle.properties"
    private const val ZH_CN_BUNDLE_PATH = "/messages/DeployXBundle_zh_CN.properties"

    /**
     * 按 locale 缓存已加载的 ResourceBundle，避免每次调用都重新读文件。
     * 语言切换时切换 key 即可读到另一份 bundle。
     */
    private val bundleCache = ConcurrentHashMap<String, ResourceBundle>()

    /**
     * 语言变更监听器列表。语言切换后通过 [notifyLanguageChanged] 通知，
     * 已注册的 UI 面板可据此重建/刷新本地化文案。
     */
    private val languageChangeListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 根据持久化设置解析当前语言标识。
     * - "system"：跟随 JVM 默认 Locale（中文系统→中文，其他→英文）
     * - "en"：英文
     * - "zh_CN"：简体中文
     */
    fun currentLanguageTag(): String {
        val language = try {
            FileSyncSettings.getInstance().language
        } catch (_: Exception) {
            "system"
        }
        return when (language) {
            "en" -> "en"
            "zh_CN" -> "zh_CN"
            else -> if (Locale.getDefault().language == "zh") "zh_CN" else "en"
        }
    }

    /**
     * 获取当前语言对应的 ResourceBundle。
     * 直接按语言标识加载对应的 properties 文件，不依赖 ResourceBundle.getBundle
     * 的 fallback 机制，避免 JVM 默认 locale 干扰。
     */
    private fun getBundle(): ResourceBundle {
        val langTag = currentLanguageTag()
        return bundleCache.computeIfAbsent(langTag) { loadBundle(langTag) }
    }

    private fun loadBundle(langTag: String): ResourceBundle {
        val path = when (langTag) {
            "zh_CN" -> ZH_CN_BUNDLE_PATH
            else -> DEFAULT_BUNDLE_PATH
        }
        val stream: InputStream = DeployXBundle::class.java.getResourceAsStream(path)
            ?: return ResourceBundle.getBundle(BUNDLE_BASE_NAME) // fallback to standard mechanism
        return stream.use { PropertyResourceBundle(it) }
    }

    /**
     * 获取国际化消息。
     *
     * 当 [params] 为空时直接返回原始字符串（不经 MessageFormat），
     * 因此文本中可安全包含字面量花括号（如 `${var}`）。
     *
     * 当 [params] 非空时使用 [MessageFormat] 处理 `{0}` `{1}` 占位符。
     */
    @JvmOverloads
    fun message(key: String, vararg params: Any): String {
        val value = try {
            getBundle().getString(key)
        } catch (_: java.util.MissingResourceException) {
            // key 缺失时返回 key 本身，避免抛异常中断 UI
            return key
        }
        return if (params.isEmpty()) value else MessageFormat.format(value, *params)
    }

    /**
     * 延迟获取消息，适用于需要 Supplier 的场景（如 Action 文本）。
     */
    fun lazyMessage(key: String, vararg params: Any): Supplier<String> {
        return Supplier { message(key, *params) }
    }

    /**
     * 注册语言变更监听器。当 [notifyLanguageChanged] 被调用时，监听器会在 EDT 上执行。
     *
     * 返回一个移除监听器的回调，便于面板 dispose 时注销，避免内存泄漏。
     */
    fun addLanguageChangeListener(listener: () -> Unit): () -> Unit {
        languageChangeListeners.add(listener)
        return { languageChangeListeners.remove(listener) }
    }

    /**
     * 通知语言已变更：清空 bundle 缓存（使后续 [message] 调用加载新语言的 bundle），
     * 并在 EDT 上依次触发已注册的监器。
     *
     * 应在持久化语言设置写入后调用（如 [com.alianga.idea.deploy.ui.settings.LanguageSettingsPanel.apply]）。
     */
    fun notifyLanguageChanged() {
        bundleCache.clear()
        if (languageChangeListeners.isEmpty()) return
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            languageChangeListeners.forEach { runCatching(it) }
        } else {
            javax.swing.SwingUtilities.invokeLater {
                languageChangeListeners.forEach { runCatching(it) }
            }
        }
    }
}
