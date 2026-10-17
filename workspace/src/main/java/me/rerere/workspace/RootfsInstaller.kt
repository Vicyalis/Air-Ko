package me.rerere.workspace

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

class RootfsInstaller(
    private val manager: WorkspaceManager,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) {
    /**
     * 显式 UA: HttpURLConnection 默认不带 User-Agent, 部分镜像站的 WAF(如 USTC)会对
     * 空 UA / okhttp UA 直接回 403, 带上应用标识后各镜像站实测均放行。
     */
    private val userAgent: String = "Air-Ko-Android"

    fun install(
        root: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) {
        require(url.isNotBlank()) { "Rootfs download url is required" }
        manager.ensureWorkspace(root)
        // 允许在地址末尾附带 #sha256=<hex>: 固定住下载地址后可以强制校验完整性, 不附带则跳过
        val (downloadUrl, expectedSha256) = splitChecksum(url)
        val format = ArchiveFormat.fromUrl(downloadUrl)
        val tempDir = manager.tempDir(root)
        val archive = File(tempDir, "rootfs.${format.extension}")
        val stagingDir = File(tempDir, "rootfs-staging")
        val linuxDir = manager.linuxDir(root)

        var installed = false
        try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
            download(downloadUrl, archive, onProgress)
            if (expectedSha256 != null) {
                onProgress(RootfsInstallProgress(stage = RootfsInstallStage.VERIFYING))
                verifyChecksum(archive, expectedSha256)
            }
            extractTar(archive, stagingDir, format, onProgress)
            // Fedora 这类只发布 OCI 容器归档的发行版：把 layers 解出来当 rootfs
            val rootfsDir = extractOciIfPresent(stagingDir, tempDir, onProgress)
            linuxDir.deleteRecursively()
            require(rootfsDir.renameTo(linuxDir)) {
                "Failed to move rootfs into workspace"
            }
            patcher.patch(linuxDir)
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
            installed = true
        } finally {
            stagingDir.deleteRecursively()
            File(tempDir, OCI_ROOTFS_DIR_NAME).deleteRecursively()
            // 只有成功才丢弃压缩包: 失败时保留分片和来源标记, 下次同一地址可以续传或直接复用
            if (installed) {
                archive.delete()
                downloadMarkerOf(archive).delete()
            }
        }
    }

    /**
     * OCI 容器归档（`index.json` + `blobs/sha256/…`）→ rootfs。
     *
     * Fedora 的 release 页面只提供 OCI 归档：外层 tar.xz 解开后是标准 OCI 布局，
     * 真正的文件系统在 layers 里（gzip tar）。这里按 manifest 顺序逐层解到新的 rootfs 目录，
     * 每层解完处理 whiteout（`.wh.` 前缀）——容器镜像用它们表达删除。
     *
     * 不是 OCI 布局时原样返回 [stagingDir]（普通 rootfs tarball 走这条路）。
     */
    private fun extractOciIfPresent(
        stagingDir: File,
        tempDir: File,
        onProgress: (RootfsInstallProgress) -> Unit,
    ): File {
        val index = File(stagingDir, "index.json")
        val blobsDir = File(stagingDir, "blobs/sha256")
        if (!index.isFile || !blobsDir.isDirectory) return stagingDir

        val indexText = index.readText()
        val manifestDigest = DIGEST_REGEX
            .find(indexText.substringAfter("\"manifests\"", indexText))
            ?.groupValues?.get(1)
            ?: error("OCI index.json 里找不到 manifest 摘要")
        val manifestFile = File(blobsDir, manifestDigest)
        require(manifestFile.isFile) { "OCI manifest blob 缺失: $manifestDigest" }

        val layersSection = manifestFile.readText().substringAfter("\"layers\"", "")
        val layerDigests = DIGEST_REGEX.findAll(layersSection).map { it.groupValues[1] }.toList()
        require(layerDigests.isNotEmpty()) { "OCI manifest 里没有 layers" }

        onProgress(RootfsInstallProgress(stage = RootfsInstallStage.EXTRACTING))
        val rootfsDir = File(tempDir, OCI_ROOTFS_DIR_NAME)
        rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()
        layerDigests.forEachIndexed { index, digest ->
            checkInterrupted()
            val layerFile = File(blobsDir, digest)
            require(layerFile.isFile) { "OCI layer blob 缺失: $digest" }
            extractTar(layerFile, rootfsDir, ArchiveFormat.TAR_GZ, onProgress)
            applyWhiteouts(rootfsDir)
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.EXTRACTING))
        }
        stagingDir.deleteRecursively()
        return rootfsDir
    }

    /** 处理容器层的 whiteout 标记：`.wh.<name>` 删除同名条目，`.wh..wh..opq` 清空所在目录。 */
    private fun applyWhiteouts(rootfsDir: File) {
        val entries = rootfsDir.walkTopDown().toList()
        entries.forEach { file ->
            val name = file.name
            if (!name.startsWith(".wh.")) return@forEach
            val parent = file.parentFile ?: return@forEach
            if (name == ".wh..wh..opq") {
                parent.listFiles()?.forEach { child ->
                    if (child != file) child.deleteRecursively()
                }
                file.delete()
            } else {
                File(parent, name.removePrefix(".wh.")).deleteRecursively()
                file.delete()
            }
        }
    }

    /**
     * 带断点续传与有限重试的下载。
     *
     * 分片文件与 `<压缩包名>.src` 来源标记都留在临时目录里: 只有标记中的地址与本次完全一致才允许续传,
     * 因此重装同一个 rootfs 可以直接跳过已下载的部分, 而换地址时会丢弃旧分片避免互相污染。
     */
    private fun download(
        url: String,
        target: File,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        val marker = downloadMarkerOf(target)
        val resumable = target.isFile &&
            marker.isFile &&
            runCatching { marker.readText().trim() }.getOrNull() == url
        if (!resumable) {
            target.delete()
        }
        target.parentFile?.mkdirs()
        // 先落盘来源信息: 即使进程被杀, 下次也能凭它续传
        runCatching { marker.writeText(url) }

        var attempt = 0
        while (true) {
            attempt++
            val offset = if (target.isFile) target.length() else 0L
            try {
                downloadOnce(url, target, offset, onProgress)
                marker.delete()
                return
            } catch (cancelled: InterruptedException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!error.isRetryableDownloadError() || attempt >= DOWNLOAD_MAX_ATTEMPTS) throw error
                // 已下载的数据保留在 target 中, 下一次尝试用 Range 从断点继续
                Thread.sleep(DOWNLOAD_RETRY_DELAY_MS * attempt)
            }
        }
    }

    private fun downloadOnce(
        url: String,
        target: File,
        offset: Long,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent)
        try {
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=$offset-")
            }
            val code = connection.responseCode
            if (code == HTTP_RANGE_NOT_SATISFIABLE) {
                // 416: 本地分片不短于远端总长. 长度一致说明上次其实已经下完(只是标记没删), 直接当作成功;
                // 否则说明分片已损坏, 删掉后抛出可重试异常, 让上层从 0 重新开始
                val total = remoteLength(url)
                if (total != null && target.length() == total) return
                target.delete()
                throw IOException("Rootfs download range rejected, restarting: ${target.name}")
            }
            if (code !in 200..299) {
                throw RootfsDownloadHttpException(code, "Rootfs download failed: HTTP $code")
            }
            // 服务器可能忽略 Range 直接返回 200: 这时必须从头写, 不能追加
            val appending = offset > 0 && code == HttpURLConnection.HTTP_PARTIAL
            if (offset > 0 && !appending) {
                target.delete()
            }
            val totalBytes = totalBytesOf(connection, appending)
            var written = if (appending) offset else 0L
            onProgress(
                RootfsInstallProgress(
                    stage = RootfsInstallStage.DOWNLOADING,
                    bytesRead = written,
                    totalBytes = totalBytes,
                )
            )
            connection.inputStream.use { input ->
                FileOutputStream(target, appending).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastReported = written
                    while (true) {
                        checkInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = written
                            onProgress(
                                RootfsInstallProgress(
                                    stage = RootfsInstallStage.DOWNLOADING,
                                    bytesRead = written,
                                    totalBytes = totalBytes,
                                )
                            )
                        }
                    }
                }
            }
            // 服务器声明了总长却提前结束(代理截断等), 当失败处理, 上层会续传
            if (totalBytes != null && written != totalBytes) {
                throw IOException("Rootfs download incomplete: $written/$totalBytes")
            }
            onProgress(
                RootfsInstallProgress(
                    stage = RootfsInstallStage.DOWNLOADING,
                    bytesRead = written,
                    totalBytes = totalBytes ?: written,
                )
            )
        } finally {
            connection.disconnect()
        }
    }

    /** 续传时 Content-Length 只是本次分片的长度, 必须从 Content-Range 里取真实总长 */
    private fun totalBytesOf(connection: HttpURLConnection, partial: Boolean): Long? {
        if (partial) {
            return connection.getHeaderField("Content-Range")
                ?.substringAfterLast('/')
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        }
        return connection.contentLengthLong.takeIf { it > 0 }
    }

    private fun remoteLength(url: String): Long? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "HEAD"
        connection.setRequestProperty("User-Agent", userAgent)
        return try {
            if (connection.responseCode in 200..299) {
                connection.contentLengthLong.takeIf { it > 0 }
            } else {
                null
            }
        } catch (error: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun Throwable.isRetryableDownloadError(): Boolean = when (this) {
        is RootfsDownloadHttpException -> code == 408 || code == 429 || code >= 500
        is IOException -> true
        else -> false
    }

    /** 校验下载结果; url 里带的是 sha256 而不是 md5, 对 rootfs 这种几十 MB 的文件才有实际意义 */
    private fun verifyChecksum(archive: File, expected: String) {
        val normalized = expected.trim().lowercase(Locale.US)
        require(SHA256_HEX.matches(normalized)) { "Rootfs url contains invalid sha256: $expected" }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(CHECKSUM_BUFFER_SIZE)
            while (true) {
                checkInterrupted()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = buildString(64) {
            digest.digest().forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }
        require(actual == normalized) {
            "Rootfs checksum mismatch: expected $normalized, actual $actual"
        }
    }

    // 支持 "https://host/rootfs.tar.gz#sha256=<hex>" 写法; 非 sha256 的 fragment 一律忽略
    private fun splitChecksum(url: String): Pair<String, String?> {
        val hashIndex = url.indexOf('#')
        if (hashIndex < 0) return url to null
        val value = url.substring(hashIndex + 1)
            .substringAfter("sha256=", "")
            .trim()
            .takeIf { it.isNotEmpty() }
        return url.substring(0, hashIndex) to value
    }

    private fun downloadMarkerOf(archive: File) = File(archive.parentFile, "${archive.name}.src")

    internal fun extractTar(
        archive: File,
        targetDir: File,
        format: ArchiveFormat = ArchiveFormat.fromFile(archive),
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        // 缓冲区在整次解压中复用: Ubuntu 这类 rootfs 有数千条目, 逐条目分配 64KB 缓冲会制造几百 MB 垃圾;
        // 解压流外面再套一层 64KB BufferedInputStream, 否则 tar 头(512B)和拷贝循环都会走默认 8KB 缓冲
        val copyBuffer = ByteArray(BUFFER_SIZE)
        val headerBlock = ByteArray(TAR_BLOCK_SIZE)
        // 根目录只规范化一次: 逐条目 realpath 会在上万条目时明显拖慢解压
        val root = targetDir.canonicalFile
        var cachedParent: String? = null
        var entries = 0
        var reportedEntries = 0
        var reportedAt = System.nanoTime()
        val archiveInput = BufferedInputStream(archive.inputStream(), BUFFER_SIZE)
        BufferedInputStream(format.wrapStream(archiveInput), BUFFER_SIZE).use { input ->
            var pendingName: String? = null
            var pendingLinkName: String? = null
            while (true) {
                checkInterrupted()
                val rawHeader = input.readTarHeader(headerBlock) ?: break
                val header = rawHeader.copy(
                    name = pendingName ?: rawHeader.name,
                    linkName = pendingLinkName ?: rawHeader.linkName,
                )
                pendingName = null
                pendingLinkName = null
                if (header.name.isBlank()) {
                    input.skipFully(header.size.paddedTarSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_NAME) {
                    pendingName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_LINK) {
                    pendingLinkName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.PAX) {
                    val pax = parsePax(input.readExactly(header.size).toString(Charsets.UTF_8))
                    pendingName = pax["path"]
                    pendingLinkName = pax["linkpath"]
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                val target = safeResolve(root, header.name)
                // 相邻条目通常同目录, 缓存上一个父目录可以省掉大量重复的 mkdirs 系统调用
                val parent = target.parentFile
                if (parent != null && parent.path != cachedParent) {
                    parent.mkdirs()
                    cachedParent = parent.path
                }
                when (header.type) {
                    TarEntryType.DIRECTORY -> target.mkdirs()
                    TarEntryType.SYMLINK -> createSymlink(root, target, header.linkName)
                    TarEntryType.HARDLINK -> createHardLink(root, target, header.linkName)
                    TarEntryType.FILE -> {
                        target.outputStream().use { output ->
                            input.copyExactly(output, header.size, copyBuffer)
                        }
                        target.applyMode(header.mode)
                    }

                    // LONG_NAME/LONG_LINK/PAX 已在上方 continue, 这里只有 OTHER 可达;
                    // 数据区统一由下方的非 FILE skip 跳过, 这里再 skip 会双重跳过导致后续 header 错位
                    TarEntryType.LONG_NAME,
                    TarEntryType.LONG_LINK,
                    TarEntryType.PAX,
                    TarEntryType.OTHER -> Unit
                }
                if (header.type != TarEntryType.FILE) {
                    input.skipFully(header.size)
                }
                input.skipFully(header.size.paddingSize())
                if (header.modTime > 0 && header.type != TarEntryType.SYMLINK) {
                    target.setLastModified(header.modTime * 1000)
                }
                entries++
                val now = System.nanoTime()
                // 进度按条目+时间双阈值节流: 上万条目逐一回调会让 UI 每秒重组上千次
                if (entries - reportedEntries >= PROGRESS_ENTRY_STEP || now - reportedAt >= PROGRESS_INTERVAL_NANOS) {
                    reportedEntries = entries
                    reportedAt = now
                    onProgress(
                        RootfsInstallProgress(
                            stage = RootfsInstallStage.EXTRACTING,
                            entriesExtracted = entries,
                            currentEntry = header.name,
                        )
                    )
                }
            }
        }
        // 收尾补报一次真实条目数, 否则进度会停在最后一个节流点上
        if (entries != reportedEntries) {
            onProgress(
                RootfsInstallProgress(
                    stage = RootfsInstallStage.EXTRACTING,
                    entriesExtracted = entries,
                )
            )
        }
    }

    // root 必须是 targetDir 的 canonicalFile, 调用方已规范化
    private fun createSymlink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val linkTarget = if (File(linkName).isAbsolute) {
            File(linkName)
        } else {
            val resolved = File(target.parentFile ?: root, linkName).canonicalFile
            require(resolved.path == root.path || resolved.path.startsWith(root.path + File.separator)) {
                "Symlink escapes rootfs: ${target.name}"
            }
            (target.parentFile ?: root).toPath().relativize(resolved.toPath()).toFile()
        }
        target.delete()
        Files.createSymbolicLink(target.toPath(), linkTarget.toPath())
    }

    // root 必须是 targetDir 的 canonicalFile, 调用方已规范化
    private fun createHardLink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val source = safeResolve(root, linkName)
        if (!source.exists()) return
        target.delete()
        runCatching {
            Files.createLink(target.toPath(), source.toPath())
        }.recoverCatching { error ->
            if (error !is IOException &&
                error !is UnsupportedOperationException &&
                error !is SecurityException
            ) {
                throw error
            }
            source.copyTo(target, overwrite = true)
            target.setReadable(source.canRead(), false)
            target.setWritable(source.canWrite(), true)
            target.setExecutable(source.canExecute(), false)
        }.getOrThrow()
    }

    private fun InputStream.readTarHeader(headerBlock: ByteArray): TarHeader? {
        val read = readFullyOrEnd(headerBlock)
        if (read == 0) return null
        if (read < TAR_BLOCK_SIZE) throw EOFException("Unexpected EOF while reading tar header")
        if (headerBlock.all { it == 0.toByte() }) return null

        val name = headerBlock.string(0, 100)
        val prefix = headerBlock.string(345, 155)
        val fullName = listOf(prefix, name)
            .filter { it.isNotBlank() }
            .joinToString("/")
        return TarHeader(
            name = normalizeTarPath(fullName),
            mode = headerBlock.octal(100, 8).toInt(),
            size = headerBlock.octal(124, 12),
            modTime = headerBlock.octal(136, 12),
            type = when (headerBlock[156].toInt().toChar()) {
                '0', '\u0000' -> TarEntryType.FILE
                '5' -> TarEntryType.DIRECTORY
                '2' -> TarEntryType.SYMLINK
                '1' -> TarEntryType.HARDLINK
                'L' -> TarEntryType.LONG_NAME
                'K' -> TarEntryType.LONG_LINK
                'x' -> TarEntryType.PAX
                else -> TarEntryType.OTHER
            },
            linkName = headerBlock.string(157, 100),
        )
    }

    private fun parsePax(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val length = text.substring(index, space).toIntOrNull() ?: break
            val end = (index + length).coerceAtMost(text.length)
            val record = text.substring(space + 1, end).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) {
                result[record.substring(0, equals)] = record.substring(equals + 1)
            }
            index += length
        }
        return result
    }

    // 协程取消时调用方通过 runInterruptible 将取消转成线程中断, 这里在阻塞循环中检测并尽早退出,
    // 避免离开页面后仍继续下载/解压并向已清空的 StateFlow 推送进度
    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Rootfs install cancelled")
        }
    }

    private fun InputStream.copyExactly(output: java.io.OutputStream, bytes: Long, buffer: ByteArray) {
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Unexpected EOF while extracting tar entry")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.readExactly(bytes: Long): ByteArray {
        // 只用于 LONG_NAME/LONG_LINK/PAX 这类元数据, 限制上限可避免损坏的归档申请超大数组
        require(bytes <= MAX_INLINE_ENTRY_BYTES) { "Tar metadata entry is too large: $bytes" }
        val buffer = ByteArray(bytes.toInt())
        val read = readFullyOrEnd(buffer)
        if (read != buffer.size) throw EOFException("Unexpected EOF while reading tar entry")
        return buffer
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                throw EOFException("Unexpected EOF while skipping tar data")
            }
        }
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    // root 必须是 canonicalFile, 调用方已规范化
    private fun safeResolve(root: File, path: String): File {
        val normalized = normalizeTarPath(path)
        val target = File(root, normalized).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Rootfs entry escapes target directory: $path"
        }
        return target
    }

    private fun File.applyMode(mode: Int) {
        setReadable(mode and 0b100_000_000 != 0, false)
        setWritable(mode and 0b010_000_000 != 0, true)
        setExecutable(mode and 0b001_000_000 != 0, false)
    }

    // 根目录条目 (如 Alpine minirootfs 开头的 "./") 规范化后为空串, 由调用方跳过
    private fun normalizeTarPath(path: String): String {
        require(!path.contains('\u0000')) { "Rootfs entry path contains invalid character" }
        val segments = path
            .replace('\\', '/')
            .trim()
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
        require(segments.none { it == ".." }) { "Rootfs entry escapes target directory: $path" }
        return segments.joinToString("/")
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        val end = (offset until offset + length)
            .firstOrNull { this[it] == 0.toByte() }
            ?: (offset + length)
        return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long {
        val value = string(offset, length)
            .trim()
            .lowercase(Locale.US)
            .trimEnd('\u0000')
        return if (value.isBlank()) 0L else value.toLong(8)
    }

    private fun Long.paddingSize(): Long = (TAR_BLOCK_SIZE - (this % TAR_BLOCK_SIZE)).let {
        if (it == TAR_BLOCK_SIZE.toLong()) 0L else it
    }

    private fun Long.paddedTarSize(): Long = this + paddingSize()

    private data class TarHeader(
        val name: String,
        val mode: Int,
        val size: Long,
        val modTime: Long,
        val type: TarEntryType,
        val linkName: String,
    )

    private enum class TarEntryType {
        FILE,
        DIRECTORY,
        SYMLINK,
        HARDLINK,
        LONG_NAME,
        LONG_LINK,
        PAX,
        OTHER,
    }

    enum class ArchiveFormat(val extension: String) {
        TAR_GZ("tar.gz") {
            override fun wrapStream(input: InputStream): InputStream = GZIPInputStream(input)
        },
        TAR_XZ("tar.xz") {
            override fun wrapStream(input: InputStream): InputStream = XZInputStream(input)
        };

        abstract fun wrapStream(input: InputStream): InputStream

        companion object {
            fun fromUrl(url: String): ArchiveFormat {
                val path = url.substringBefore('?').substringBefore('#')
                return when {
                    path.endsWith(".tar.xz") || path.endsWith(".txz") -> TAR_XZ
                    else -> TAR_GZ
                }
            }

            fun fromFile(file: File): ArchiveFormat = fromUrl(file.name)
        }
    }

    companion object {
        private const val TAR_BLOCK_SIZE = 512
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024
        // Android 的 java.net.HttpURLConnection 没有 416 的常量, 这里自己定义
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val PROGRESS_ENTRY_STEP = 64
        private const val PROGRESS_INTERVAL_NANOS = 100_000_000L
        private const val MAX_INLINE_ENTRY_BYTES = 1024L * 1024
        private const val CHECKSUM_BUFFER_SIZE = 256 * 1024
        private const val DOWNLOAD_MAX_ATTEMPTS = 3
        private const val DOWNLOAD_RETRY_DELAY_MS = 1_500L
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()

        /** OCI 解包时的临时 rootfs 目录名（与 staging 同级）。 */
        private const val OCI_ROOTFS_DIR_NAME = "rootfs-oci"

        /** OCI 的 blob 摘要：`"digest":"sha256:<64 hex>"`。 */
        private val DIGEST_REGEX = Regex("\"digest\"\\s*:\\s*\"sha256:([0-9a-f]{64})\"")
    }
}

/** 服务器返回非 2xx 时抛出; 只有 408/429/5xx 会被重试 */
private class RootfsDownloadHttpException(
    val code: Int,
    message: String,
) : IOException(message)
