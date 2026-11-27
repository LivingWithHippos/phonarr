package com.github.livingwithhippos.phonarr.core.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Stores a value as JSON in a file, writing atomically through a temp file. */
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    @Synchronized
    fun read(): T {
        if (!file.exists()) return default()
        return runCatching {
            json.decodeFromString(
                serializer,
                file.readText()
            )
        }.getOrElse { default() }
    }

    @Synchronized
    fun write(value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
