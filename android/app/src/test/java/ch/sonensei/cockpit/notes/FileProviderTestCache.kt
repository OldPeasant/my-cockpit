package ch.sonensei.cockpit.notes

import androidx.core.content.FileProvider
import java.lang.reflect.Modifier

/**
 * FileProvider caches its path roots per authority in a static map. Robolectric gives every test a new data
 * directory but keeps the class loaded, so a root cached by an earlier test points to an old directory. Tests that
 * create camera files clear the cache first.
 */
internal fun clearFileProviderCache() {
    FileProvider::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && Map::class.java.isAssignableFrom(it.type) }
        .forEach { field ->
            field.isAccessible = true
            (field.get(null) as MutableMap<*, *>).clear()
        }
}
