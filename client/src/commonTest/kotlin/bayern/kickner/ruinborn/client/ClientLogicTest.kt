package bayern.kickner.ruinborn.client

import bayern.kickner.ruinborn.client.net.ApiClient
import bayern.kickner.ruinborn.client.net.Backoff
import bayern.kickner.ruinborn.client.net.ServerTime
import bayern.kickner.ruinborn.client.state.GameStore
import bayern.kickner.ruinborn.client.state.MapData
import bayern.kickner.ruinborn.client.state.mergeChat
import bayern.kickner.ruinborn.shared.dto.ChatMessageDto
import bayern.kickner.ruinborn.shared.dto.MapObjectDto
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientLogicTest {
    @Test
    fun backoffSequenceMatchesConcept() {
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L), (0..5).map { Backoff.delayFor(it) })
    }

    @Test
    fun serverTimeOffset() {
        var local = 1_000L
        val t = ServerTime { local }
        assertEquals(1_000L, t.now())
        t.update(5_000)
        assertEquals(4_000L, t.offsetMs)
        local = 2_000
        assertEquals(6_000L, t.now())
    }

    @Test
    fun mapChangesApply() {
        val a = MapObjectDto(1, MapObjectKind.ZOMBIE, 1, 1, 3)
        val b = MapObjectDto(2, MapObjectKind.FIELD, 2, 2, 1)
        val m = MapData(objects = mapOf(1L to a, 2L to b))
        val next = m.apply(WsEvent.MapChanged(objects = listOf(b.copy(amount = 5)), removedObjectIds = listOf(1)))
        assertEquals(setOf(2L), next.objects.keys)
        assertEquals(5L, next.objects.getValue(2).amount)
        assertEquals(b.id, next.byTile[2 to 2]?.id)
    }

    @Test
    fun chatMergeReplacesDeletedAndSorts() {
        fun msg(id: Long, text: String, deleted: Boolean = false) = ChatMessageDto(id, "world", 1, "a", text = text, createdAt = id, deleted = deleted)
        val merged = mergeChat(listOf(msg(2, "b"), msg(1, "a")), listOf(msg(3, "c"), msg(2, "Nachricht entfernt", true)))
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
        assertTrue(merged[1].deleted)
        assertEquals(3, mergeChat(emptyList(), (1..10L).map { msg(it, "x") }, keep = 3).size)
    }

    @Test
    fun storeNotifiesOnlyOnChange() {
        val store = GameStore()
        var calls = 0
        val remove = store.listen { calls++ }
        store.update { it.copy(connected = true) }
        store.update { it.copy(connected = true) }
        assertEquals(1, calls)
        remove()
        store.update { it.copy(connected = false) }
        assertEquals(1, calls)
    }

    @Test
    fun urlEncoding() {
        assertEquals("Die%20%C3%9Cberlebenden", ApiClient.encode("Die Überlebenden"))
    }
}
