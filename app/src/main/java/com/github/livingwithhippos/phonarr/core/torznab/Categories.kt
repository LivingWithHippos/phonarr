package com.github.livingwithhippos.phonarr.core.torznab

import java.security.MessageDigest

/** A Newznab/Torznab category. Equality is by id, like Prowlarr's IndexerCategory. */
class IndexerCategory(val id: Int, val name: String = "") {
    val subCategories: MutableList<IndexerCategory> = mutableListOf()

    fun contains(cat: IndexerCategory): Boolean = this == cat || subCategories.contains(cat)
    fun copyWithoutSubCategories() = IndexerCategory(id, name)

    override fun equals(other: Any?) = other is IndexerCategory && other.id == id
    override fun hashCode() = id
    override fun toString() = "$name ($id)"
}

object NewznabStandardCategory {
    private val all = mutableListOf<IndexerCategory>()
    private fun c(id: Int, name: String) = IndexerCategory(id, name).also { all += it }

    val ZedOther = c(0, "Other")
    val ZedOtherMisc = c(10, "Other/Misc")
    val ZedOtherHashed = c(20, "Other/Hashed")
    val Console = c(1000, "Console")

    init {
        c(1010, "Console/NDS"); c(1020, "Console/PSP"); c(1030, "Console/Wii"); c(
            1040,
            "Console/XBox"
        )
        c(1050, "Console/XBox 360"); c(1060, "Console/Wiiware"); c(1070, "Console/XBox 360 DLC")
        c(1080, "Console/PS3"); c(1090, "Console/Other"); c(1110, "Console/3DS"); c(
            1120,
            "Console/PS Vita"
        )
        c(1130, "Console/WiiU"); c(1140, "Console/XBox One"); c(1180, "Console/PS4")
    }

    val Movies = c(2000, "Movies")

    init {
        c(2010, "Movies/Foreign"); c(2020, "Movies/Other"); c(2030, "Movies/SD"); c(
            2040,
            "Movies/HD"
        )
        c(2045, "Movies/UHD"); c(2050, "Movies/BluRay"); c(2060, "Movies/3D"); c(2070, "Movies/DVD")
        c(2080, "Movies/WEB-DL"); c(2090, "Movies/x265")
    }

    val Audio = c(3000, "Audio")

    init {
        c(3010, "Audio/MP3"); c(3020, "Audio/Video"); c(3030, "Audio/Audiobook"); c(
            3040,
            "Audio/Lossless"
        )
        c(3050, "Audio/Other"); c(3060, "Audio/Foreign")
    }

    val PC = c(4000, "PC")

    init {
        c(4010, "PC/0day"); c(4020, "PC/ISO"); c(4030, "PC/Mac"); c(
            4040,
            "PC/Mobile-Other"
        ); c(4050, "PC/Games")
        c(4060, "PC/Mobile-iOS"); c(4070, "PC/Mobile-Android")
    }

    val TV = c(5000, "TV")

    init {
        c(5010, "TV/WEB-DL"); c(5020, "TV/Foreign"); c(5030, "TV/SD"); c(5040, "TV/HD"); c(
            5045,
            "TV/UHD"
        )
        c(5050, "TV/Other"); c(5060, "TV/Sport"); c(5070, "TV/Anime"); c(5080, "TV/Documentary"); c(
            5090,
            "TV/x265"
        )
    }

    val XXX = c(6000, "XXX")

    init {
        c(6010, "XXX/DVD"); c(6020, "XXX/WMV"); c(6030, "XXX/XviD"); c(6040, "XXX/x264"); c(
            6045,
            "XXX/UHD"
        )
        c(6050, "XXX/Pack"); c(6060, "XXX/ImageSet"); c(6070, "XXX/Other"); c(6080, "XXX/SD"); c(
            6090,
            "XXX/WEB-DL"
        )
    }

    val Books = c(7000, "Books")

    init {
        c(7010, "Books/Mags"); c(7020, "Books/EBook"); c(7030, "Books/Comics"); c(
            7040,
            "Books/Technical"
        )
        c(7050, "Books/Other"); c(7060, "Books/Foreign")
    }

    val Other = c(8000, "Other")

    init {
        c(8010, "Other/Misc"); c(8020, "Other/Hashed")
    }

    val allCats: List<IndexerCategory> get() = all

    val parentCats: List<IndexerCategory> by lazy {
        val parents = listOf(Console, Movies, Audio, PC, TV, XXX, Books, Other, ZedOther)
        parents.forEach { p ->
            if (p === ZedOther) {
                p.subCategories += listOf(ZedOtherMisc, ZedOtherHashed)
            } else {
                p.subCategories += all.filter { it.id != p.id && it.id / 1000 == p.id / 1000 && it.id >= 1000 }
            }
        }
        parents
    }

    fun getCatByName(name: String?): IndexerCategory? = all.firstOrNull { it.name == name }
    fun getCatById(id: Int): IndexerCategory? = all.firstOrNull { it.id == id }
}

/** Port of Prowlarr's IndexerCapabilitiesCategories: maps site categories to Torznab ones. */
class IndexerCapabilitiesCategories {
    private data class CategoryMapping(
        val trackerCategory: String,
        val trackerCategoryDesc: String?,
        val newznabCategory: Int
    )

    private val mappings = mutableListOf<CategoryMapping>()
    private val tree = mutableListOf<IndexerCategory>()

    fun getTrackerCategories(): List<String> =
        mappings.filter { it.newznabCategory < 100000 }.map { it.trackerCategory }.distinct()

    fun getTorznabCategoryTree(sorted: Boolean = false): List<IndexerCategory> {
        if (!sorted) return tree
        return tree.map { c ->
            IndexerCategory(
                c.id,
                c.name
            ).also { it.subCategories += c.subCategories.sortedBy { s -> s.id } }
        }.sortedWith(
            compareBy(
                { it.id >= 100000 },
                { if (it.id >= 100000) 0 else it.id },
                { it.name })
        )
    }

    fun getTorznabCategoryList(sorted: Boolean = false): List<IndexerCategory> =
        getTorznabCategoryTree(sorted).flatMap { listOf(it.copyWithoutSubCategories()) + it.subCategories }

    fun addCategoryMapping(
        trackerCategory: String,
        torznabCategory: IndexerCategory?,
        trackerCategoryDesc: String? = null
    ) {
        if (torznabCategory != null) {
            mappings += CategoryMapping(trackerCategory, trackerCategoryDesc, torznabCategory.id)
            addTorznabCategoryTree(torznabCategory)
        }
        if (trackerCategoryDesc == null) return

        // custom 1:1 category; ids must stay stable so they are derived from the tracker category
        val trackerInt = trackerCategory.toIntOrNull() ?: run {
            val hashed = MessageDigest.getInstance("SHA-1")
                .digest(trackerCategory.toByteArray(Charsets.UTF_8))
            (hashed[0].toInt() and 0xff) or ((hashed[1].toInt() and 0xff) shl 8) // little-endian UInt16
        }
        val custom = IndexerCategory(trackerInt + 100000, trackerCategoryDesc)
        mappings += CategoryMapping(trackerCategory, trackerCategoryDesc, custom.id)
        addTorznabCategoryTree(custom)
    }

    fun mapTorznabCapsToTrackers(
        queryCategories: List<Int>?,
        mapChildrenCatsToParent: Boolean = false
    ): List<String> {
        val expanded = expandTorznabQueryCategories(queryCategories, mapChildrenCatsToParent)
        return mappings.filter { expanded.contains(it.newznabCategory) }.map { it.trackerCategory }
            .distinct()
    }

    fun mapTrackerCatToNewznab(input: String?): List<IndexerCategory> {
        if (input.isNullOrBlank()) return emptyList()
        return mappings.filter {
            it.trackerCategory.isNotBlank() && it.trackerCategory.equals(
                input,
                ignoreCase = true
            )
        }
            .map {
                NewznabStandardCategory.getCatById(it.newznabCategory)
                    ?: IndexerCategory(it.newznabCategory)
            }
    }

    fun mapTrackerCatDescToNewznab(desc: String?): List<IndexerCategory> {
        if (desc.isNullOrBlank()) return emptyList()
        return mappings.filter {
            !it.trackerCategoryDesc.isNullOrBlank() && it.trackerCategoryDesc.equals(
                desc,
                ignoreCase = true
            )
        }
            .map {
                NewznabStandardCategory.getCatById(it.newznabCategory)
                    ?: IndexerCategory(it.newznabCategory)
            }
    }

    fun supportedCategories(categories: List<Int>?): List<Int> {
        if (categories.isNullOrEmpty()) return emptyList()
        val all = tree + tree.flatMap { it.subCategories }
        return all.filter { categories.contains(it.id) }.map { it.id }
    }

    fun concat(other: IndexerCapabilitiesCategories) {
        other.getTorznabCategoryList().filter { it.id < 100000 }
            .forEach { addTorznabCategoryTree(it) }
    }

    fun expandTorznabQueryCategories(
        queryCategories: List<Int>?,
        mapChildrenCatsToParent: Boolean = false
    ): List<Int> {
        val result = mutableListOf<Int>()
        if (queryCategories == null) return result
        for (q in queryCategories) {
            result += q
            if (q >= 100000) continue
            val parent = tree.firstOrNull { it.id == q }
            if (parent != null) {
                result += parent.subCategories.map { it.id }
            } else if (mapChildrenCatsToParent) {
                tree.firstOrNull { it.contains(IndexerCategory(q)) }?.let { result += it.id }
            }
        }
        return result.distinct()
    }

    private fun addTorznabCategoryTree(cat: IndexerCategory) {
        val parents = NewznabStandardCategory.parentCats
        if (parents.contains(cat)) {
            if (!tree.contains(cat)) tree += cat.copyWithoutSubCategories()
            return
        }
        val parent = parents.firstOrNull { it.contains(cat) }
            ?: if (cat.id in 1001..9999) parents.firstOrNull { it.id / 1000 == cat.id / 1000 } else null
        if (parent == null) {
            if (!tree.contains(cat)) tree += cat
            return
        }
        val node = tree.firstOrNull { it == parent } ?: parent.copyWithoutSubCategories()
            .also { tree += it }
        if (!node.contains(cat)) node.subCategories += cat
    }
}
