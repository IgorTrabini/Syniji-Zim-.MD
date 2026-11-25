package com.example.markdown

object YamlFrontMatter {

    fun build(fields: LinkedHashMap<String, String?>): String {
        val sb = StringBuilder()
        sb.append("---\n")
        for ((key, value) in fields) {
            if (value == null) continue
            val cleanKey = key.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
            sb.append(cleanKey).append(": ").append(quoteYamlString(value)).append('\n')
        }
        sb.append("---\n\n")
        return sb.toString()
    }

    fun quoteYamlString(raw: String): String {
        val normalized = raw
            .replace("\r\n", " ")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("\t", " ")
            .trim()
        val escaped = normalized
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
