package com.github.livingwithhippos.phonarr.core

import com.github.livingwithhippos.phonarr.core.cardigann.Filters
import com.github.livingwithhippos.phonarr.core.cardigann.FilterBlock
import com.github.livingwithhippos.phonarr.core.cardigann.Template
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateTest {
    private val vars = mapOf<String, Any?>(
        ".Keywords" to "the matrix",
        ".Query.Keywords" to "The Matrix 1999",
        ".Categories" to listOf("1", "2"),
        ".Config.sort" to "seeders",
        ".Config.flag" to ".True",
        ".Config.empty" to null,
        ".True" to "True",
        ".False" to null,
    )

    private fun t(s: String) = Template.apply(s, vars)

    @Test
    fun variables() {
        assertEquals("search/the matrix/1/", t("search/{{ .Keywords }}/1/"))
        assertEquals("x", t("x{{ .Config.empty }}"))
    }

    @Test
    fun ifElse() {
        assertEquals("q=the matrix", t("{{ if .Keywords }}q={{ .Keywords }}{{ else }}latest{{ end }}"))
        assertEquals("latest", t("{{ if .Config.empty }}q{{ else }}latest{{ end }}"))
    }

    @Test
    fun logic() {
        assertEquals("yes", t("{{ if eq .Config.sort \"seeders\" }}yes{{ else }}no{{ end }}"))
        assertEquals("no", t("{{ if ne .Config.sort \"seeders\" }}yes{{ else }}no{{ end }}"))
        assertEquals("yes", t("{{ if or .Config.empty .Config.flag }}yes{{ else }}no{{ end }}"))
        assertEquals("no", t("{{ if and .Config.empty .Config.flag }}yes{{ else }}no{{ end }}"))
    }

    @Test
    fun rangeAndJoin() {
        assertEquals("cat[]=1&cat[]=2&", t("{{ range .Categories }}cat[]={{.}}&{{end}}"))
        assertEquals("c0=1;c1=2;", t("{{ range \$i, \$e := .Categories }}c{{\$i}}={{.}};{{end}}"))
        assertEquals("1,2", t("{{ join .Categories \",\" }}"))
    }

    @Test
    fun reReplace() {
        assertEquals("The%Matrix%1999", t("{{ re_replace .Query.Keywords \"[^a-zA-Z0-9]+\" \"%\" }}"))
    }

    @Test
    fun modifier() {
        assertEquals("the+matrix", Template.apply("{{ .Keywords }}", vars) { Filters.urlEncode(it) })
    }

    @Test
    fun filters() {
        val f = Filters("test", Charsets.UTF_8) { s, v -> Template.apply(s, v) }
        fun run(input: String, vararg filters: FilterBlock) = f.apply(input, filters.toList(), vars)
        assertEquals("123", run("/torrent/123/name", FilterBlock("regexp", "/torrent/(\\d+)/")))
        assertEquals("b", run("a-b-c", FilterBlock("split", listOf("-", "1"))))
        assertEquals("c", run("a-b-c", FilterBlock("split", listOf("-", "-1"))))
        assertEquals("x_y", run("x y", FilterBlock("replace", listOf(" ", "_"))))
        assertEquals("a1b", run("a 1 b", FilterBlock("re_replace", listOf("\\s+(\\d)\\s+", "$1"))))
        assertEquals("42", run("details.php?id=42&x=1", FilterBlock("querystring", "id")))
        assertEquals("HELLO!", run("hello", FilterBlock("toupper", null), FilterBlock("append", "!")))
        assertEquals("Cafe", run("Café", FilterBlock("diacritics", "replace")))
        assertEquals("a&b", run("a&amp;b", FilterBlock("htmldecode", null)))
        assertEquals("1080p, x264", run("Movie 1080p x264", FilterBlock("validate", "1080p, 720p, x264")))
        assertEquals("abc", run("..abc..", FilterBlock("trim", ".")))
        assertEquals("a b", run("a[b", FilterBlock("re_replace", listOf("[\\[!\"#\$%&'()*+,\\-.\\/:;<=>?@[\\]^_`{|}~]", " "))))
        assertEquals("x.", run("x中文", FilterBlock("re_replace", listOf("([\\p{IsCJKUnifiedIdeographs}\\W]+)", "."))))
    }
}
