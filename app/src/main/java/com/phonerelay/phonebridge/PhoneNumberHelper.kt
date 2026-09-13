package com.phonerelay.phonebridge

object PhoneNumberHelper {
    private val PAREN_PHONE_REGEX = Regex("""\(([\+0-9\s\-]{8,18})\)""")
    private val STANDALONE_BD_PHONE = Regex("""(?:\+880|0)?1[3-9]\d{2}[-.\s]?\d{6}""")
    private val GENERIC_PHONE = Regex("""\+?\d{9,15}""")

    fun extractNumber(event: NativeEvent): String? {
        // 1. Check title for "(+8801...)" or "(01...)"
        val parenMatch = PAREN_PHONE_REGEX.find(event.title)
        if (parenMatch != null) {
            val num = parenMatch.groupValues[1].replace(Regex("[^0-9+]"), "")
            if (isValidPhone(num)) return num
        }

        // 2. Check body for "(01...)"
        val bodyParen = PAREN_PHONE_REGEX.find(event.body)
        if (bodyParen != null) {
            val num = bodyParen.groupValues[1].replace(Regex("[^0-9+]"), "")
            if (isValidPhone(num)) return num
        }

        // 3. Check sender if sender itself is a phone number
        val cleanSender = event.sender.replace(Regex("[^0-9+]"), "")
        if (isValidPhone(cleanSender)) return cleanSender

        // 4. Check title for BD standalone number
        val titleBd = STANDALONE_BD_PHONE.find(event.title)
        if (titleBd != null) {
            val num = titleBd.value.replace(Regex("[^0-9+]"), "")
            if (isValidPhone(num)) return num
        }

        // 5. Check sender for BD standalone number
        val senderBd = STANDALONE_BD_PHONE.find(event.sender)
        if (senderBd != null) {
            val num = senderBd.value.replace(Regex("[^0-9+]"), "")
            if (isValidPhone(num)) return num
        }

        // 6. Generic phone fallback in title
        val genMatch = GENERIC_PHONE.find(event.title)
        if (genMatch != null) {
            val num = genMatch.value.replace(Regex("[^0-9+]"), "")
            if (isValidPhone(num)) return num
        }

        return null
    }

    private fun isValidPhone(num: String): Boolean {
        val digitsOnly = num.replace("+", "")
        return digitsOnly.length in 8..15 && digitsOnly.all { it.isDigit() }
    }
}
