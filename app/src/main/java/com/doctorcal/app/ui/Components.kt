package com.doctorcal.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.doctorcal.app.data.Entry
import com.doctorcal.app.data.Kind
import com.doctorcal.app.data.Shift
import com.doctorcal.app.data.formatMinute
import com.doctorcal.app.NoteState
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * One of the four quadrants: the appointments or therapy sessions of one shift.
 * [scrollInside] = true when the box has a fixed size (grid layouts) and must scroll its own list;
 * false when it sits in a scrolling column (phone portrait) and simply grows with its content.
 */
@Composable
fun ShiftBox(
    kind: Kind,
    shift: Shift,
    entries: List<Entry>,
    modifier: Modifier,
    scrollInside: Boolean,
    onAdd: () -> Unit,
    onOpen: (Entry) -> Unit,
) {
    val accent = shiftAccent(shift)
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
    ) {
        Column(if (scrollInside) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(accent.copy(alpha = 0.12f))
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (shift == Shift.MORNING) Icons.Default.WbSunny else Icons.Default.NightsStay,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(
                        text = shift.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        maxLines = 1,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (kind == Kind.APPOINTMENT) Icons.Default.Person else Icons.Default.Healing,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${kind.plural.replaceFirstChar { it.uppercase() }} · ${entries.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = "Add ${kind.label.lowercase()} (${shift.label.lowercase()})", tint = accent)
                }
            }

            when {
                entries.isEmpty() -> Box(
                    (if (scrollInside) Modifier.weight(1f) else Modifier).fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No ${kind.plural}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                    )
                }

                scrollInside -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(entries, key = { it.id }) { PatientCard(it, accent) { onOpen(it) } }
                }

                else -> Column(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entries.forEach { PatientCard(it, accent) { onOpen(it) } }
                }
            }
        }
    }
}

/** A patient card: name and the hour of the appointment. */
@Composable
fun PatientCard(entry: Entry, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(accent.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials(entry.patientName),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.patientName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.details.isNotBlank()) {
                    Text(
                        text = entry.details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatMinute(entry.startMinute),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${entry.durationMin} min",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun initials(name: String): String =
    name.trim().split(" ").filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "?" }

/** Daily notes. Saved on every keystroke into the local database. */
@Composable
fun NotesPanel(
    date: LocalDate,
    noteState: NoteState?,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    val key = date.toString()
    // null = not loaded yet for this date
    var text by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key, noteState) {
        if (text == null && noteState != null && noteState.date == key) text = noteState.text
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.EditNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "Notes",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        date.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = text ?: "",
                onValueChange = {
                    text = it
                    onChange(it)
                },
                enabled = text != null,
                placeholder = { Text("Notes for this day…") },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
            )
        }
    }
}
