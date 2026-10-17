package me.rerere.rikkahub.web.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.workspace.WorkspaceStorageArea
import java.io.ByteArrayOutputStream
import java.io.OutputStream

private const val WORKSPACE_UPLOAD_MAX_BYTES = 200 * 1024 * 1024 // 200 MB

@Serializable
private data class WorkspaceRootDto(val id: String, val name: String)

@Serializable
private data class WorkspaceEntryDto(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

@Serializable
private data class WorkspaceListDto(val path: String, val entries: List<WorkspaceEntryDto>)

@Serializable
private data class WorkspaceUploadDto(val uploaded: Int, val names: List<String>)

/**
 * 内置 Linux 共享文件夹（/workspace）的网页 API。
 *
 * 用途：同一局域网里的另一台手机/电脑打开 `http://<地址>:<端口>/upload.html` 就能把文件
 * 直接传进这个文件夹——传完在 Linux 终端和 AI 的文件工具里立即可见。
 *
 * 与 /api/files/upload（聊天附件）相互独立：这里落盘的位置就是工作区 `files` 目录本身。
 */
fun Route.workspaceFilesRoutes(repository: WorkspaceRepository) {
    route("/workspace") {
        // GET /api/workspace/roots - 工作区列表（网页端下拉选择）
        get("/roots") {
            val roots = repository.listFlow().first()
            call.respond(roots.map { WorkspaceRootDto(it.id, it.name) })
        }

        // GET /api/workspace/list?root=<id>&path=<相对路径> - 列目录
        get("/list") {
            val root = call.resolveRoot(repository)
            val path = call.request.queryParameters["path"].orEmpty()
            val entries = repository.listFiles(root, WorkspaceStorageArea.FILES, path)
            call.respond(
                WorkspaceListDto(
                    path = path,
                    entries = entries.map {
                        WorkspaceEntryDto(
                            name = it.name,
                            path = it.path,
                            isDirectory = it.isDirectory,
                            sizeBytes = it.sizeBytes,
                        )
                    },
                )
            )
        }

        // POST /api/workspace/upload?root=<id>&path=<相对路径> - 上传（multipart，字段名任意）
        post("/upload") {
            val root = call.resolveRoot(repository)
            val path = call.request.queryParameters["path"].orEmpty()
            val multipart = call.receiveMultipart()
            val names = mutableListOf<String>()

            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (part is PartData.FileItem) {
                        val fileName = part.originalFileName
                            ?.takeIf { it.isNotBlank() }
                            ?.substringAfterLast('/')
                            ?.substringAfterLast('\\')
                            ?: "file"
                        val bytes = readPartBytes(part, WORKSPACE_UPLOAD_MAX_BYTES)
                        if (bytes.isEmpty()) continue
                        repository.importFile(
                            id = root,
                            area = WorkspaceStorageArea.FILES,
                            destinationPath = path,
                            fileName = fileName,
                            inputStream = bytes.inputStream(),
                        )
                        names += fileName
                    }
                } finally {
                    part.dispose()
                }
            }

            if (names.isEmpty()) {
                throw BadRequestException("No files uploaded")
            }
            call.respond(HttpStatusCode.Created, WorkspaceUploadDto(uploaded = names.size, names = names))
        }

        // GET /api/workspace/download?root=<id>&path=<相对路径> - 下载
        get("/download") {
            val root = call.resolveRoot(repository)
            val path = call.request.queryParameters["path"]
                ?: throw BadRequestException("Missing path")
            val name = path.substringAfterLast('/').ifBlank { "file" }
            call.response.header("Content-Type", ContentType.Application.OctetStream.toString())
            call.response.header("Content-Disposition", "attachment; filename=\"$name\"")
            call.respondOutputStream {
                repository.exportFile(root, WorkspaceStorageArea.FILES, path, this)
            }
        }

        // POST /api/workspace/mkdir?root=<id>&path=<父目录>&name=<目录名> - 新建文件夹
        post("/mkdir") {
            val root = call.resolveRoot(repository)
            val path = call.request.queryParameters["path"].orEmpty()
            val name = call.request.queryParameters["name"]?.trim().orEmpty()
            if (name.isBlank() || name.contains('/') || name.contains('\\') || name == "." || name == "..") {
                throw BadRequestException("Invalid folder name")
            }
            val target = if (path.isBlank()) name else "$path/$name"
            repository.createDirectory(root, WorkspaceStorageArea.FILES, target)
            call.respond(HttpStatusCode.Created, mapOf("status" to "created", "path" to target))
        }

        // DELETE /api/workspace/file?root=<id>&path=<相对路径>&recursive=1 - 删除
        delete("/file") {
            val root = call.resolveRoot(repository)
            val path = call.request.queryParameters["path"]
                ?: throw BadRequestException("Missing path")
            val recursive = call.request.queryParameters["recursive"] == "1"
            val deleted = repository.deleteFile(root, WorkspaceStorageArea.FILES, path, recursive)
            if (!deleted) throw NotFoundException("File not found")
            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.resolveRoot(
    repository: WorkspaceRepository,
): String {
    val requested = request.queryParameters["root"]
    if (!requested.isNullOrBlank()) {
        repository.getById(requested) ?: throw NotFoundException("Workspace not found")
        return requested
    }
    return repository.listFlow().first().firstOrNull()?.id
        ?: throw NotFoundException("No workspace available")
}

private suspend fun readPartBytes(part: PartData.FileItem, maxBytes: Int): ByteArray {
    val input = part.provider()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0

    while (true) {
        val read = input.readAvailable(buffer, 0, buffer.size)
        if (read <= 0) break

        totalBytes += read
        if (totalBytes > maxBytes) {
            throw BadRequestException("File too large: max ${maxBytes / (1024 * 1024)} MB")
        }

        output.write(buffer, 0, read)
    }

    return output.toByteArray()
}
