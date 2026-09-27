package org.lsposed.corepatch.hook

import org.lsposed.corepatch.XposedHelper

open class BaseHook {
    open val name = "BaseHook"

    private var inited = false

    open fun hook() {

    }

    private fun hookInternal() {
        try {
            hook()
        } catch (t: Throwable) {
            XposedHelper.log("[$name] hook failed", t)
        }
    }

    fun init() {
        if (inited) return
        inited = true
        // 只在挂载失败时输出（hookInternal 内部已处理），避免每个 hook 刷两行
        hookInternal()
    }
}
