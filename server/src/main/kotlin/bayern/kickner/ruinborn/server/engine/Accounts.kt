package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.server.db.AccountT
import bayern.kickner.ruinborn.server.db.BuildingT
import bayern.kickner.ruinborn.server.db.MapObjectT
import bayern.kickner.ruinborn.server.db.PlayerT
import bayern.kickner.ruinborn.server.db.ServerMetaT
import bayern.kickner.ruinborn.server.db.SessionT
import bayern.kickner.ruinborn.shared.model.ErrorCode
import bayern.kickner.ruinborn.shared.model.MapObjectKind
import bayern.kickner.ruinborn.shared.model.Plot
import bayern.kickner.ruinborn.shared.model.Role
import bayern.kickner.ruinborn.shared.rules.MS_PER_DAY
import kotnexlib.crypto.HashAlgorithm
import kotnexlib.crypto.hash
import kotnexlib.crypto.hashIter
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Validity of a session after last use (concept section 13). */
const val SESSION_VALID_MS = 30 * MS_PER_DAY

/** Marks an inactivity shield (until the next login): 31.12.9999. */
const val INACTIVITY_SHIELD_UNTIL = 253_402_300_799_000L

/**
 * Password hashing with KotNexLib: salted, iterated SHA-256, stored as `v1$<salt Base64>$<hash hex>`.
 * Never runs in the engine, but in the HTTP handler on `Dispatchers.Default`.
 */
class PasswordHasher(private val iterations: Int) {
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val saltText = Base64.getEncoder().encodeToString(salt)
        return "v1\$" + saltText + "\$" + compute(saltText, password)
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 3 || parts[0] != "v1") return false
        val expected = parts[2].toByteArray(Charsets.US_ASCII)
        val actual = compute(parts[1], password).toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun compute(salt: String, password: String): String = "$salt:$password".hashIter(HashAlgorithm.SHA_256, iterations)
}

object Tokens {
    private val random = SecureRandom()

    /** 32 bytes from SecureRandom, Base64url without padding. */
    fun newToken(): String = ByteArray(32).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /** The database only stores the SHA-256 of the token as hex text. */
    fun hashOf(token: String): String = token.hash(HashAlgorithm.SHA_256)
}

fun Ctx.createSession(accountId: Long): String {
    val token = Tokens.newToken()
    SessionT.insert {
        it[tokenHash] = Tokens.hashOf(token)
        it[SessionT.accountId] = accountId
        it[createdAt] = now
        it[expiresAt] = now + SESSION_VALID_MS
    }
    return token
}

/** Registration: the handler checks the code and creates the password hash beforehand. */
fun Ctx.register(username: String, pwHash: String): Res<String> {
    val count = AccountT.selectAll().count()
    ensure(count < config.maxPlayers, ErrorCode.SERVER_FULL) { "Der Server ist voll." }?.let { return it }
    ensure(accountByName(username) == null, ErrorCode.NAME_TAKEN) { "Der Name ist bereits vergeben." }?.let { return it }
    val id = AccountT.insert {
        it[AccountT.username] = username
        it[AccountT.pwHash] = pwHash
        it[role] = if (config.isAdmin(username)) Role.ADMIN else Role.PLAYER
        it[createdAt] = now
        it[lastLoginAt] = now
        it[bannedUntil] = 0
        it[banReason] = null
        it[mutedUntil] = 0
    }[AccountT.id]
    createPlayer(id)
    return ok(createSession(id))
}

/** Login after the password was verified: create a session, recreate the game progress if needed (after "new world"). */
fun Ctx.login(accountId: Long): Res<String> {
    val acc = accountById(accountId) ?: return fail(ErrorCode.UNAUTHORIZED, "Anmeldung fehlgeschlagen.")
    ensure(acc.bannedUntil <= now, ErrorCode.BANNED) { banText(acc.bannedUntil, acc.banReason) }?.let { return it }
    AccountT.update({ AccountT.id eq accountId }) { it[lastLoginAt] = now }
    if (playerOrNull(accountId) == null) createPlayer(accountId)
    markActive(accountId)
    return ok(createSession(accountId))
}

fun banText(until: Long, reason: String?): String =
    "Dein Konto ist gesperrt" + (if (until >= INACTIVITY_SHIELD_UNTIL) " (dauerhaft)" else "") + (reason?.let { ": $it" } ?: ".")

/** Records activity. An inactivity shield ends at the next login. */
fun Ctx.markActive(pid: Long) {
    val p = playerOrNull(pid) ?: return
    PlayerT.update({ PlayerT.id eq pid }) {
        it[lastActiveAt] = now
        if (p.shieldUntil == INACTIVITY_SHIELD_UNTIL) it[shieldUntil] = 0
    }
}

fun Ctx.changePassword(accountId: Long, newHash: String, keepTokenHash: String?): Res<Unit> {
    AccountT.update({ AccountT.id eq accountId }) { it[pwHash] = newHash }
    if (keepTokenHash != null) SessionT.deleteWhere { (SessionT.accountId eq accountId) and (tokenHash neq keepTokenHash) }
    else SessionT.deleteWhere { SessionT.accountId eq accountId }
    return OK
}

fun Ctx.logout(tokenHash: String): Res<Unit> {
    SessionT.deleteWhere { SessionT.tokenHash eq tokenHash }
    return OK
}

// ---------------------------------------------------------------- Starting state

/** Creates the game progress with the starting state from section 2. */
fun Ctx.createPlayer(id: Long) {
    val s = balance.start
    PlayerT.insert {
        it[PlayerT.id] = id
        it[food] = s.food.toDouble()
        it[wood] = s.wood.toDouble()
        it[steel] = s.steel.toDouble()
        it[resAt] = now
        it[protectionUntil] = now + rules.hoursMs(balance.protection.newbieHours)
        it[shieldUntil] = 0
        it[catchupUntil] = 0
        it[lastActiveAt] = now
        it[maxZombieLevel] = 0
        it[zombiesDefeated] = 0
        it[troopsTrained] = 0
        it[skin] = balance.cosmetics.defaultSkin
        it[frame] = balance.cosmetics.defaultFrame
        it[allianceBlockUntil] = 0
    }
    s.buildings.forEach { (plot, level) -> setBuilding(id, plot, plot.fixedType!!, level) }
    s.resourceBuildings.forEach { (plot, type) -> setBuilding(id, plot, type, 1) }
    s.troops.forEach { changeTroops(id, it.type, it.tier, it.count, 0) }
    s.heroes.forEach { insertHero(id, it) }
    ensureHeroes(id, s.buildings[Plot.HQ] ?: 1)
    s.items.forEach { (item, n) -> addItem(id, item, n) }
    placeBase(id)
    // New players are checked against the current median for the catch-up bonus right away.
    applyCatchupFor(id, currentHqMedian())
    dirty(id)
}

/**
 * Random free tile in the starting zone with a minimum distance to the nearest base. If none is left,
 * any free tile of the zone will do.
 */
fun Ctx.placeBase(pid: Long) {
    val zone = balance.start.spawnZone
    val occupied = MapObjectT.selectAll().map { it[MapObjectT.x] to it[MapObjectT.y] }.toHashSet()
    val bases = MapObjectT.selectAll().where { MapObjectT.kind eq MapObjectKind.BASE }.map { it[MapObjectT.x] to it[MapObjectT.y] }
    val candidates = buildList {
        for (x in 0 until balance.map.width) for (y in 0 until balance.map.height) {
            if ((x to y) !in occupied && rules.zoneOf(x, y) == zone) add(x to y)
        }
    }
    check(candidates.isNotEmpty()) { "Kein freies Feld in Zone $zone" }
    val spaced = candidates.filter { (x, y) -> bases.all { (bx, by) -> rules.distance(x, y, bx, by) >= balance.start.minBaseDistance } }
    val (x, y) = (spaced.ifEmpty { candidates }).let { it[random.nextInt(it.size)] }
    val oid = MapObjectT.insert {
        it[kind] = MapObjectKind.BASE
        it[MapObjectT.x] = x
        it[MapObjectT.y] = y
        it[MapObjectT.zone] = zone
        it[level] = 0
        it[resType] = null
        it[amount] = 0
        it[playerId] = pid
        it[occupiedBy] = null
    }[MapObjectT.id]
    changedObjects += oid
}

// ---------------------------------------------------------------- Catch-up bonus

fun Ctx.currentHqMedian(): Int? =
    ServerMetaT.selectAll().where { ServerMetaT.key eq "hq_median" }.firstOrNull()?.get(ServerMetaT.value)?.toIntOrNull()

/** Players with an HQ level ≤ median − 3 get the catch-up bonus until the next reset. */
fun Ctx.applyCatchupFor(pid: Long, median: Int?) {
    val hq = BuildingT.selectAll().where { (BuildingT.playerId eq pid) and (BuildingT.plot eq Plot.HQ) }
        .firstOrNull()?.get(BuildingT.level) ?: 1
    val until = if (median != null && hq <= median - balance.catchup.levelsBelowMedian) nextDailyReset() else 0L
    PlayerT.update({ PlayerT.id eq pid }) { it[catchupUntil] = until }
}

fun hqOf(pid: Long): Int = buildings(pid)[Plot.HQ]?.level ?: 0
