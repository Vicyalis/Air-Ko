package me.rerere.workspace

import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Windows 上跳过需要 POSIX 环境的用例。
 *
 * 这些用例要么直接跑 `/bin/sh`（宿主 shell runner），要么在解压 rootfs 时创建符号链接
 * （Windows 非管理员/开发者模式下没有该特权），在 Windows 上必然失败，上游 master 同样失败，
 * 真机和 CI（Linux）不受影响。
 */
val skipOnWindows: TestRule = TestRule { base: Statement, _: Description ->
    object : Statement() {
        override fun evaluate() {
            Assume.assumeFalse(
                "该用例需要 POSIX shell / 符号链接权限（上游 master 同样失败）",
                System.getProperty("os.name").startsWith("Windows"),
            )
            base.evaluate()
        }
    }
}
