package ym.ecolink.util

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

object YamlMaps {

    private val yaml = Yaml(SafeConstructor(LoaderOptions()))

    fun load(path: Path): Map<String, Any?> {
        if (!Files.exists(path)) {
            return emptyMap()
        }
        Files.newBufferedReader(path).use { reader ->
            val loaded = yaml.load<Any?>(reader) ?: return emptyMap()
            return asMap(loaded)
        }
    }

    fun section(source: Map<String, Any?>, key: String): Map<String, Any?> {
        return asMap(source[key])
    }

    fun string(value: Any?): String? {
        return value?.toString()
    }

    private fun asMap(value: Any?): Map<String, Any?> {
        if (value !is Map<*, *>) {
            return emptyMap()
        }
        return value.entries.associate { (key, nested) ->
            key.toString() to nested
        }
    }
}
