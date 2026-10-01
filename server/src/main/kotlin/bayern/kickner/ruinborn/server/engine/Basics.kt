package bayern.kickner.ruinborn.server.engine

import bayern.kickner.ruinborn.shared.model.ErrorCode
import kotnexlib.ResultOf2

/** Time source (ms UTC). Tests replace it with [ManualClock]. */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** Controllable clock for tests and simulation. */
class ManualClock(@Volatile var time: Long) : Clock {
    override fun now(): Long = time
    fun advance(ms: Long) {
        time += ms
    }
}

/** Expected game error with a code and German text. */
data class GameError(val code: ErrorCode, val message: String)

typealias Res<T> = ResultOf2<T, GameError>

fun <T> ok(value: T): Res<T> = ResultOf2.Success(value)

val OK: Res<Unit> = ResultOf2.Success(Unit)

fun fail(code: ErrorCode, message: String): ResultOf2.Failure<GameError> = ResultOf2.Failure(GameError(code, message))

/** Returns an error if [condition] is not met. Usage: `check(...)?.let { return it }`. */
inline fun ensure(condition: Boolean, code: ErrorCode, message: () -> String): ResultOf2.Failure<GameError>? =
    if (condition) null else fail(code, message())

/** Value of a successful result, or return of the error: `val x = r.orReturn { return it }`. */
inline fun <T> Res<T>.orReturn(onFailure: (ResultOf2.Failure<GameError>) -> Nothing): T = when (this) {
    is ResultOf2.Success -> value
    is ResultOf2.Failure -> onFailure(this)
}
