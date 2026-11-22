package code.ai.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

private val StudioBg = Color(0xFF0B0F17)
private val StudioSurface = Color(0xFF131A26)
private val StudioElevated = Color(0xFF1A2436)
private val StudioUserBubble = Color(0xFF16324F)
private val StudioAccent = Color(0xFF38BDF8)
private val StudioText = Color(0xFFE6EDF3)
private val StudioMuted = Color(0xFF8B949E)
private val StudioCodeBg = Color(0xFF070A10)
private val StudioErrorBg = Color(0xFF3B1219)
private val StudioErrorBorder = Color(0xFFF87171)

data class ChatMessage(
    val sender: String,
    val role: String,
    val text: String,
    val imageUriString: String? = null,
    val isError: Boolean = false
)

private data class EncodedImage(
    val base64: String,
    val mimeType: String,
    val previewBitmap: Bitmap?
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
    text = "Namaste! Main CoAIde hoon (v3.35.5) — Aamir Alam dwara banaya gaya coding assistant. Code, bugs, ya gallery se screenshot/image bhej kar sawaal poochein."
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
            if (text.isNotBlank() || imgUri != null) {
                list.add(
                    ChatMessage(
                        sender = obj.optString("sender", "CoAIde"),
                        role = obj.optString("role", "model"),
                        text = text,
                        imageUriString = imgUri,
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = CoAIdeRepository(
            workerUrl = BuildConfig.WORKER_URL,
            clientToken = BuildConfig.APP_CLIENT_TOKEN
        )

        val darkScheme = darkColorScheme(
            primary = StudioAccent,
            onPrimary = Color(0xFF04131D),
            primaryContainer = StudioUserBubble,
            onPrimaryContainer = StudioText,
            background = StudioBg,
            onBackground = StudioText,
            surface = StudioSurface,
            onSurface = StudioText,
            surfaceVariant = StudioElevated,
            onSurfaceVariant = StudioMuted,
            errorContainer = StudioErrorBg,
            onErrorContainer = Color(0xFFFECACA),
            error = StudioErrorBorder
        )

        setContent {
            MaterialTheme(colorScheme = darkScheme) {
                CoAIdeScreen(repo = repo)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoAIdeScreen(repo: CoAIdeRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current

    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var encodedImage by remember { mutableStateOf<EncodedImage?>(null) }

    val messages = remember {
        mutableStateListOf<ChatMessage>().apply {
            addAll(loadChatHistory(context))
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            scope.launch {
                encodedImage = encodeUriToBase64(context, uri)
            }
        }
    }

    LaunchedEffect(messages.size, isLoading) {
        saveChatHistory(context, messages)
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        containerColor = StudioBg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = StudioSurface,
                    titleContentColor = StudioText
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "CoAIde",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 19.sp,
                                    color = StudioText
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = StudioAccent.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = StudioAccent.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "v${BuildConfig.VERSION_NAME}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = StudioAccent
                                    )
                                }
                            }
                            Text(
                                text = "Built by Aamir Alam • History Saved",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioMuted
                            )
                        }
                    }
                },
                actions = {
                    TextButton(
                        enabled = !isLoading && messages.size > 1,
                        onClick = {
                            messages.clear()
                            messages.add(welcomeMessage())
                            saveChatHistory(context, messages)
                        }
                    ) {
                        Text("Reset", color = StudioAccent)
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = StudioSurface,
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (isLoading) {
                        LinearProgressIndicator(
                            color = StudioAccent,
                            trackColor = StudioElevated,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                    }

                    val currentPreview = encodedImage?.previewBitmap
                    if (currentPreview != null || selectedUri != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .background(StudioElevated, RoundedCornerShape(12.dp))
                                .border(1.dp, StudioAccent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(8.dp),
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
                                            .size(54.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Image attached",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioText
                                )
                            }
                            TextButton(
                                onClick = {
                                    selectedUri = null
                                    encodedImage = null
                                }
                            ) {
                                Text("Remove", color = StudioErrorBorder)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        OutlinedButton(
                            enabled = !isLoading,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(52.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            border = BorderStroke(1.dp, Color(0xFF283548)),
                            onClick = { galleryLauncher.launch("image/*") }
                        ) {
                            Text(
                                text = "Img",
                                color = StudioAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            placeholder = {
                                Text(
                                    "Code ya bug poochein...",
                                    color = StudioMuted
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = StudioText,
                                unfocusedTextColor = StudioText,
                                focusedContainerColor = StudioElevated,
                                unfocusedContainerColor = StudioElevated,
                                focusedBorderColor = StudioAccent,
                                unfocusedBorderColor = Color(0xFF283548),
                                cursorColor = StudioAccent
                            ),
                            maxLines = 5
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Button(
                            enabled = !isLoading && (inputText.isNotBlank() || encodedImage != null),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StudioAccent,
                                contentColor = Color(0xFF04131D),
                                disabledContainerColor = StudioElevated,
                                disabledContentColor = StudioMuted
                            ),
                            onClick = {
                                val prompt = inputText.trim()
                                val imgToSend = encodedImage
                                val uriStr = selectedUri?.toString()

                                inputText = ""
                                selectedUri = null
                                encodedImage = null

                                val displayText = if (prompt.isNotBlank()) {
                                    prompt
                                } else {
                                    "Is image ko dekhein aur coding/error help dein."
                                }

                                messages.add(
                                    ChatMessage(
                                        sender = "Aamir",
                                        role = "user",
                                        text = displayText,
                                        imageUriString = uriStr
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
                                        imageMime = imgToSend?.mimeType ?: "image/jpeg"
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
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { msg ->
                ChatBubbleCard(
                    message = msg,
                    onCopy = { textToCopy ->
                        clipboardManager.setText(AnnotatedString(textToCopy))
                    }
                )
            }
        }
    }
}

@Composable
private fun ChatBubbleCard(
    message: ChatMessage,
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
        message.isError -> StudioErrorBg
        isUser -> StudioUserBubble
        else -> StudioSurface
    }

    val borderStroke = when {
        message.isError -> BorderStroke(1.dp, StudioErrorBorder)
        isUser -> BorderStroke(1.dp, StudioAccent.copy(alpha = 0.35f))
        else -> BorderStroke(1.dp, Color(0xFF233044))
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
            Column(modifier = Modifier.padding(12.dp)) {
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
                            message.isError -> StudioErrorBorder
                            isUser -> StudioAccent
                            else -> StudioMuted
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
                            color = StudioAccent
                        )
                    }
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
                                        color = if (message.isError) Color(0xFFFECACA) else StudioText
                                    )
                                }

                                is MessagePart.CodePart -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                color = StudioCodeBg,
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = Color(0xFF1F2A3C),
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
                                                color = StudioMuted
                                            )
                                            TextButton(
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                                modifier = Modifier.height(24.dp),
                                                onClick = { onCopy(part.code) }
                                            ) {
                                                Text(
                                                    text = "Copy Code",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = StudioAccent
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = part.code,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = StudioText,
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
