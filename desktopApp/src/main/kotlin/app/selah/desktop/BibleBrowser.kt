package app.selah.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.selah.shared.Bible
import app.selah.shared.ClientMessage
import app.selah.shared.oldTestamentIndices
import app.selah.shared.slideIndexOfVerse

/**
 * Explorador de la Biblia: libros → capítulos → versículos.
 * Clic en un versículo: se proyecta el capítulo entero empezando en ese versículo,
 * así "Siguiente" sigue con el próximo.
 */
@Composable
fun BibleBrowser(
    bible: Bible,
    modifier: Modifier,
    send: (ClientMessage) -> Unit,
    present: (ClientMessage) -> Unit,
    initialBook: Int? = null,
    initialChapter: Int? = null,
) {
    var bookIndex by rememberSaveable(bible.name) { mutableStateOf(initialBook) }
    var chapterNumber by rememberSaveable(bible.name) { mutableStateOf(initialChapter) }
    val book = bookIndex?.let { bible.books.getOrNull(it) }
    val chapter = chapterNumber?.let { book?.chapters?.getOrNull(it - 1) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Migas: Libros › Juan › 3
        Row(verticalAlignment = Alignment.CenterVertically) {
            Crumb("Libros", active = book == null) { bookIndex = null; chapterNumber = null }
            if (book != null) {
                Text(" › ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Crumb(book.name, active = chapter == null) { chapterNumber = null }
            }
            if (chapter != null) {
                Text(" › ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Crumb("$chapterNumber", active = true) {}
            }
        }

        when {
            // Libros de un solo capítulo (Abdías, Judas…): directo a los versículos.
            book == null -> BookGrid(bible) { bookIndex = it; chapterNumber = if (bible.books[it].chapters.size == 1) 1 else null }
            chapter == null -> ChapterGrid(book.chapters.size) { chapterNumber = it }
            else -> {
                val ref = "${book.name} $chapterNumber"
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button({ present(ClientMessage.ShowBible(ref)) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("Mostrar capítulo")
                    }
                    OutlinedButton({ send(ClientMessage.PlanAddBible(ref)) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                        Text("+ Lista")
                    }
                    TextButton(
                        { chapterNumber = chapterNumber!! - 1 }, enabled = chapterNumber!! > 1,
                        contentPadding = PaddingValues(4.dp), modifier = Modifier.width(36.dp),
                    ) { Text("‹") }
                    TextButton(
                        { chapterNumber = chapterNumber!! + 1 }, enabled = chapterNumber!! < book.chapters.size,
                        contentPadding = PaddingValues(4.dp), modifier = Modifier.width(36.dp),
                    ) { Text("›") }
                }
                VerseList(
                    chapter,
                    key = ref,
                    onShow = { v ->
                        present(ClientMessage.ShowBible(ref))
                        send(ClientMessage.GoTo(slideIndexOfVerse(chapter, v)))
                    },
                    onAdd = { v -> send(ClientMessage.PlanAddBible("$ref:$v")) },
                )
            }
        }
    }
}

@Composable
private fun Crumb(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = if (active) Modifier else Modifier.handClickable(onClick = onClick),
    )
}

@Composable
private fun Tile(text: String, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .handClickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BookGrid(bible: Bible, onPick: (Int) -> Unit) {
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        GridCells.Adaptive(96.dp), state = gridState,
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val oldTestament = oldTestamentIndices(bible.books.map { it.name })
        val groups = listOf(
            "Antiguo Testamento" to bible.books.indices.filter { it in oldTestament },
            "Nuevo Testamento" to bible.books.indices.filter { it !in oldTestament },
        )
        for ((title, indices) in groups) {
            if (indices.isEmpty()) continue
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
            }
            items(indices, key = { it }) { i -> Tile(bible.books[i].name) { onPick(i) } }
        }
    }
}

@Composable
private fun ChapterGrid(count: Int, onPick: (Int) -> Unit) {
    LazyVerticalGrid(
        GridCells.Adaptive(44.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items((1..count).toList(), key = { it }) { c -> Tile("$c") { onPick(c) } }
    }
}

@Composable
private fun VerseList(chapter: List<String>, key: String, onShow: (Int) -> Unit, onAdd: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(key) { listState.scrollToItem(0) }
    LazyColumn(state = listState) {
        itemsIndexed(chapter) { i, text ->
            if (text.isBlank()) return@itemsIndexed
            val v = i + 1
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).handClickable { onShow(v) }.padding(vertical = 5.dp, horizontal = 2.dp)) {
                    Text(
                        "$v", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp),
                    )
                    Text(text.trim(), style = MaterialTheme.typography.bodySmall)
                }
                TextButton({ onAdd(v) }, contentPadding = PaddingValues(4.dp), modifier = Modifier.width(36.dp)) { Text("+") }
            }
        }
    }
}
