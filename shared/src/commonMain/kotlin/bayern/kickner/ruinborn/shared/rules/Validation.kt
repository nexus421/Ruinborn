package bayern.kickner.ruinborn.shared.rules

import bayern.kickner.ruinborn.shared.balance.Balance

/**
 * Input rules that server and client check the same way (concept sections 9, 11 and 13).
 * Each function returns `null` if the input is valid, otherwise a German error text.
 */
object Validation {
    const val USERNAME_MIN = 3
    const val USERNAME_MAX = 16
    const val PASSWORD_MIN = 8
    const val PASSWORD_MAX = 128

    private val usernameRegex = Regex("^[A-Za-z0-9_]+$")
    private val tagRegex = Regex("^[A-Z0-9]+$")

    fun username(name: String): String? = when {
        name.length !in USERNAME_MIN..USERNAME_MAX -> "Der Benutzername muss $USERNAME_MIN bis $USERNAME_MAX Zeichen lang sein."
        usernameRegex.matches(name).not() -> "Der Benutzername darf nur A–Z, a–z, 0–9 und _ enthalten."
        else -> null
    }

    fun password(pw: String): String? =
        if (pw.length !in PASSWORD_MIN..PASSWORD_MAX) "Das Passwort muss $PASSWORD_MIN bis $PASSWORD_MAX Zeichen lang sein." else null

    /** Alliance name: letters (including umlauts), digits and spaces. Not only spaces, no leading or trailing spaces. */
    fun allianceName(b: Balance, name: String): String? {
        val a = b.alliance
        return when {
            name.length !in a.nameMinLength..a.nameMaxLength -> "Der Allianzname muss ${a.nameMinLength} bis ${a.nameMaxLength} Zeichen lang sein."
            name.all { it.isLetterOrDigit() || it == ' ' }.not() -> "Der Allianzname darf nur Buchstaben, Ziffern und Leerzeichen enthalten."
            name.isBlank() || name.trim() != name || "  " in name -> "Der Allianzname darf nicht mit Leerzeichen beginnen oder enden."
            else -> null
        }
    }

    fun allianceTag(b: Balance, tag: String): String? = when {
        tag.length != b.alliance.tagLength -> "Das Kürzel muss genau ${b.alliance.tagLength} Zeichen haben."
        tagRegex.matches(tag).not() -> "Das Kürzel darf nur A–Z und 0–9 enthalten."
        else -> null
    }

    fun allianceDescription(b: Balance, text: String): String? =
        if (text.length > b.alliance.descriptionMaxLength) "Die Beschreibung darf höchstens ${b.alliance.descriptionMaxLength} Zeichen haben." else null

    fun chatText(b: Balance, text: String): String? = when {
        text.isBlank() -> "Die Nachricht ist leer."
        text.length > b.chat.maxLength -> "Eine Nachricht darf höchstens ${b.chat.maxLength} Zeichen haben."
        else -> null
    }
}
