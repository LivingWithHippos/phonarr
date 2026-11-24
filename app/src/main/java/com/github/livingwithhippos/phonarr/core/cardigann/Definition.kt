package com.github.livingwithhippos.phonarr.core.cardigann

/*
 * Cardigann definition model, ported from Prowlarr's CardigannDefinition.cs.
 * Property names follow the YAML keys (YamlDotNet camelCase convention).
 */

data class CardigannDefinition(
    val id: String,
    var settings: List<SettingsField>,
    val name: String,
    val description: String?,
    val type: String?,
    val language: String?,
    var encoding: String,
    val requestDelay: Double?,
    val links: List<String>,
    val legacylinks: List<String>,
    val followredirect: Boolean,
    val testLinkTorrent: Boolean,
    val caps: CapabilitiesBlock,
    val login: LoginBlock?,
    val search: SearchBlock,
    val download: DownloadBlock?,
) {
    val isPrivate: Boolean get() = type == "private"
    val privacy: String get() = when (type) { "private" -> "private"; "public" -> "public"; else -> "semi-private" }
}

data class SettingsField(
    val name: String,
    val type: String,
    val label: String?,
    val default: String?,
    val defaults: List<String>?,
    val options: List<Pair<String, String?>>?,
)

data class CategoryMappingBlock(val id: String, val cat: String?, val desc: String?, val default: Boolean)

data class CapabilitiesBlock(
    val categories: List<Pair<String, String?>>?,
    val categorymappings: List<CategoryMappingBlock>?,
    val modes: Map<String, List<String>>?,
    val allowrawsearch: Boolean,
)

data class LoginBlock(
    val path: String?,
    val submitpath: String?,
    val cookies: List<String>?,
    var method: String?,
    val form: String?,
    val selectors: Boolean,
    val inputs: List<Pair<String, String?>>?,
    /** form login: POST values read from the login page */
    val selectorinputs: List<Pair<String, SelectorBlock>>? = null,
    /** form login: query string values read from the login page */
    val getselectorinputs: List<Pair<String, SelectorBlock>>? = null,
    val error: List<ErrorBlock>?,
    val test: PageTestBlock?,
    val hasCaptcha: Boolean,
    val headers: Map<String, List<String>>?,
)

data class ErrorBlock(val path: String?, val selector: String?, val message: SelectorBlock?)

data class PageTestBlock(val path: String?, val selector: String?)

/** A filter with either a single string argument or a list of arguments. */
data class FilterBlock(val name: String, val args: Any?) {
    val argString: String? get() = when (args) {
        is String -> args
        is List<*> -> args.firstOrNull() as String?
        else -> null
    }

    fun arg(index: Int): String? = when (args) {
        is List<*> -> args.getOrNull(index) as String?
        is String -> if (index == 0) args else null
        else -> null
    }
}

open class SelectorBlock(
    val selector: String? = null,
    val optional: Boolean = false,
    val default: String? = null,
    val text: String? = null,
    val attribute: String? = null,
    val remove: String? = null,
    val filters: List<FilterBlock>? = null,
    val case: List<Pair<String, String?>>? = null,
)

class RowsBlock(
    base: SelectorBlock,
    val after: Int,
    val dateheaders: SelectorBlock?,
    val count: SelectorBlock?,
    val multiple: Boolean,
    val missingAttributeEqualsNoResults: Boolean,
) : SelectorBlock(base.selector, base.optional, base.default, base.text, base.attribute, base.remove, base.filters, base.case)

open class RequestBlock(
    val path: String?,
    val method: String?,
    val inputs: List<Pair<String, String?>>?,
    val queryseparator: String = "&",
)

class SearchPathBlock(
    path: String?,
    method: String?,
    inputs: List<Pair<String, String?>>?,
    val categories: List<String>?,
    val inheritinputs: Boolean = true,
    val followredirect: Boolean = false,
    val response: ResponseBlock? = null,
) : RequestBlock(path, method, inputs)

data class ResponseBlock(val type: String?, val noResultsMessage: String?)

data class SearchBlock(
    val paths: List<SearchPathBlock>,
    val headers: Map<String, List<String>>?,
    val keywordsfilters: List<FilterBlock>?,
    val allowEmptyInputs: Boolean,
    val inputs: List<Pair<String, String?>>?,
    val error: List<ErrorBlock>?,
    val preprocessingfilters: List<FilterBlock>?,
    val rows: RowsBlock,
    val fields: List<Pair<String, SelectorBlock>>,
)

data class SelectorField(
    val selector: String?,
    val attribute: String?,
    val usebeforeresponse: Boolean,
    val filters: List<FilterBlock>?,
)

class BeforeBlock(
    path: String?,
    method: String?,
    inputs: List<Pair<String, String?>>?,
    val pathselector: SelectorField?,
) : RequestBlock(path, method, inputs)

data class InfohashBlock(val hash: SelectorField?, val title: SelectorField?, val usebeforeresponse: Boolean)

data class DownloadBlock(
    val selectors: List<SelectorField>?,
    val method: String?,
    val before: BeforeBlock?,
    val infohash: InfohashBlock?,
    val headers: Map<String, List<String>>?,
)

class DefinitionException(message: String) : Exception(message)

object DefinitionParser {

    fun parse(text: String, fileId: String? = null): CardigannDefinition {
        val root = Yaml.parse(text).map ?: throw DefinitionException("Definition is empty or not a map")
        return fromNode(root, fileId)
    }

    fun fromNode(root: YNode.YMap, fileId: String? = null): CardigannDefinition {
        val id = root["id"].str() ?: fileId ?: throw DefinitionException("Missing id")
        val search = root["search"].map ?: throw DefinitionException("Missing search block")
        val links = root["links"].strList().orEmpty()
        if (links.isEmpty()) throw DefinitionException("Missing links")

        val defaultSettings = listOf(
            SettingsField("username", "text", "Username", null, null, null),
            SettingsField("password", "password", "Password", null, null, null),
        )
        val settings = root["settings"].mapList(::settingsField) ?: defaultSettings

        val login = root["login"].map?.let(::loginBlock)
        if (login != null && login.method == null) login.method = "form"

        return CardigannDefinition(
            id = id,
            settings = settings,
            name = root["name"].str() ?: id,
            description = root["description"].str(),
            type = root["type"].str(),
            language = root["language"].str(),
            encoding = root["encoding"].str() ?: "UTF-8",
            requestDelay = root["requestDelay"].str()?.toDoubleOrNull(),
            links = links,
            legacylinks = root["legacylinks"].strList().orEmpty(),
            followredirect = root["followredirect"].bool(),
            testLinkTorrent = root["testlinktorrent"].bool(true),
            caps = capabilities(root["caps"].map),
            login = login,
            search = searchBlock(search),
            download = root["download"].map?.let(::downloadBlock),
        )
    }

    private fun settingsField(m: YNode.YMap) = SettingsField(
        name = m["name"].str() ?: "",
        type = m["type"].str() ?: "text",
        label = m["label"].str(),
        default = m["default"].str(),
        defaults = m["defaults"].strList(),
        options = m["options"].pairs(),
    )

    private fun capabilities(m: YNode.YMap?): CapabilitiesBlock = CapabilitiesBlock(
        categories = m?.get("categories").pairs(),
        categorymappings = m?.get("categorymappings").mapList {
            CategoryMappingBlock(it["id"].str() ?: "", it["cat"].str(), it["desc"].str(), it["default"].bool())
        },
        modes = m?.get("modes").listMap(),
        allowrawsearch = m?.get("allowrawsearch").bool(),
    )

    private fun loginBlock(m: YNode.YMap) = LoginBlock(
        path = m["path"].str(),
        submitpath = m["submitpath"].str(),
        cookies = m["cookies"].strList(),
        method = m["method"].str(),
        form = m["form"].str(),
        selectors = m["selectors"].bool(),
        inputs = m["inputs"].pairs(),
        selectorinputs = selectorMap(m["selectorinputs"]),
        getselectorinputs = selectorMap(m["getselectorinputs"]),
        error = m["error"].mapList(::errorBlock),
        test = m["test"].map?.let { PageTestBlock(it["path"].str(), it["selector"].str()) },
        hasCaptcha = m["captcha"] != null,
        headers = m["headers"].listMap(),
    )

    private fun selectorMap(n: YNode?): List<Pair<String, SelectorBlock>>? =
        (n as? YNode.YMap)?.entries?.mapNotNull { (k, v) -> (v as? YNode.YMap)?.let { k to selectorBlock(it) } }

    private fun errorBlock(m: YNode.YMap) = ErrorBlock(m["path"].str(), m["selector"].str(), m["message"].map?.let(::selectorBlock))

    fun filters(n: YNode?): List<FilterBlock>? = n.mapList { f ->
        val args: Any? = when (val a = f["args"]) {
            is YNode.Scalar -> a.value
            is YNode.YList -> a.items.map { it.str() }
            else -> null
        }
        FilterBlock(f["name"].str() ?: "", args)
    }

    fun selectorBlock(m: YNode.YMap) = SelectorBlock(
        selector = m["selector"].str(),
        optional = m["optional"].bool(),
        default = m["default"].str(),
        text = m["text"].str(),
        attribute = m["attribute"].str(),
        remove = m["remove"].str(),
        filters = filters(m["filters"]),
        case = m["case"].pairs(),
    )

    private fun searchBlock(m: YNode.YMap): SearchBlock {
        val paths = m["paths"].mapList { p ->
            SearchPathBlock(
                path = p["path"].str(),
                method = p["method"].str(),
                inputs = p["inputs"].pairs(),
                categories = p["categories"].strList(),
                inheritinputs = p["inheritinputs"].bool(true),
                followredirect = p["followredirect"].bool(),
                response = p["response"].map?.let { ResponseBlock(it["type"].str(), it["noResultsMessage"].str()) },
            )
        }.orEmpty().toMutableList()
        // a single "path" is converted into a paths entry, like Prowlarr's CleanIndexerDefinition
        m["path"].str()?.let { paths += SearchPathBlock(it, null, null, null, inheritinputs = true) }

        val rowsNode = m["rows"].map ?: throw DefinitionException("Missing search rows")
        val rows = RowsBlock(
            base = selectorBlock(rowsNode),
            after = rowsNode["after"].str()?.toIntOrNull() ?: 0,
            dateheaders = rowsNode["dateheaders"].map?.let(::selectorBlock),
            count = rowsNode["count"].map?.let(::selectorBlock),
            multiple = rowsNode["multiple"].bool(),
            missingAttributeEqualsNoResults = rowsNode["missingAttributeEqualsNoResults"].bool(),
        )
        val fields = m["fields"].map?.entries?.map { (k, v) ->
            k to when (v) {
                is YNode.YMap -> selectorBlock(v)
                else -> SelectorBlock()
            }
        } ?: throw DefinitionException("Missing search fields")

        return SearchBlock(
            paths = paths,
            headers = m["headers"].listMap(),
            keywordsfilters = filters(m["keywordsfilters"]),
            allowEmptyInputs = m["allowEmptyInputs"].bool(),
            inputs = m["inputs"].pairs(),
            error = m["error"].mapList(::errorBlock),
            preprocessingfilters = filters(m["preprocessingfilters"]),
            rows = rows,
            fields = fields,
        )
    }

    private fun selectorField(m: YNode.YMap) = SelectorField(
        selector = m["selector"].str(),
        attribute = m["attribute"].str(),
        usebeforeresponse = m["usebeforeresponse"].bool(),
        filters = filters(m["filters"]),
    )

    private fun downloadBlock(m: YNode.YMap) = DownloadBlock(
        selectors = m["selectors"].mapList(::selectorField),
        method = m["method"].str(),
        before = m["before"].map?.let {
            BeforeBlock(it["path"].str(), it["method"].str(), it["inputs"].pairs(), it["pathselector"].map?.let(::selectorField))
        },
        infohash = m["infohash"].map?.let {
            InfohashBlock(it["hash"].map?.let(::selectorField), it["title"].map?.let(::selectorField), it["usebeforeresponse"].bool())
        },
        headers = m["headers"].listMap(),
    )
}
