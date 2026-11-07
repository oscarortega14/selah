package app.selah.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.selah.shared.BibleBookInfo
import app.selah.shared.OLD_TESTAMENT_BOOKS
import app.selah.shared.ServerMessage
import app.selah.shared.slideIndexOfVerse

/** Dónde está el explorador: libro (índice) y capítulo (1…); null = nivel de arriba. */
data class BibleNav(val book: Int? = null, val chapter: Int? = null) {
    companion object {
        val Saver = listSaver<BibleNav, Int>(
            save = { listOf(it.book ?: -1, it.chapter ?: -1) },
            restore = { BibleNav(it[0].takeIf { b -> b >= 0 }, it[1].takeIf { c -> c >= 0 }) },
        )
    }
}

/**
 * Biblia en la app: buscador arriba y, debajo, el explorador libros → capítulos → versículos.
 * Tocar un versículo proyecta el capítulo empezando en él (▶ sigue con el próximo).
 */
@Composable
fun BibleTab(
    bibleName: String?,
    books: List<BibleBookInfo>,
    chapterText: ServerMessage.BibleChapter?,
    results: ServerMessage.BibleResults?,
    nav: BibleNav,
    onNavigate: (BibleNav) -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onRequestChapter: (book: String, chapter: Int) -> Unit,
    onAdd: (String) -> Unit,
    /** Proyectar una cita y saltar a la diapositiva [slide] de ella. */
    onShow: (ref: String, slide: Int) -> Unit,
    bibleNames: List<String> = emptyList(),
    secondBible: String? = null,
    onSelectBible: (String) -> Unit = {},
    onSelectSecond: (String?) -> Unit = {},
) {
    val notices = LocalNotices.current
    val focus = LocalFocusManager.current
    var ref by rememberSaveable { mutableStateOf("") }
    val added = { r: String -> onAdd(r); notices.show("$r agregado a la lista") }
    val show = { if (ref.isNotBlank()) { focus.clearFocus(); onShow(ref.trim(), 0) } }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (bibleNames.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Choice("Traducción", bibleName ?: "—", bibleNames, bibleName) { it?.let(onSelectBible) }
                Choice("+ segunda", secondBible ?: "ninguna", listOf<String?>(null) + bibleNames.filter { it != bibleName }, secondBible, onSelectSecond)
            }
        }
        OutlinedTextField(
            ref, { ref = it },
            label = { Text("Cita o frase (ej. Juan 3:16 · amó Dios)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { show() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val pad = PaddingValues(horizontal = 6.dp)
            Button(show, Modifier.weight(1f), enabled = ref.isNotBlank(), contentPadding = pad) { Text("Mostrar", maxLines = 1) }
            OutlinedButton({ added(ref.trim()) }, Modifier.weight(1f), enabled = ref.isNotBlank(), contentPadding = pad) { Text("+ Lista", maxLines = 1) }
            OutlinedButton(
                { focus.clearFocus(); onSearch(ref.trim()) }, Modifier.weight(1f), enabled = ref.isNotBlank(), contentPadding = pad,
            ) { Text("Buscar", maxLines = 1) }
        }

        when {
            results != null -> SearchResults(results, onClearSearch, { onShow(it, 0) }, added)
            books.isEmpty() -> Text(
                if (bibleName == null) "El computador no tiene Biblia cargada."
                else "Para ver los libros, actualiza Selah en el computador. Mientras tanto puedes escribir la cita arriba.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Browser(books, chapterText, nav, onNavigate, onRequestChapter, onShow, added)
        }
    }
}

@Composable
private fun SearchResults(
    results: ServerMessage.BibleResults,
    onClose: () -> Unit,
    onShow: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (results.total == 0) "Sin resultados para «${results.query}»"
                else "${results.total} resultados" + if (results.total > results.hits.size) " (primeros ${results.hits.size})" else "",
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
            )
            TextButton(onClose) { Text("✕ Ver libros") }
        }
        LazyColumn {
            items(results.hits, key = { it.reference }) { hit ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onShow(hit.reference) }.padding(vertical = 6.dp)) {
                        Text(hit.reference, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        Text(hit.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton({ onAdd(hit.reference) }) { Text("+") }
                }
            }
        }
    }
}

@Composable
private fun Browser(
    books: List<BibleBookInfo>,
    chapterText: ServerMessage.BibleChapter?,
    nav: BibleNav,
    onNavigate: (BibleNav) -> Unit,
    onRequestChapter: (String, Int) -> Unit,
    onShow: (String, Int) -> Unit,
    onAdd: (String) -> Unit,
) {
    val book = nav.book?.let { books.getOrNull(it) }
    val chapter = nav.chapter?.takeIf { book != null && it in 1..book.chapters }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Libros › Juan › 3
        Row(verticalAlignment = Alignment.CenterVertically) {
            Crumb("Libros", active = book == null) { onNavigate(BibleNav()) }
            if (book != null) {
                Text(" › ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Crumb(book.name, active = chapter == null) { onNavigate(BibleNav(nav.book)) }
            }
            if (chapter != null) {
                Text(" › ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Crumb("$chapter", active = true) {}
            }
        }

        if (book == null) {
            LazyVerticalGrid(
                GridCells.Adaptive(104.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val groups = listOf(
                    "Antiguo Testamento" to books.indices.filter { it < OLD_TESTAMENT_BOOKS },
                    "Nuevo Testamento" to books.indices.filter { it >= OLD_TESTAMENT_BOOKS },
                )
                for ((title, indices) in groups) {
                    if (indices.isEmpty()) continue
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                    items(indices, key = { it }) { i ->
                        // Libros de un solo capítulo (Abdías, Judas…): directo a los versículos.
                        Tile(books[i].name) { onNavigate(BibleNav(i, if (books[i].chapters == 1) 1 else null)) }
                    }
                }
            }
        } else if (chapter == null) {
            LazyVerticalGrid(
                GridCells.Adaptive(52.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items((1..book.chapters).toList(), key = { it }) { c -> Tile("$c") { onNavigate(nav.copy(chapter = c)) } }
            }
        } else {
            val ref = "${book.name} $chapter"
            val loaded = chapterText?.takeIf { it.book == book.name && it.chapter == chapter }
            LaunchedEffect(ref, loaded == null) { if (loaded == null) onRequestChapter(book.name, chapter) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ onShow(ref, 0) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Mostrar todo") }
                OutlinedButton({ onAdd(ref) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+ Lista") }
                Box(Modifier.weight(1f))
                TextButton({ onNavigate(nav.copy(chapter = chapter - 1)) }, enabled = chapter > 1) { Text("‹ Ant.") }
                TextButton({ onNavigate(nav.copy(chapter = chapter + 1)) }, enabled = chapter < book.chapters) { Text("Sig. ›") }
            }
            if (loaded == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (loaded.verses.isEmpty()) {
                // El PC siempre manda el capítulo completo; vacío solo pasa en la demostración.
                Text(DemoServer.DEMO_BIBLE_ONLY, Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn {
                    itemsIndexed(loaded.verses, key = { i, _ -> "$ref:$i" }) { i, text ->
                        if (text.isNotBlank()) {
                            val v = i + 1
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f).clickable { onShow(ref, slideIndexOfVerse(loaded.verses, v)) }.padding(vertical = 8.dp)) {
                                    Text(
                                        "$v", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(30.dp),
                                    )
                                    Text(text.trim())
                                }
                                TextButton({ onAdd("$ref:$v") }) { Text("+") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Crumb(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = if (active) Modifier.padding(vertical = 6.dp) else Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}

@Composable
private fun Tile(text: String, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Choice(title: String, shown: String, options: List<String?>, current: String?, onPick: (String?) -> Unit) {
    var open by androidx.compose.runtime.remember { mutableStateOf(false) }
    Box {
        TextButton({ open = true }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("$title: $shown ▾", maxLines = 1) }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            for (o in options) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text((if (o == current) "✓ " else "") + (o ?: "Ninguna (solo una)")) },
                    onClick = { open = false; if (o != current) onPick(o) },
                )
            }
        }
    }
}
