package org.lsposed.corepatch

import android.annotation.SuppressLint
import android.graphics.Point
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

typealias BeforeCallback = (XposedHelper.BeforeHookCallback) -> Unit
typealias AfterCallback = (XposedHelper.AfterHookCallback) -> Unit

object XposedHelper {
    lateinit var xposedModule: XposedModule
        private set
    lateinit var hostClassLoader: ClassLoader
        private set
    val prefs by lazy { xposedModule.getRemotePreferences("conf") }
    private val fieldOffsetValue by lazy { getFieldOffsetOffset() }

    fun setXposedModule(module: XposedModule) {
        xposedModule = module
    }

    fun setHostClassLoader(classLoader: ClassLoader) {
        hostClassLoader = classLoader
    }

    class BeforeHookCallback(private val chain: XposedInterface.Chain) {
        val thisObject: Any? get() = chain.thisObject
        val args: Array<Any?> = chain.args.toTypedArray()
        private var skipped = false
        private var skipResult: Any? = null

        fun returnAndSkip(result: Any?) {
            skipped = true
            skipResult = result
        }

        fun isSkipped() = skipped
        fun getSkipResult() = skipResult
    }

    class AfterHookCallback(
        private val chain: XposedInterface.Chain,
        var result: Any?,
        var throwable: Throwable?
    ) {
        val thisObject: Any? get() = chain.thisObject
        val args: Array<Any?> get() = chain.args.toTypedArray()
    }

    internal class CustomHooker(
        val beforeCallback: BeforeCallback = {},
        val afterCallback: AfterCallback = {},
    ) : XposedInterface.Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            var result: Any? = null
            var throwable: Throwable? = null
            var skipped = false

            val bcb = BeforeHookCallback(chain)
            beforeCallback(bcb)
            if (bcb.isSkipped()) {
                result = bcb.getSkipResult()
                skipped = true
            }

            if (!skipped) {
                try {
                    result = chain.proceed(bcb.args)
                } catch (t: Throwable) {
                    throwable = t
                }
            }

            val acb = AfterHookCallback(chain, result, throwable)
            afterCallback(acb)
            result = acb.result
            throwable = acb.throwable

            if (throwable != null) {
                throw throwable
            }
            return result
        }
    }

    /** 本代次已注册的 hook id，用于发现同 (executable, phase) 的重复注册 */
    private val installedHookIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun hookBefore(
        member: Executable, callback: BeforeCallback
    ): XposedInterface.HookHandle {
        val id = hookId(member, "before")
        registerHookId(id)
        return xposedModule.hook(member).setId(id)
            .intercept(CustomHooker(beforeCallback = callback))
    }

    fun hookAfter(
        executable: Executable, callback: AfterCallback
    ): XposedInterface.HookHandle {
        val id = hookId(executable, "after")
        registerHookId(id)
        return xposedModule.hook(executable).setId(id)
            .intercept(CustomHooker(afterCallback = callback))
    }

    /**
     * 同一个 (executable, phase) 被注册两次时，后注册的会按 id 原子替换先注册的，
     * 其中一个逻辑静默失效。这里只告警不抛异常：抛异常会让 hook 直接装不上，
     * 比静默顶替更糟。每代次的注册表是新的，热重载后重新安装不会误报。
     */
    private fun registerHookId(id: String) {
        if (!installedHookIds.add(id)) {
            Log.w(
                "CorePatch",
                "duplicate hook id: later registration replaces the earlier one -> $id"
            )
        }
    }

    /**
     * 稳定的 hook 标识。
     *
     * API 102 起，同一模块、同一 Executable 上相同 id 的新 hook 会原子替换旧的。但当前
     * 热重载走的是"先全量 unhook 旧 handle、再重装"的路线（见 XposedMain.onHotReloaded），
     * 装新 hook 时旧的已经被删掉，所以 id 此刻并不承担替换，它的作用是：
     *   1. 给每个挂载点一个身份，配合上面的重复注册检查；
     *   2. 保留 handle 反查键——将来若要消掉 unhook 与重装之间的空窗期而改用
     *      HookHandle.replaceHook()，可用 handle.getId() 反解出 phase 与 executable。
     *
     * 必须把 before/after 计入 id：同一个方法上同时挂 before 与 after 的模块
     * （例如 InstallCallerGateHook）如果共用一个 id，后注册的会原子替换先注册的，
     * 直接导致其中一个逻辑不生效。
     */
    private fun hookId(executable: Executable, phase: String): String {
        val kind = if (executable is Method) "M" else "C"
        val params = executable.parameterTypes.joinToString(",") { it.name }
        return "$phase:$kind:${executable.declaringClass.name}#${executable.name}($params)"
    }

    fun log(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            xposedModule.log(Log.ERROR, "CorePatch", message, throwable)
        } else if (BuildConfig.DEBUG) {
            xposedModule.log(Log.DEBUG, "CorePatch", message)
        }
    }

    fun deoptimize(method: Method): Boolean {
        return xposedModule.deoptimize(method)
    }

    fun getOriginInvoker(method: Method): XposedInterface.Invoker<*, Method?>? =
        xposedModule.getInvoker(method).setType(XposedInterface.Invoker.Type.ORIGIN)

    fun findClassIfExists(name: String): Class<*>? {
        return try {
            hostClassLoader.loadClass(name)
        } catch (_: ClassNotFoundException) {
            null
        }
    }

    fun setStaticBoolean(field: Field, value: Boolean) {
        try {
            // Resolve the target field before reading its internal ART offset.
            field.isAccessible = true
            field.get(null)
        } catch (_: IllegalAccessException) {
        }

        val offset = UnsafeAccess.getInt(field, fieldOffsetValue).toLong()
        UnsafeAccess.putBoolean(field.declaringClass, offset, value)
    }

    @SuppressLint("DiscouragedPrivateApi")
    private object UnsafeAccess {
        private val unsafeClass = Class.forName("sun.misc.Unsafe")
        private val unsafeInstance = unsafeClass.getDeclaredField("theUnsafe").let { field ->
            field.isAccessible = true
            field.get(null)
        }
        private val getIntMethod = unsafeClass.getMethod(
            "getInt", Any::class.java, Long::class.javaPrimitiveType
        )
        private val putIntMethod = unsafeClass.getMethod(
            "putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        private val putBooleanMethod = unsafeClass.getMethod(
            "putBoolean",
            Any::class.java,
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        private val objectFieldOffsetMethod = unsafeClass.getMethod(
            "objectFieldOffset", Field::class.java
        )

        fun getInt(target: Any, offset: Long) =
            getIntMethod.invoke(unsafeInstance, target, offset) as Int

        fun putInt(target: Any, offset: Long, value: Int) {
            putIntMethod.invoke(unsafeInstance, target, offset, value)
        }

        fun putBoolean(target: Any, offset: Long, value: Boolean) {
            putBooleanMethod.invoke(unsafeInstance, target, offset, value)
        }

        fun objectFieldOffset(field: Field) =
            objectFieldOffsetMethod.invoke(unsafeInstance, field) as Long
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SoonBlockedPrivateApi")
    private fun getFieldOffsetOffset(): Long {
        var noSuchFieldException: NoSuchFieldException? = null
        try {
            val offsetField = Field::class.java.getDeclaredField("offset")
            offsetField.isAccessible = true
            offsetField.getInt(offsetField)
            return UnsafeAccess.objectFieldOffset(offsetField)
        } catch (e: NoSuchFieldException) {
            noSuchFieldException = e
        } catch (_: IllegalAccessException) {
        } catch (_: UnsupportedOperationException) {
        }

        val probeField = Point::class.java.getDeclaredField("x")
        probeField.getInt(Point())
        val fieldOffset = UnsafeAccess.objectFieldOffset(probeField).toInt()
        for (offset in 8 until 256 step 4) {
            val offsetLong = offset.toLong()
            if (UnsafeAccess.getInt(probeField, offsetLong) != fieldOffset) continue

            val modifiedOffset = fieldOffset.inv()
            UnsafeAccess.putInt(probeField, offsetLong, modifiedOffset)
            val currentOffset = UnsafeAccess.objectFieldOffset(probeField).toInt()
            UnsafeAccess.putInt(probeField, offsetLong, fieldOffset)
            if (currentOffset == modifiedOffset) return offsetLong
        }
        throw noSuchFieldException ?: NoSuchFieldException("Field.offset")
    }
}
