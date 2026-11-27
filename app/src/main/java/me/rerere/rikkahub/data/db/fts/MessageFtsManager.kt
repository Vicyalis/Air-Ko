package me.rerere.rikkahub.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

private const val MAX_CACHED_CONVERSATIONS = 32

/** 进程内索引快照：[messages] 按 messageId 保存 UIMessage 引用，引用相同即内容相同。 */
internal data class FtsIndexState(
    val title: String,
    val updateAt: Long,
    val messages: Map<String, UIMessage>,
)

private class FtsIndexCache : LinkedHashMap<String, FtsIndexState>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FtsIndexState>?): Boolean =
        size > MAX_CACHED_CONVERSATIONS
}

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    private val indexStates = FtsIndexCache()

    /**
     * 增量索引：只有新增/变化的 UIMessage 才重新分词并写入，变动的元数据（标题/时间）单独 UPDATE。
     *
     * 旧实现每次保存都「删光本会话的索引 + 逐条 INSERT」，且没有事务；500 条消息的会话
     * 每轮 3~4 次保存就是 1500+ 次独立写事务 + 1500 次分词。
     */
    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        val previous = synchronized(indexStates) { indexStates[conversationId] }
        val currentMessages = LinkedHashMap<String, UIMessage>(conversation.messageNodes.size)
        val inserts = ArrayList<Array<Any?>>()
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val messageId = message.id.toString()
                currentMessages[messageId] = message
                if (previous?.messages?.get(messageId) !== message) {
                    val text = message.extractFtsText()
                    if (text.isNotBlank()) {
                        inserts += arrayOf(
                            text,
                            node.id.toString(),
                            messageId,
                            conversationId,
                            conversation.title,
                            conversation.updateAt.toEpochMilli().toString(),
                        )
                    }
                }
            }
        }
        val removedIds = previous?.messages?.keys?.filterNot { it in currentMessages }.orEmpty()
        val metaChanged = previous != null &&
            (previous.title != conversation.title ||
                previous.updateAt != conversation.updateAt.toEpochMilli())

        db.beginTransaction()
        try {
            if (previous == null) {
                // 进程重启后的首次索引：库里可能已有旧索引，先清掉避免重复行
                db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
            } else if (metaChanged) {
                db.execSQL(
                    "UPDATE message_fts SET title = ?, update_at = ? WHERE conversation_id = ?",
                    arrayOf(conversation.title, conversation.updateAt.toEpochMilli().toString(), conversationId),
                )
            }
            if (removedIds.isNotEmpty()) {
                val placeholders = removedIds.joinToString(",") { "?" }
                db.execSQL(
                    "DELETE FROM message_fts WHERE conversation_id = ? AND message_id IN ($placeholders)",
                    arrayOf<Any?>(conversationId, *removedIds.toTypedArray()),
                )
            }
            inserts.forEach { args ->
                db.execSQL(
                    "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                    args,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        synchronized(indexStates) {
            indexStates[conversationId] = FtsIndexState(
                title = conversation.title,
                updateAt = conversation.updateAt.toEpochMilli(),
                messages = currentMessages,
            )
        }
    }

    suspend fun updateTitle(conversationId: String, title: String) = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE message_fts SET title = ? WHERE conversation_id = ?", arrayOf(title, conversationId))
        synchronized(indexStates) {
            indexStates[conversationId]?.let { indexStates[conversationId] = it.copy(title = title) }
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        synchronized(indexStates) { indexStates.remove(conversationId) }
        Unit
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
        synchronized(indexStates) { indexStates.clear() }
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        // 只搜这些助手的对话，null 表示不限
        assistantIds: Collection<String>? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        if (assistantIds != null && assistantIds.isEmpty()) return@withContext results
        val assistantFilter = if (assistantIds != null) {
            """
            AND EXISTS (
                SELECT 1 FROM conversationentity AS conversation
                WHERE conversation.id = message_fts.conversation_id
                  AND conversation.assistant_id IN (${assistantIds.joinToString { "?" }})
            )
            """.trimIndent()
        } else {
            ""
        }
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            WHERE text MATCH jieba_query(?)
            $assistantFilter
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            arrayOf(keyword, *assistantIds.orEmpty().toTypedArray())
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
