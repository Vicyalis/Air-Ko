package me.rerere.rikkahub.data.datastore

import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Windows 上跳过依赖 POSIX 文件语义的用例。
 *
 * DataStore 写盘是「写 .tmp 再覆盖重命名」，Windows 不允许重命名一个还打开着的文件，
 * 于是这些用例会以 `Unable to rename ... This likely means that there are multiple instances of DataStore`
 * 假红。上游 master 上同样失败（已单独跑过确认），真机和 CI（Linux）不受影响。
 */
val skipOnWindows: TestRule = TestRule { base: Statement, _: Description ->
    object : Statement() {
        override fun evaluate() {
            Assume.assumeFalse(
                "DataStore 覆盖重命名在 Windows 上不可用（上游 master 同样失败）",
                System.getProperty("os.name").startsWith("Windows"),
            )
            base.evaluate()
        }
    }
}
