package code.ai.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

private data class CoAIdePalette(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val elevated: Color,
    val userBubble: Color,
    val accent: Color,
    val onAccent: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val codeBg: Color,
    val codeBorder: Color,
    val codeText: Color,
    val errorBg: Color,
    val errorBorder: Color,
    val errorText: Color
)

private val DarkPalette = CoAIdePalette(
    isDark = true,
    bg = Color(0xFF0B0F17),
    surface = Color(0xFF131A26),
    elevated = Color(0xFF1A2436),
    userBubble = Color(0xFF16324F),
    accent = Color(0xFF38BDF8),
    onAccent = Color(0xFF04131D),
    text = Color(0xFFE6EDF3),
    muted = Color(0xFF8B949E),
    border = Color(0xFF233044),
    codeBg = Color(0xFF070A10),
    codeBorder = Color(0xFF1F2A3C),
    codeText = Color(0xFFE6EDF3),
    errorBg = Color(0xFF3B1219),
    errorBorder = Color(0xFFF87171),
    errorText = Color(0xFFFECACA)
)

private val LightPalette = CoAIdePalette(
    isDark = false,
    bg = Color(0xFFF4F7FB),
    surface = Color(0xFFFFFFFF),
    elevated = Color(0xFFE9EFF6),
    userBubble = Color(0xFFDCEEFF),
    accent = Color(0xFF0284C7),
    onAccent = Color(0xFFFFFFFF),
    text = Color(0xFF0F172A),
    muted = Color(0xFF475569),
    border = Color(0xFFCBD5E1),
    codeBg = Color(0xFF0F172A),
    codeBorder = Color(0xFF1E293B),
    codeText = Color(0xFFF8FAFC),
    errorBg = Color(0xFFFEE2E2),
    errorBorder = Color(0xFFDC2626),
    errorText = Color(0xFF7F1D1D)
)

data class ChatMessage(
    val sender: String,
    val role: String,
    val text: String,
    val imageUriString: String? = null,
    val fileName: String? = null,
    val isError: Boolean = false
)

private data class EncodedImage(
    val base64: String,
    val mimeType: String,
    val previewBitmap: Bitmap?
)

private data class AttachedTextFile(
    val fileName: String,
    val content: String
)

private sealed interface MessagePart {
    data class TextPart(val content: String) : MessagePart
    data class CodePart(val language: String, val code: String) : MessagePart
}

private fun parseMessageParts(rawText: String): List<MessagePart> {
    val parts = mutableListOf<MessagePart>()
    val regex = Regex("```([a-zA-Z0-9_-]*)\\n([\\s\\S]*?)```")
    var lastIndex = 0

    for (match in regex.findAll(rawText)) {
        if (match.range.first > lastIndex) {
            val before = rawText.substring(lastIndex, match.range.first).trim()
            if (before.isNotEmpty()) {
                parts.add(MessagePart.TextPart(before))
            }
        }
        val lang = match.groupValues[1].ifBlank { "code" }
        val codeBody = match.groupValues[2].trimEnd()
        parts.add(MessagePart.CodePart(lang, codeBody))
        lastIndex = match.range.last + 1
    }

    if (lastIndex < rawText.length) {
        val remaining = rawText.substring(lastIndex).trim()
        if (remaining.isNotEmpty()) {
            parts.add(MessagePart.TextPart(remaining))
        }
    }

    if (parts.isEmpty() && rawText.isNotBlank()) {
        parts.add(MessagePart.TextPart(rawText))
    }

    return parts
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    var name = "document.txt"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) {
            val fetched = cursor.getString(idx)
            if (!fetched.isNullOrBlank()) {
                name = fetched
            }
        }
    }
    return name
}

private suspend fun readTextFileFromUri(context: Context, uri: Uri): AttachedTextFile? =
    withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(context, uri)
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()
            if (text.isBlank()) null else AttachedTextFile(fileName = name, content = text)
        } catch (_: Exception) {
            null
        }
    }

private suspend fun encodeUriToBase64(context: Context, uri: Uri): EncodedImage? =
    withContext(Dispatchers.IO) {
        try {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null

            val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@withContext null

            val maxDim = 1280
            val scale = minOf(
                1f,
                maxDim.toFloat() / maxOf(originalBitmap.width, originalBitmap.height).toFloat()
            )
            val targetW = (originalBitmap.width * scale).toInt().coerceAtLeast(1)
            val targetH = (originalBitmap.height * scale).toInt().coerceAtLeast(1)
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, targetW, targetH, true)

            val outStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 82, outStream)
            val compressedBytes = outStream.toByteArray()
            val encoded = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)

            EncodedImage(
                base64 = encoded,
                mimeType = if (mime.startsWith("image/")) mime else "image/jpeg",
                previewBitmap = scaledBitmap
            )
        } catch (_: Exception) {
            null
        }
    }

private suspend fun loadBitmapFromUriString(context: Context, uriString: String): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

private fun welcomeMessage(): ChatMessage = ChatMessage(
    sender = "CoAIde",
    role = "model",
    text = "Namaste! Main CoAIde hoon (v3.35.5) — Aamir Alam dwara banaya gaya coding assistant. Bolkar, likh kar, file attach karke, ya image bhej kar sawaal poochein."
)

private fun saveChatHistory(context: Context, messages: List<ChatMessage>) {
    val prefs = context.getSharedPreferences("coaide_prefs", Context.MODE_PRIVATE)
    val arr = JSONArray()
    for (msg in messages) {
        val obj = JSONObject()
            .put("sender", msg.sender)
            .put("role", msg.role)
            .put("text", msg.text)
            .put("isError", msg.isError)
        if (!msg.imageUriString.isNullOrBlank()) {
            obj.put("imageUriString", msg.imageUriString)
        }
        if (!msg.fileName.isNullOrBlank()) {
            obj.put("fileName", msg.fileName)
        }
        arr.put(obj)
    }
    prefs.edit().putString("saved_history_json", arr.toString()).apply()
}

private fun loadChatHistory(context: Context): List<ChatMessage> {
    val prefs = context.getSharedPreferences("coaide_prefs", Context.MODE_PRIVATE)
    val raw = prefs.getString("saved_history_json", null) ?: return listOf(welcomeMessage())
    return try {
        val arr = JSONArray(raw)
        if (arr.length() == 0) return listOf(welcomeMessage())
        val list = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val text = obj.optString("text", "")
            val imgUri = obj.optString("imageUriString", "").ifBlank { null }
            val fName = obj.optString("fileName", "").ifBlank { null }
            if (text.isNotBlank() || imgUri != null || fName != null) {
                list.add(
                    ChatMessage(
                        sender = obj.optString("sender", "CoAIde"),
                        role = obj.optString("role", "model"),
                        text = text,
                        imageUriString = imgUri,
                        fileName = fName,
                        isError = obj.optBoolean("isError", false)
                    )
                )
            }
        }
        if (list.isEmpty()) listOf(welcomeMessage()) else list
    } catch (_: Exception) {
        listOf(welcomeMessage())
    }
}

private fun saveSettings(
    context: Context,
    autoScroll: Boolean,
    compactCards: Boolean,
    lightTheme: Boolean
) {
    context.getSharedPreferences("coaide_prefs", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("auto_scroll", autoScroll)
        .putBoolean("compact_cards", compactCards)
        .putBoolean("light_theme", lightTheme)
        .apply()
}

private fun loadAutoScroll(context: Context): Boolean =
    context.getSharedPreferences("coaide_prefs", Context.MODE_PRIVATE)
        .getBoolean("auto_scroll", true)

private fun loadCompactCards(context: Context): Boolean =
    context.getSharedPreferences("coaide_prefs", Context.MODE_PRIVATE)
        .getBoolean("compact_cards", false)

private fun loadLightTheme(context: Context): Boolean =
    context.getSharedPreferences("coaide_prefs", Context.MODE_PRIVATE)
        .getBoolean("light_theme", false)

@Composable
private fun TwoLineMenuIcon(tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val strokeWidth = 2.4.dp.toPx()
        val leftX = 3.dp.toPx()
        val rightX = size.width - 3.dp.toPx()
        val y1 = size.height * 0.36f
        val y2 = size.height * 0.64f

        drawLine(
            color = tint,
            start = Offset(leftX, y1),
            end = Offset(rightX, y1),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(leftX, y2),
            end = Offset(rightX, y2),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun PlusIcon(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = 2.4.dp.toPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val arm = size.width * 0.32f

        drawLine(
            color = tint,
            start = Offset(cx - arm, cy),
            end = Offset(cx + arm, cy),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(cx, cy - arm),
            end = Offset(cx, cy + arm),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun MicIcon(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = 2.1.dp.toPx()
        val w = size.width
        val h = size.height

        val capsuleW = w * 0.34f
        val capsuleH = h * 0.44f
        val capsuleLeft = (w - capsuleW) / 2f
        val capsuleTop = h * 0.14f

        drawRoundRect(
            color = tint,
            topLeft = Offset(capsuleLeft, capsuleTop),
            size = Size(capsuleW, capsuleH),
            cornerRadius = CornerRadius(capsuleW / 2f, capsuleW / 2f),
            style = Stroke(width = stroke)
        )

        val arcLeft = w * 0.24f
        val arcTop = h * 0.34f
        val arcWidth = w * 0.52f
        val arcHeight = h * 0.38f

        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(arcLeft, arcTop),
            size = Size(arcWidth, arcHeight),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        val stemTop = arcTop + arcHeight
        val stemBottom = h * 0.86f
        val centerX = w / 2f

        drawLine(
            color = tint,
            start = Offset(centerX, stemTop),
            end = Offset(centerX, stemBottom),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )

        drawLine(
            color = tint,
            start = Offset(w * 0.32f, stemBottom),
            end = Offset(w * 0.68f, stemBottom),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = CoAIdeRepository(
            workerUrl = BuildConfig.WORKER_URL,
            clientToken = BuildConfig.APP_CLIENT_TOKEN
        )

        setContent {
            CoAIdeScreen(repo = repo)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoAIdeScreen(repo: CoAIdeRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val clipboardManager = LocalClipboardManager.current

    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var plusMenuExpanded by remember { mutableStateOf(false) }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var encodedImage by remember { mutableStateOf<EncodedImage?>(null) }
    var attachedFile by remember { mutableStateOf<AttachedTextFile?>(null) }

    var autoScrollEnabled by remember { mutableStateOf(loadAutoScroll(context)) }
    var compactCardsEnabled by remember { mutableStateOf(loadCompactCards(context)) }
    var isLightTheme by remember { mutableStateOf(loadLightTheme(context)) }

    val palette = if (isLightTheme) LightPalette else DarkPalette

    val colorScheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.userBubble,
            onPrimaryContainer = palette.text,
            background = palette.bg,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.elevated,
            onSurfaceVariant = palette.muted,
            errorContainer = palette.errorBg,
            onErrorContainer = palette.errorText,
            error = palette.errorBorder
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            primaryContainer = palette.userBubble,
            onPrimaryContainer = palette.text,
            background = palette.bg,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.elevated,
            onSurfaceVariant = palette.muted,
            errorContainer = palette.errorBg,
            onErrorContainer = palette.errorText,
            error = palette.errorBorder
        )
    }

    val messages = remember {
        mutableStateListOf<ChatMessage>().apply {
            addAll(loadChatHistory(context))
        }
    }

    val userHistoryPrompts = remember(messages.size) {
        messages
            .filter { it.role == "user" && it.text.isNotBlank() }
            .map { it.text }
            .reversed()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            attachedFile = null
            scope.launch {
                encodedImage = encodeUriToBase64(context, uri)
            }
        }
    }

    val textFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = null
            encodedImage = null
            scope.launch {
                val read = readTextFileFromUri(context, uri)
                if (read != null) {
                    attachedFile = read
                } else {
                    Toast.makeText(
                        context,
                        "Is file ka text read nahi ho paaya.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim().orEmpty()
            if (spoken.isNotBlank()) {
                inputText = if (inputText.isBlank()) spoken else "$inputText $spoken"
            }
        }
    }

    fun launchVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "CoAIde se coding sawaal bolein...")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(
                context,
                "Is device par voice recognition available nahi hai.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(messages.size, isLoading, autoScrollEnabled) {
        saveChatHistory(context, messages)
        if (autoScrollEnabled && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            scrimColor = Color.Black.copy(alpha = 0.60f),
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = palette.surface,
                    drawerContentColor = palette.text,
                    modifier = Modifier
                        .widthIn(max = 310.dp)
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.elevated, RoundedCornerShape(14.dp))
                                .border(1.dp, palette.accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "CoAIde Menu",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = palette.text
                                )
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = palette.accent
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Creator: Aamir Alam",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.accent
                            )
                            Text(
                                text = "Package: ${BuildConfig.APPLICATION_ID}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = palette.muted
                            )
                        }

                        Text(
                            text = "QUICK PROMPTS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.muted
                        )

                        val quickPrompts = listOf(
                            "Jetpack Compose mein clean card component ka code likho",
                            "Is code/bug ko step-by-step explain aur fix karo: ",
                            "Android app architecture aur folder structure suggest karo"
                        )
                        quickPrompts.forEach { sample ->
                            Surface(
                                color = palette.bg,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, palette.border),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        inputText = sample
                                        scope.launch { drawerState.close() }
                                    }
                            ) {
                                Text(
                                    text = sample,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = palette.border)

                        Text(
                            text = "SETTINGS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.muted
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.bg, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Light Screen Mode",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.text
                                )
                                Text(
                                    text = if (isLightTheme) "Abhi Light mode on hai" else "Abhi Dark mode on hai",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.muted
                                )
                            }
                            Switch(
                                checked = isLightTheme,
                                onCheckedChange = { checked ->
                                    isLightTheme = checked
                                    saveSettings(context, autoScrollEnabled, compactCardsEnabled, isLightTheme)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = palette.onAccent,
                                    checkedTrackColor = palette.accent
                                )
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.bg, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto-Scroll",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.text
                                )
                                Text(
                                    text = "Naye message par apne aap neeche jaye",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.muted
                                )
                            }
                            Switch(
                                checked = autoScrollEnabled,
                                onCheckedChange = { checked ->
                                    autoScrollEnabled = checked
                                    saveSettings(context, autoScrollEnabled, compactCardsEnabled, isLightTheme)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = palette.onAccent,
                                    checkedTrackColor = palette.accent
                                )
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.bg, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Compact View",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.text
                                )
                                Text(
                                    text = "Kam padding ke saath zyada chat dikhaye",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.muted
                                )
                            }
                            Switch(
                                checked = compactCardsEnabled,
                                onCheckedChange = { checked ->
                                    compactCardsEnabled = checked
                                    saveSettings(context, autoScrollEnabled, compactCardsEnabled, isLightTheme)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = palette.onAccent,
                                    checkedTrackColor = palette.accent
                                )
                            )
                        }

                        HorizontalDivider(color = palette.border)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "HISTORY (${userHistoryPrompts.size})",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = palette.muted
                            )
                            if (messages.size > 1) {
                                TextButton(
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(26.dp),
                                    onClick = {
                                        messages.clear()
                                        messages.add(welcomeMessage())
                                        saveChatHistory(context, messages)
                                        scope.launch { drawerState.close() }
                                    }
                                ) {
                                    Text(
                                        text = "Clear All",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.errorBorder
                                    )
                                }
                            }
                        }

                        if (userHistoryPrompts.isEmpty()) {
                            Text(
                                text = "Abhi tak koi saved prompt history nahi hai.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.muted,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        } else {
                            userHistoryPrompts.forEach { pastPrompt ->
                                Surface(
                                    color = palette.bg,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, palette.border),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            inputText = pastPrompt
                                            scope.launch { drawerState.close() }
                                        }
                                ) {
                                    Text(
                                        text = pastPrompt,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.text,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.bg)
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Surface(
                    color = palette.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        if (drawerState.isClosed) drawerState.open() else drawerState.close()
                                    }
                                }
                            ) {
                                TwoLineMenuIcon(tint = palette.text)
                            }
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "CoAIde",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = palette.text
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = palette.accent.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = palette.accent.copy(alpha = 0.5f),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = "v${BuildConfig.VERSION_NAME}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = palette.accent
                                        )
                                    }
                                }
                                Text(
                                    text = "Built by Aamir Alam • ${if (isLightTheme) "Light" else "Dark"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                contentPadding = PaddingValues(horizontal = 6.dp),
                                onClick = {
                                    isLightTheme = !isLightTheme
                                    saveSettings(context, autoScrollEnabled, compactCardsEnabled, isLightTheme)
                                }
                            ) {
                                Text(
                                    text = if (isLightTheme) "Dark" else "Light",
                                    color = palette.accent,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                            TextButton(
                                enabled = !isLoading && messages.size > 1,
                                contentPadding = PaddingValues(horizontal = 6.dp),
                                onClick = {
                                    messages.clear()
                                    messages.add(welcomeMessage())
                                    saveChatHistory(context, messages)
                                }
                            ) {
                                Text("Reset", color = palette.accent, fontSize = 13.sp)
                            }
                        }
                    }
                }

                HorizontalDivider(color = palette.border)

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = if (compactCardsEnabled) 8.dp else 12.dp),
                    contentPadding = PaddingValues(vertical = if (compactCardsEnabled) 6.dp else 10.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compactCardsEnabled) 6.dp else 10.dp)
                ) {
                    items(messages) { msg ->
                        ChatBubbleCard(
                            message = msg,
                            compact = compactCardsEnabled,
                            palette = palette,
                            onCopy = { textToCopy ->
                                clipboardManager.setText(AnnotatedString(textToCopy))
                            }
                        )
                    }
                }

                Surface(
                    color = palette.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        if (isLoading) {
                            LinearProgressIndicator(
                                color = palette.accent,
                                trackColor = palette.elevated,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                            )
                        }

                        val currentPreview = encodedImage?.previewBitmap
                        if (currentPreview != null || selectedUri != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .background(palette.elevated, RoundedCornerShape(10.dp))
                                    .border(1.dp, palette.accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                    .padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (currentPreview != null) {
                                        Image(
                                            bitmap = currentPreview.asImageBitmap(),
                                            contentDescription = "Selected Image",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Image attached",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.text
                                    )
                                }
                                TextButton(
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp),
                                    onClick = {
                                        selectedUri = null
                                        encodedImage = null
                                    }
                                ) {
                                    Text("Remove", color = palette.errorBorder, fontSize = 12.sp)
                                }
                            }
                        }

                        val currentFile = attachedFile
                        if (currentFile != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .background(palette.elevated, RoundedCornerShape(10.dp))
                                    .border(1.dp, palette.accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "File: ${currentFile.fileName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp),
                                    onClick = { attachedFile = null }
                                ) {
                                    Text("Remove", color = palette.errorBorder, fontSize = 12.sp)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                OutlinedButton(
                                    enabled = !isLoading,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.size(46.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    border = BorderStroke(1.dp, palette.border),
                                    onClick = { plusMenuExpanded = true }
                                ) {
                                    PlusIcon(tint = palette.accent)
                                }

                                DropdownMenu(
                                    expanded = plusMenuExpanded,
                                    onDismissRequest = { plusMenuExpanded = false },
                                    containerColor = palette.elevated
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text("Gallery Image", color = palette.text)
                                        },
                                        onClick = {
                                            plusMenuExpanded = false
                                            galleryLauncher.launch("image/*")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text("Text / Code File", color = palette.text)
                                        },
                                        onClick = {
                                            plusMenuExpanded = false
                                            textFileLauncher.launch(
                                                arrayOf(
                                                    "text/*",
                                                    "application/json",
                                                    "application/xml",
                                                    "application/javascript",
                                                    "application/octet-stream"
                                                )
                                            )
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            OutlinedButton(
                                enabled = !isLoading,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(46.dp),
                                contentPadding = PaddingValues(0.dp),
                                border = BorderStroke(1.dp, palette.border),
                                onClick = { launchVoiceInput() }
                            ) {
                                MicIcon(tint = palette.accent)
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                placeholder = {
                                    Text(
                                        "Code ya bug poochein...",
                                        color = palette.muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = palette.text,
                                    unfocusedTextColor = palette.text,
                                    focusedContainerColor = palette.elevated,
                                    unfocusedContainerColor = palette.elevated,
                                    focusedBorderColor = palette.accent,
                                    unfocusedBorderColor = palette.border,
                                    cursorColor = palette.accent
                                ),
                                maxLines = 4
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Button(
                                enabled = !isLoading && (
                                    inputText.isNotBlank() ||
                                        encodedImage != null ||
                                        attachedFile != null
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(46.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.accent,
                                    contentColor = palette.onAccent,
                                    disabledContainerColor = palette.elevated,
                                    disabledContentColor = palette.muted
                                ),
                                onClick = {
                                    val prompt = inputText.trim()
                                    val imgToSend = encodedImage
                                    val uriStr = selectedUri?.toString()
                                    val fileToSend = attachedFile

                                    inputText = ""
                                    selectedUri = null
                                    encodedImage = null
                                    attachedFile = null

                                    val displayText = when {
                                        prompt.isNotBlank() -> prompt
                                        fileToSend != null -> "File bheji gayi: ${fileToSend.fileName}"
                                        else -> "Is image ko dekhein aur coding/error help dein."
                                    }

                                    messages.add(
                                        ChatMessage(
                                            sender = "Aamir",
                                            role = "user",
                                            text = displayText,
                                            imageUriString = uriStr,
                                            fileName = fileToSend?.fileName
                                        )
                                    )
                                    isLoading = true

                                    val historyPairs = messages
                                        .filter { !it.isError }
                                        .dropLast(1)
                                        .map { it.role to it.text }

                                    scope.launch {
                                        val result = repo.askCoAIde(
                                            history = historyPairs,
                                            userPrompt = displayText,
                                            imageBase64 = imgToSend?.base64,
                                            imageMime = imgToSend?.mimeType ?: "image/jpeg",
                                            attachedFileName = fileToSend?.fileName,
                                            attachedFileContent = fileToSend?.content
                                        )
                                        isLoading = false
                                        result.onSuccess { reply ->
                                            messages.add(ChatMessage("CoAIde", "model", reply))
                                        }.onFailure { err ->
                                            messages.add(
                                                ChatMessage(
                                                    sender = "Error",
                                                    role = "model",
                                                    text = err.message ?: "Kuch galat hua",
                                                    isError = true
                                                )
                                            )
                                        }
                                    }
                                }
                            ) {
                                Text("Run", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubbleCard(
    message: ChatMessage,
    compact: Boolean,
    palette: CoAIdePalette,
    onCopy: (String) -> Unit
) {
    val context = LocalContext.current
    val isUser = message.role == "user"
    var copied by remember(message.text) { mutableStateOf(false) }
    var loadedBitmap by remember(message.imageUriString) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(message.imageUriString) {
        if (!message.imageUriString.isNullOrBlank()) {
            loadedBitmap = loadBitmapFromUriString(context, message.imageUriString)
        }
    }

    val containerColor = when {
        message.isError -> palette.errorBg
        isUser -> palette.userBubble
        else -> palette.surface
    }

    val borderStroke = when {
        message.isError -> BorderStroke(1.dp, palette.errorBorder)
        isUser -> BorderStroke(1.dp, palette.accent.copy(alpha = 0.35f))
        else -> BorderStroke(1.dp, palette.border)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 345.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = containerColor,
            border = borderStroke
        ) {
            Column(modifier = Modifier.padding(if (compact) 9.dp else 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.sender,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            message.isError -> palette.errorBorder
                            isUser -> palette.accent
                            else -> palette.muted
                        }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    TextButton(
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp),
                        onClick = {
                            onCopy(message.text)
                            copied = true
                        }
                    ) {
                        Text(
                            text = if (copied) "Copied" else "Copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.accent
                        )
                    }
                }

                if (!message.fileName.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Attached: ${message.fileName}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = palette.accent
                    )
                }

                if (loadedBitmap != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Image(
                        bitmap = loadedBitmap!!.asImageBitmap(),
                        contentDescription = "Sent Image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                SelectionContainer {
                    val parts = remember(message.text) { parseMessageParts(message.text) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        parts.forEach { part ->
                            when (part) {
                                is MessagePart.TextPart -> {
                                    Text(
                                        text = part.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (message.isError) palette.errorText else palette.text
                                    )
                                }

                                is MessagePart.CodePart -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                color = palette.codeBg,
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = palette.codeBorder,
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = part.language,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color(0xFF94A3B8)
                                            )
                                            TextButton(
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                modifier = Modifier.height(24.dp),
                                                onClick = { onCopy(part.code) }
                                            ) {
                                                Text(
                                                    text = "Copy Code",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF38BDF8)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = part.code,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = palette.codeText,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState())
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
