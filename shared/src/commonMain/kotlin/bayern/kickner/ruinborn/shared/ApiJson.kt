package bayern.kickner.ruinborn.shared

import kotlinx.serialization.json.Json

/**
 * JSON settings for API and WebSocket. Unknown fields are ignored so older clients keep working with
 * newer servers (new DTO fields always have a default value).
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}

/** Names of the HTTP headers (concept section 15). */
object Headers {
    const val CLIENT_VERSION = "X-Client-Version"
    const val REQUEST_ID = "X-Request-Id"
    const val SERVER_TIME = "X-Server-Time"
}
