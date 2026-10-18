@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.kalender_app

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.sosmon.meinkalender.ReminderReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class Termin(val id: Int, val title: String, val time: Long)

// month: 1-12, year: 0 = unbekannt
data class Geburtstag(val key: String, val name: String, val month: Int, val day: Int, val year: Int)

data class Eintrag(val key: String, val title: String, val sub: String, val time: Long, val termin: Termin?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot()
            }
        }
    }
}

// ---------- Termine speichern ----------

fun loadTermine(ctx: Context): List<Termin> {
    val json = ctx.getSharedPreferences("kalender", Context.MODE_PRIVATE)
        .getString("termine", "[]") ?: "[]"
    val arr = JSONArray(json)
    return (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        Termin(o.getInt("id"), o.getString("title"), o.getLong("time"))
    }
}

fun saveTermine(ctx: Context, list: List<Termin>) {
    val arr = JSONArray()
    list.forEach {
        arr.put(JSONObject().put("id", it.id).put("title", it.title).put("time", it.time))
    }
    ctx.getSharedPreferences("kalender", Context.MODE_PRIVATE)
        .edit().putString("termine", arr.toString()).apply()
}

fun pendingFor(ctx: Context, t: Termin): PendingIntent {
    val intent = Intent(ctx, ReminderReceiver::class.java)
        .putExtra("title", t.title)
        .putExtra("id", t.id)
    return PendingIntent.getBroadcast(
        ctx, t.id, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

fun scheduleReminder(ctx: Context, t: Termin) {
    val am = ctx.getSystemService(AlarmManager::class.java)
    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.time, pendingFor(ctx, t))
}

fun cancelReminder(ctx: Context, t: Termin) {
    ctx.getSystemService(AlarmManager::class.java).cancel(pendingFor(ctx, t))
}

// ---------- Geburtstage ----------

fun loadGeburtstage(ctx: Context): List<Geburtstag> {
    val json = ctx.getSharedPreferences("kalender", Context.MODE_PRIVATE)
        .getString("geburtstage", "[]") ?: "[]"
    val arr = JSONArray(json)
    return (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        Geburtstag(
            o.getString("key"), o.getString("name"),
            o.getInt("month"), o.getInt("day"), o.getInt("year")
        )
    }
}

fun saveGeburtstage(ctx: Context, list: List<Geburtstag>) {
    val arr = JSONArray()
    list.forEach {
        arr.put(
            JSONObject().put("key", it.key).put("name", it.name)
                .put("month", it.month).put("day", it.day).put("year", it.year)
        )
    }
    ctx.getSharedPreferences("kalender", Context.MODE_PRIVATE)
        .edit().putString("geburtstage", arr.toString()).apply()
}

// Nächster Geburtstag, immer um 9:00 Uhr
fun nextBirthdayMillis(g: Geburtstag): Long {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply {
        set(now.get(Calendar.YEAR), g.month - 1, g.day, 9, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (c.timeInMillis <= now.timeInMillis) c.add(Calendar.YEAR, 1)
    return c.timeInMillis
}

fun alterText(g: Geburtstag, time: Long): String {
    if (g.year <= 0) return "Geburtstag"
    val y = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.YEAR)
    return "wird ${y - g.year}"
}

fun birthdayPending(ctx: Context, g: Geburtstag, time: Long): PendingIntent {
    val code = ("bday" + g.key).hashCode()
    val intent = Intent(ctx, ReminderReceiver::class.java)
        .putExtra("title", "Geburtstag: ${g.name}")
        .putExtra("text", if (time > 0) alterText(g, time) else "Geburtstag")
        .putExtra("id", code)
        .putExtra("bday_key", g.key)
        .putExtra("bday_name", g.name)
        .putExtra("bday_month", g.month)
        .putExtra("bday_day", g.day)
        .putExtra("bday_year", g.year)
    return PendingIntent.getBroadcast(
        ctx, code, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

fun scheduleBirthday(ctx: Context, g: Geburtstag) {
    val time = nextBirthdayMillis(g)
    val am = ctx.getSystemService(AlarmManager::class.java)
    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, birthdayPending(ctx, g, time))
}

fun cancelBirthday(ctx: Context, g: Geburtstag) {
    ctx.getSystemService(AlarmManager::class.java).cancel(birthdayPending(ctx, g, 0))
}

fun readContactBirthdays(ctx: Context): List<Geburtstag> {
    val result = mutableListOf<Geburtstag>()
    val projection = arrayOf(
        ContactsContract.Data.LOOKUP_KEY,
        ContactsContract.Data.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Event.START_DATE
    )
    val selection =
        "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?"
    val args = arrayOf(
        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()
    )
    ctx.contentResolver.query(
        ContactsContract.Data.CONTENT_URI, projection, selection, args, null
    )?.use { c ->
        while (c.moveToNext()) {
            val key = c.getString(0) ?: continue
            val name = c.getString(1) ?: continue
            val date = c.getString(2) ?: continue
            try {
                val p = date.split("-")
                if (date.startsWith("--")) {
                    // Format ohne Jahr: --MM-dd
                    result.add(Geburtstag(key, name, p[2].toInt(), p[3].substring(0, 2).toInt(), 0))
                } else {
                    // Format yyyy-MM-dd
                    val y = p[0].toInt()
                    result.add(
                        Geburtstag(
                            key, name, p[1].toInt(), p[2].substring(0, 2).toInt(),
                            if (y < 1900) 0 else y
                        )
                    )
                }
            } catch (e: Exception) {
                // Unbekanntes Datumsformat: überspringen
            }
        }
    }
    return result.distinctBy { it.key }
}

// Liest alle Kontakt-Geburtstage, ersetzt die gespeicherten und plant Erinnerungen neu
fun syncBirthdays(ctx: Context): Int {
    loadGeburtstage(ctx).forEach { cancelBirthday(ctx, it) }
    val neu = readContactBirthdays(ctx)
    saveGeburtstage(ctx, neu)
    neu.forEach { scheduleBirthday(ctx, it) }
    return neu.size
}

// ---------- Oberfläche ----------

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val termine = remember { mutableStateListOf<Termin>().apply { addAll(loadTermine(ctx)) } }
    val geburtstage = remember {
        mutableStateListOf<Geburtstag>().apply { addAll(loadGeburtstage(ctx)) }
    }
    var screen by remember { mutableStateOf("home") }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Geburtstags-Erinnerungen bei jedem Start sicherheitshalber neu planen
        geburtstage.forEach { scheduleBirthday(ctx, it) }
    }

    if (screen == "settings") {
        SettingsScreen(
            count = geburtstage.size,
            onBack = { screen = "home" },
            onSync = {
                val n = syncBirthdays(ctx)
                geburtstage.clear()
                geburtstage.addAll(loadGeburtstage(ctx))
                n
            }
        )
    } else {
        KalenderScreen(termine, geburtstage, onOpenSettings = { screen = "settings" })
    }
}

@Composable
fun KalenderScreen(
    termine: androidx.compose.runtime.snapshots.SnapshotStateList<Termin>,
    geburtstage: List<Geburtstag>,
    onOpenSettings: () -> Unit
) {
    val ctx = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("EEE, dd.MM.yyyy HH:mm", Locale.GERMANY) }
    val fmtDay = remember { SimpleDateFormat("EEE, dd.MM.yyyy", Locale.GERMANY) }

    val eintraege = (
            termine.map { Eintrag("t${it.id}", it.title, fmt.format(it.time), it.time, it) } +
                    geburtstage.map {
                        val t = nextBirthdayMillis(it)
                        Eintrag("b${it.key}", "🎂 ${it.name}", "${alterText(it, t)} · ${fmtDay.format(t)}", t, null)
                    }
            ).sortedBy { it.time }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mein Kalender") },
                actions = {
                    Box {
                        TextButton(onClick = { menuOpen = true }) {
                            Text("⋮", style = MaterialTheme.typography.titleLarge)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Einstellungen") },
                                onClick = { menuOpen = false; onOpenSettings() }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) { Text("+") }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(eintraege, key = { it.key }) { e ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.titleMedium)
                        Text(e.sub, style = MaterialTheme.typography.bodyMedium)
                    }
                    val t = e.termin
                    if (t != null) {
                        TextButton(onClick = {
                            cancelReminder(ctx, t)
                            termine.remove(t)
                            saveTermine(ctx, termine)
                        }) { Text("Löschen") }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (showDialog) {
        AddDialog(
            onDismiss = { showDialog = false },
            onSave = { title, time ->
                val t = Termin((System.currentTimeMillis() / 1000).toInt(), title, time)
                termine.add(t)
                saveTermine(ctx, termine)
                scheduleReminder(ctx, t)
                showDialog = false
            }
        )
    }
}

@Composable
fun SettingsScreen(count: Int, onBack: () -> Unit, onSync: () -> Int) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        status = if (granted) "${onSync()} Geburtstage synchronisiert"
        else "Zugriff auf Kontakte wurde abgelehnt"
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ListItem(
                headlineContent = { Text("Kontakte synchronisieren") },
                supportingContent = { Text(status ?: "$count Geburtstage gespeichert") },
                modifier = Modifier.clickable {
                    val granted = ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.READ_CONTACTS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        status = "${onSync()} Geburtstage synchronisiert"
                    } else {
                        permLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun AddDialog(onDismiss: () -> Unit, onSave: (String, Long) -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var cal by remember { mutableStateOf<Calendar?>(null) }
    val fmt = remember { SimpleDateFormat("EEE, dd.MM.yyyy HH:mm", Locale.GERMANY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neuer Termin") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titel") }
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    val now = Calendar.getInstance()
                    DatePickerDialog(ctx, { _, y, m, d ->
                        TimePickerDialog(ctx, { _, h, min ->
                            cal = Calendar.getInstance().apply {
                                set(y, m, d, h, min, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
                    }, now.get(Calendar.YEAR), now.get(Calendar.MONTH),
                        now.get(Calendar.DAY_OF_MONTH)).show()
                }) {
                    Text(cal?.let { fmt.format(it.time) } ?: "Datum & Uhrzeit wählen")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && cal != null,
                onClick = { onSave(title.trim(), cal!!.timeInMillis) }
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}