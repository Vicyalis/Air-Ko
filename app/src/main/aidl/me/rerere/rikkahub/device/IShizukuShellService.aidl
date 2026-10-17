// Shizuku UserService 接口：在 Shizuku 进程（shell/ADB 或 root 身份）里执行命令与写文件。
//
// 注意：Shizuku 要求 destroy 方法使用固定事务号 16777114，AIDL 规则又要求「要么全部分配 id、
// 要么全部不分配」，因此这里给所有方法显式分配了事务号。
package me.rerere.rikkahub.device;

interface IShizukuShellService {
    /** 执行 `sh -c command`，返回 JSON：{"ok":bool,"exit":int,"stdout":"…","stderr":"…","timedOut":bool} */
    String exec(String command, int timeoutMs) = 1;

    /** 向远端路径追加/覆盖写入一段字节（安装 APK 时分块传输，避免 binder 事务上限）。 */
    boolean writeFile(String path, in byte[] chunk, boolean append) = 2;

    /** 删除远端文件。 */
    boolean deleteFile(String path) = 3;

    /** Shizuku 解绑时调用的固定事务（16777114），实现里退出进程。 */
    void destroy() = 16777114;
}
