package com.alianga.idea.deploy.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.lang.reflect.Modifier

/**
 * Gson 空值兜底（1.1.1 修复）：旧版 JSON 缺字段或字段显式为 null 时，Gson 会把 Kotlin 非空字段
 * 留成 null，新代码一读就 NPE（如 MappingConfig.effectivePreCommandEnabled）。
 *
 * 对本包内有无参构造（全部参数带默认值）的模型类，反序列化后把仍为 null 的字段补成默认实例里的值。
 *
 * @author 郑明亮
 */
object NullSafeModels : TypeAdapterFactory {

    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val raw = type.rawType
        if (raw.`package`?.name != NullSafeModels::class.java.`package`.name || raw.isEnum) return null
        val defaults: Any = try {
            raw.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
        } catch (_: Exception) {
            return null
        }
        val fields = generateSequence<Class<*>>(raw) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
            .onEach { it.isAccessible = true }
            .toList()
        val delegate = gson.getDelegateAdapter(this, type)
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) = delegate.write(out, value)
            override fun read(reader: JsonReader): T? {
                val v = delegate.read(reader) ?: return null
                fields.forEach { f ->
                    if (f.get(v) == null) f.get(defaults)?.let { d -> f.set(v, d) }
                    // 列表里的 null 元素一并去掉
                    (f.get(v) as? List<*>)?.takeIf { l -> l.any { it == null } }?.let { l -> f.set(v, l.filterNotNull()) }
                }
                return v
            }
        }
    }

    /** 统一的 GsonBuilder：已注册空值兜底 */
    fun builder(): GsonBuilder = GsonBuilder().registerTypeAdapterFactory(NullSafeModels)
}
