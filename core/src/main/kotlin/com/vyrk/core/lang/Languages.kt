package com.vyrk.core.lang

/**
 * Language codes, names and detection. Codes are ISO 639-2/B three-letter codes where known
 * ("eng", "spa", "por"…), which is what OpenSubtitles and most add-ons use.
 * Flags are a visual supplement only and are always shown next to the name.
 */
object Languages {
    data class Lang(val code: String, val iso1: String, val name: String, val flag: String, val aliases: List<String>)

    val all: List<Lang> = listOf(
        Lang("eng", "en", "English", "🇬🇧", listOf("english", "eng", "en", "🇬🇧", "🇺🇸")),
        Lang("spa", "es", "Spanish", "🇪🇸", listOf("spanish", "español", "espanol", "spa", "es", "castellano", "latino", "🇪🇸", "🇲🇽")),
        Lang("por", "pt", "Portuguese", "🇵🇹", listOf("portuguese", "português", "portugues", "por", "pt", "pt-br", "pob", "🇵🇹", "🇧🇷")),
        Lang("fre", "fr", "French", "🇫🇷", listOf("french", "français", "francais", "fre", "fra", "fr", "vff", "vfq", "truefrench", "🇫🇷")),
        Lang("ger", "de", "German", "🇩🇪", listOf("german", "deutsch", "ger", "deu", "de", "🇩🇪")),
        Lang("ita", "it", "Italian", "🇮🇹", listOf("italian", "italiano", "ita", "it", "🇮🇹")),
        Lang("hin", "hi", "Hindi", "🇮🇳", listOf("hindi", "hin", "hi", "🇮🇳")),
        Lang("jpn", "ja", "Japanese", "🇯🇵", listOf("japanese", "jpn", "ja", "jap", "🇯🇵")),
        Lang("kor", "ko", "Korean", "🇰🇷", listOf("korean", "kor", "ko", "🇰🇷")),
        Lang("chi", "zh", "Chinese", "🇨🇳", listOf("chinese", "chi", "zho", "zh", "mandarin", "cantonese", "🇨🇳", "🇹🇼", "🇭🇰")),
        Lang("rus", "ru", "Russian", "🇷🇺", listOf("russian", "rus", "ru", "🇷🇺")),
        Lang("ara", "ar", "Arabic", "🇸🇦", listOf("arabic", "ara", "ar", "🇸🇦", "🇦🇪")),
        Lang("tur", "tr", "Turkish", "🇹🇷", listOf("turkish", "tur", "tr", "🇹🇷")),
        Lang("pol", "pl", "Polish", "🇵🇱", listOf("polish", "pol", "pl", "🇵🇱")),
        Lang("dut", "nl", "Dutch", "🇳🇱", listOf("dutch", "dut", "nld", "nl", "🇳🇱")),
        Lang("swe", "sv", "Swedish", "🇸🇪", listOf("swedish", "swe", "sv", "🇸🇪")),
        Lang("nor", "no", "Norwegian", "🇳🇴", listOf("norwegian", "nor", "no", "nob", "🇳🇴")),
        Lang("dan", "da", "Danish", "🇩🇰", listOf("danish", "dan", "da", "🇩🇰")),
        Lang("fin", "fi", "Finnish", "🇫🇮", listOf("finnish", "fin", "fi", "🇫🇮")),
        Lang("gre", "el", "Greek", "🇬🇷", listOf("greek", "gre", "ell", "el", "🇬🇷")),
        Lang("heb", "he", "Hebrew", "🇮🇱", listOf("hebrew", "heb", "he", "🇮🇱")),
        Lang("hun", "hu", "Hungarian", "🇭🇺", listOf("hungarian", "hun", "hu", "🇭🇺")),
        Lang("cze", "cs", "Czech", "🇨🇿", listOf("czech", "cze", "ces", "cs", "🇨🇿")),
        Lang("rum", "ro", "Romanian", "🇷🇴", listOf("romanian", "rum", "ron", "ro", "🇷🇴")),
        Lang("ukr", "uk", "Ukrainian", "🇺🇦", listOf("ukrainian", "ukr", "uk", "🇺🇦")),
        Lang("tha", "th", "Thai", "🇹🇭", listOf("thai", "tha", "th", "🇹🇭")),
        Lang("vie", "vi", "Vietnamese", "🇻🇳", listOf("vietnamese", "vie", "vi", "🇻🇳")),
        Lang("ind", "id", "Indonesian", "🇮🇩", listOf("indonesian", "ind", "id", "🇮🇩")),
        Lang("tam", "ta", "Tamil", "🇮🇳", listOf("tamil", "tam", "ta")),
        Lang("tel", "te", "Telugu", "🇮🇳", listOf("telugu", "tel", "te")),
        Lang("mal", "ml", "Malayalam", "🇮🇳", listOf("malayalam", "mal", "ml")),
        Lang("ben", "bn", "Bengali", "🇧🇩", listOf("bengali", "ben", "bn", "🇧🇩")),
        Lang("bul", "bg", "Bulgarian", "🇧🇬", listOf("bulgarian", "bul", "bg", "🇧🇬")),
        Lang("hrv", "hr", "Croatian", "🇭🇷", listOf("croatian", "hrv", "hr", "scr", "🇭🇷")),
        Lang("srp", "sr", "Serbian", "🇷🇸", listOf("serbian", "srp", "sr", "scc", "🇷🇸")),
        Lang("slv", "sl", "Slovenian", "🇸🇮", listOf("slovenian", "slv", "sl", "🇸🇮")),
        Lang("slo", "sk", "Slovak", "🇸🇰", listOf("slovak", "slo", "slk", "sk", "🇸🇰")),
        Lang("est", "et", "Estonian", "🇪🇪", listOf("estonian", "est", "et", "🇪🇪")),
        Lang("lav", "lv", "Latvian", "🇱🇻", listOf("latvian", "lav", "lv", "🇱🇻")),
        Lang("lit", "lt", "Lithuanian", "🇱🇹", listOf("lithuanian", "lit", "lt", "🇱🇹")),
        Lang("per", "fa", "Persian", "🇮🇷", listOf("persian", "farsi", "per", "fas", "fa", "🇮🇷")),
        Lang("may", "ms", "Malay", "🇲🇾", listOf("malay", "may", "msa", "ms", "🇲🇾")),
        Lang("ice", "is", "Icelandic", "🇮🇸", listOf("icelandic", "ice", "isl", "is", "🇮🇸")),
        Lang("cat", "ca", "Catalan", "🏴", listOf("catalan", "cat", "ca")),
        Lang("baq", "eu", "Basque", "🏴", listOf("basque", "baq", "eus", "eu")),
        Lang("glg", "gl", "Galician", "🏴", listOf("galician", "glg", "gl")),
        Lang("mac", "mk", "Macedonian", "🇲🇰", listOf("macedonian", "mac", "mkd", "mk", "🇲🇰")),
        Lang("alb", "sq", "Albanian", "🇦🇱", listOf("albanian", "alb", "sqi", "sq", "🇦🇱")),
        Lang("bos", "bs", "Bosnian", "🇧🇦", listOf("bosnian", "bos", "bs", "🇧🇦")),
        Lang("urd", "ur", "Urdu", "🇵🇰", listOf("urdu", "urd", "ur", "🇵🇰")),
        Lang("tgl", "tl", "Filipino", "🇵🇭", listOf("filipino", "tagalog", "tgl", "fil", "tl", "🇵🇭")),
    )

    private val byAlias: Map<String, Lang> = buildMap { all.forEach { l -> l.aliases.forEach { put(it, l) } } }

    /** Normalises any code, name or flag to an ISO 639-2 code; unknown input is returned lower-cased. */
    fun normalise(input: String): String {
        val k = input.trim().lowercase()
        byAlias[k]?.let { return it.code }
        byAlias[k.substringBefore('-')]?.let { return it.code }
        return k
    }

    fun find(input: String): Lang? = byAlias[input.trim().lowercase()] ?: byAlias[input.trim().lowercase().substringBefore('-')]

    /** Display name ("English"); unknown codes show the code upper-cased rather than nothing. */
    fun name(code: String): String = find(code)?.name ?: code.uppercase()
    fun flag(code: String): String = find(code)?.flag ?: ""

    /** "English 🇬🇧" — name first, flag as a supplement. */
    fun label(code: String): String = listOf(name(code), flag(code)).filter { it.isNotEmpty() }.joinToString(" ")

    // Short codes like "it" or "no" are too ambiguous inside release names; only use
    // three-letter codes, full names and flags when scanning free text.
    private val tokenRx = Regex("""[\p{L}]{3,}|[\x{1F1E6}-\x{1F1FF}]{2}""")

    /** Languages mentioned in stream text, in first-seen order. */
    fun detect(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in tokenRx.findAll(text)) {
            val tok = m.value.lowercase()
            val lang = byAlias[tok] ?: continue
            if (tok.length == 2 && !tok.first().isSurrogate()) continue
            if (tok in AMBIGUOUS) continue
            out += lang.code
        }
        return out.toList()
    }

    /** Three-letter codes that are also ordinary words or names in release titles. */
    private val AMBIGUOUS = setOf("dan", "ind", "tam", "tel", "mal", "ben", "nor", "hun", "pol", "tha", "ara", "fin", "vie", "rum", "deu", "ron", "cat", "may", "ice", "per", "est", "lit", "mac", "bos", "alb", "glg", "baq", "slo")
}
