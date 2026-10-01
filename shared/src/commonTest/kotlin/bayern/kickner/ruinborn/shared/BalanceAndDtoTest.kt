package bayern.kickner.ruinborn.shared

import bayern.kickner.ruinborn.shared.balance.BalanceCodec
import bayern.kickner.ruinborn.shared.balance.DefaultBalance
import bayern.kickner.ruinborn.shared.balance.validate
import bayern.kickner.ruinborn.shared.dto.BattleReport
import bayern.kickner.ruinborn.shared.dto.ReportDto
import bayern.kickner.ruinborn.shared.dto.ReportPayload
import bayern.kickner.ruinborn.shared.dto.SystemReport
import bayern.kickner.ruinborn.shared.dto.WsEvent
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.MarchKind
import bayern.kickner.ruinborn.shared.model.ReportKind
import bayern.kickner.ruinborn.shared.rules.Validation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BalanceAndDtoTest {
    private val b = BalanceCodec.default

    @Test
    fun defaultBalanceIsValid() {
        assertEquals(emptyList(), b.validate())
        assertEquals(20, b.achievements.size)
        assertEquals(15, b.research.techs.size)
    }

    @Test
    fun missingTopLevelKeyFails() {
        val broken = DefaultBalance.JSON.replace("\"rankings\"", "\"rankingz\"")
        val (balance, problems) = BalanceCodec.load(broken)
        assertNull(balance)
        assertTrue(problems.single().startsWith("balance.json unlesbar"))
    }

    @Test
    fun negativeValueIsRejected() {
        val broken = DefaultBalance.JSON.replace("\"maxRounds\": 20", "\"maxRounds\": -1")
        val (balance, problems) = BalanceCodec.load(broken)
        assertNull(balance)
        assertTrue(problems.any { "combat.maxRounds" in it })
    }

    @Test
    fun missingEffectIsRejected() {
        val broken = DefaultBalance.JSON.replace(", \"defensePerLevel\": 0.02", "")
        val (_, problems) = BalanceCodec.load(broken)
        assertTrue(problems.any { "defensePerLevel" in it })
    }

    @Test
    fun balanceRoundTrip() {
        assertEquals(b, BalanceCodec.decode(BalanceCodec.encode(b)))
        assertEquals(b, BalanceCodec.decodeLenient(DefaultBalance.JSON.replace("\"version\": 1,", "\"version\": 1, \"neu\": 5,")))
    }

    @Test
    fun wsEventHasConceptFormat() {
        val text = ApiJson.encodeToString(WsEvent.serializer(), WsEvent.Incoming(12, "Max", MarchKind.ATTACK, 1_790_000_000_000))
        assertEquals("""{"type":"incoming","marchId":12,"attacker":"Max","kind":"ATTACK","arriveAt":1790000000000}""", text)
        assertEquals("""{"type":"state_changed"}""", ApiJson.encodeToString(WsEvent.serializer(), WsEvent.StateChanged))
        assertIs<WsEvent.AllianceChanged>(ApiJson.decodeFromString(WsEvent.serializer(), """{"type":"alliance_changed","extra":1}"""))
    }

    @Test
    fun reportPayloadIsPolymorphic() {
        val dto = ReportDto(1, ReportKind.SYSTEM, 5, false, "Hallo", SystemReport("Titel", "Text"))
        val text = ApiJson.encodeToString(ReportDto.serializer(), dto)
        assertEquals(dto, ApiJson.decodeFromString(ReportDto.serializer(), text))
        val battle: ReportPayload = BattleReport(
            at = 1, x = 2, y = 3, marchKind = MarchKind.ATTACK, targetKind = MapObjectKind.ZOMBIE, isAttacker = true,
            won = true, attackerWon = true, fought = true, rounds = 3, attackers = emptyList(), defenders = emptyList(),
        )
        val bt = ApiJson.encodeToString(ReportPayload.serializer(), battle)
        assertTrue(bt.startsWith("""{"type":"battle""""))
        assertEquals(battle, ApiJson.decodeFromString(ReportPayload.serializer(), bt))
    }

    @Test
    fun validationRules() {
        assertNull(Validation.username("Max_01"))
        assertNotNull(Validation.username("ab"))
        assertNotNull(Validation.username("x".repeat(17)))
        assertNotNull(Validation.username("Mäx"))
        assertNull(Validation.password("12345678"))
        assertNotNull(Validation.password("1234567"))
        assertNull(Validation.allianceName(b, "Die Überlebenden 7"))
        assertNotNull(Validation.allianceName(b, "Ab"))
        assertNotNull(Validation.allianceName(b, " Abc"))
        assertNotNull(Validation.allianceName(b, "Abc!"))
        assertNull(Validation.allianceTag(b, "R2D"))
        assertNotNull(Validation.allianceTag(b, "r2d"))
        assertNotNull(Validation.allianceTag(b, "ABCD"))
        assertNull(Validation.chatText(b, "Hallo"))
        assertNotNull(Validation.chatText(b, "   "))
        assertNotNull(Validation.chatText(b, "x".repeat(301)))
    }
}
