package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationStreamingUpdateTest {

    private fun conversation(): Conversation {
        val base = Conversation.ofId(Uuid.random(), assistantId = Uuid.random())
        val first = UIMessage.user("hello")
        val reply = UIMessage.assistant("partial")
        return base.copy(
            messageNodes = listOf(
                MessageNode(messages = listOf(first), selectIndex = 0),
                MessageNode(messages = listOf(reply), selectIndex = 0),
            ),
        )
    }

    @Test
    fun `streaming update only rebuilds the last node`() {
        val conversation = conversation()
        val last = conversation.currentMessages.last()
        val chunk = last.copy(
            parts = listOf(UIMessagePart.Text("partial reply continues")),
        )

        val updated = conversation.updateStreamingMessage(chunk)

        requireNotNull(updated)
        assertEquals(conversation.messageNodes.size, updated.messageNodes.size)
        // 历史节点必须是同一个实例：这保证不发生全量重建
        assertSame(conversation.messageNodes[0], updated.messageNodes[0])
        // 最后一条被替换
        assertEquals(
            "partial reply continues",
            (updated.currentMessages.last().parts.first() as UIMessagePart.Text).text,
        )
    }

    @Test
    fun `streaming update falls back when the message id differs`() {
        val conversation = conversation()

        // 消息列表里新增了一条（例如工具结果），最后一条 id 与当前最后节点不同
        val updated = conversation.updateStreamingMessage(UIMessage.assistant("brand new"))

        assertNull(updated)
    }

    @Test
    fun `streaming update never mutates the source conversation`() {
        val conversation = conversation()
        val last = conversation.currentMessages.last()
        val chunk = last.copy(parts = listOf(UIMessagePart.Text("changed")))

        conversation.updateStreamingMessage(chunk)

        assertEquals(
            "partial",
            (conversation.currentMessages.last().parts.first() as UIMessagePart.Text).text,
        )
    }
}
