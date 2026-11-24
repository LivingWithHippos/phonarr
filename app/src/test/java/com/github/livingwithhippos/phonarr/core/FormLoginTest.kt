package com.github.livingwithhippos.phonarr.core

import com.github.livingwithhippos.phonarr.core.cardigann.CardigannException
import com.github.livingwithhippos.phonarr.core.cardigann.CardigannIndexer
import com.github.livingwithhippos.phonarr.core.cardigann.DefinitionParser
import com.github.livingwithhippos.phonarr.core.torznab.SearchQuery
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormLoginTest {
    private data class Sent(val method: String, val url: String, val body: String, val cookie: String?, val contentType: String?)

    private val sent = mutableListOf<Sent>()

    private fun loginPage(enctype: String = "", form: String = "form#login") = """
        <html><body>
        <form id="search" action="/search.php"><input name="q"></form>
        <form id="${form.removePrefix("form#")}" action="/takelogin.php" method="post" $enctype>
          <input type="hidden" name="csrf" value="tok123">
          <input type="text" name="uname" id="user-field">
          <input type="password" name="pass">
          <input type="checkbox" name="keep" value="1">
          <input type="checkbox" name="secure" value="yes" checked>
          <input type="text" name="ignored" value="x" disabled>
          <input type="submit" value="Log in">
        </form>
        <span id="nonce" data-v="n-42"></span>
        </body></html>
    """.trimIndent()

    private fun definition(login: String) = DefinitionParser.parse(
        resource("testsite.yml")
            .replace("type: public", "type: private")
            .replace("settings:\n", "settings:\n  - {name: username, type: text, label: Username}\n  - {name: password, type: password, label: Password}\n")
            .replace("\nsearch:\n", "\n$login\nsearch:\n"),
    )

    private val defaultLogin = """
        login:
          path: login.php
          method: form
          form: form#login
          inputs:
            uname: "{{ .Config.username }}"
            pass: "{{ .Config.password }}"
          selectorinputs:
            nonce:
              selector: "#nonce"
              attribute: data-v
          error:
            - selector: div.error
          test:
            path: index.php
            selector: table.list
    """.trimIndent()

    private fun client(page: String = loginPage(), loginOk: Boolean = true) = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        sent += Sent(
            req.method.value, req.url.toString(), String(req.body.toByteArray()),
            req.headers[HttpHeaders.Cookie], req.body.contentType?.toString(),
        )
        when {
            path == "/login.php" -> respond(page, HttpStatusCode.OK, headersOf(
                HttpHeaders.ContentType to listOf("text/html"), HttpHeaders.SetCookie to listOf("PHPSESSID=abc; path=/"),
            ))
            req.method.value == "POST" && !loginOk -> respond("<div class=\"error\">Wrong password</div>", HttpStatusCode.OK)
            req.method.value == "POST" -> respond("", HttpStatusCode.Found, headersOf(
                HttpHeaders.Location to listOf("/index.php"), HttpHeaders.SetCookie to listOf("uid=7; path=/"),
            ))
            path == "/index.php" -> respond("<p>Welcome</p>", HttpStatusCode.OK)
            else -> respond(resource("testsite.html"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
        }
    }) { followRedirects = false; expectSuccess = false }

    private val creds = mapOf("username" to "me", "password" to "p&ss word")

    private fun form(body: String) = body.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k.decodeURLQueryComponent() to v.decodeURLQueryComponent(plusIsSpace = true)
    }

    @Test
    fun logsInThenSearches() = runTest {
        val def = definition(defaultLogin)
        assertNull(CardigannIndexer.unsupportedReason(def))
        val releases = CardigannIndexer(def, creds, client()).search(SearchQuery(q = "matrix"))
        assertEquals(2, releases.size)

        assertEquals("GET https://test.example/login.php", "${sent[0].method} ${sent[0].url}")
        val post = sent[1]
        assertEquals("POST https://test.example/takelogin.php", "${post.method} ${post.url}")
        assertEquals("PHPSESSID=abc", post.cookie)
        assertEquals(
            mapOf("csrf" to "tok123", "uname" to "me", "pass" to "p&ss word", "secure" to "yes", "nonce" to "n-42"),
            form(post.body),
        )
        val search = sent.last()
        assertTrue(search.url, search.url.startsWith("https://test.example/search/matrix"))
        assertEquals("PHPSESSID=abc; uid=7", search.cookie)
    }

    @Test
    fun selectorsNameInputsByElement() = runTest {
        val login = defaultLogin
            .replace("  form: form#login\n", "  form: form#login\n  selectors: true\n  submitpath: do-login.php\n")
            .replace("    uname: ", "    \"#user-field\": ")
            .replace("    pass: ", "    \"input[type=password]\": ")
        CardigannIndexer(definition(login), creds, client()).search(SearchQuery(q = "matrix"))
        val post = sent.first { it.method == "POST" }
        assertEquals("https://test.example/do-login.php", post.url)
        val values = form(post.body)
        assertEquals("me", values["uname"])
        assertEquals("p&ss word", values["pass"])
    }

    @Test
    fun getSelectorInputsGoInTheQuery() = runTest {
        val login = defaultLogin.replace("  selectorinputs:", "  getselectorinputs:")
        CardigannIndexer(definition(login), creds, client()).search(SearchQuery(q = "matrix"))
        val post = sent.first { it.method == "POST" }
        assertEquals("https://test.example/takelogin.php?nonce=n-42", post.url)
        assertNull(form(post.body)["nonce"])
    }

    @Test
    fun multipartForm() = runTest {
        CardigannIndexer(definition(defaultLogin), creds, client(loginPage("enctype=\"multipart/form-data\"")))
            .search(SearchQuery(q = "matrix"))
        val post = sent.first { it.method == "POST" }
        assertTrue(post.contentType, post.contentType!!.startsWith("multipart/form-data"))
        assertTrue(post.body, post.body.contains("name=\"csrf\"") && post.body.contains("tok123"))
        assertTrue(post.body, post.body.contains("p&ss word"))
    }

    @Test
    fun reportsLoginError() {
        val e = assertThrows(CardigannException::class.java) {
            kotlinx.coroutines.runBlocking { CardigannIndexer(definition(defaultLogin), creds, client(loginOk = false)).search(SearchQuery(q = "x")) }
        }
        assertEquals("Login failed: Wrong password", e.message)
    }

    @Test
    fun reportsMissingForm() {
        val e = assertThrows(CardigannException::class.java) {
            kotlinx.coroutines.runBlocking { CardigannIndexer(definition(defaultLogin), creds, client(loginPage(form = "form#other"))).search(SearchQuery(q = "x")) }
        }
        assertEquals("Login failed: No form found on https://test.example/login.php using form selector form#login", e.message)
    }

    @Test
    fun captchaStaysUnsupported() {
        val def = definition(defaultLogin + "\n  captcha:\n    type: image\n    selector: img#captcha\n    input: captcha")
        assertEquals("Sites with a login captcha are not supported yet", CardigannIndexer.unsupportedReason(def))
    }
}
