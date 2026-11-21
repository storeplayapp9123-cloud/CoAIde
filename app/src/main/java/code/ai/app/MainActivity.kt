package code.ai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class ChatMessage(
    val sender: String,
    val role: String,
    val text: String,
    val isError: Boolean = false
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = CoAIdeRepository(
            workerUrl = BuildConfig.WORKER_URL,
            clientToken = BuildConfig.APP_CLIENT_TOKEN
        )

        setContent {
            MaterialTheme {
                CoAIdeScreen(repo = repo)
            }
        }
    }
}

private fun welcomeMessage(): ChatMessage = ChatMessage(
    sender = "CoAIde",
    role = "model",
    text = "Namaste! Main CoAIde hoon. Coding, bugs, Kotlin/Java/Compose, ya programming logic se jude sawaal poochein."
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoAIdeScreen(repo: CoAIdeRepository) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current

    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf(welcomeMessage()) }

    LaunchedEffect(messages.size, isLoading) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "CoAIde",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                        Text(
                            text = "AI Code Assistant",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = !isLoading && messages.size > 1,
                        onClick = {
                            messages.clear()
                            messages.add(welcomeMessage())
                        }
                    ) {
                        Text("Clear")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 6.dp
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            placeholder = { Text("CoAIde se code poochein...") },
                            maxLines = 5
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            enabled = !isLoading && inputText.isNotBlank(),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(52.dp),
                            onClick = {
                                val prompt = inputText.trim()
                                inputText = ""
                                messages.add(ChatMessage("Aap", "user", prompt))
                                isLoading = true

                                val historyPairs = messages
                                    .filter { !it.isError }
                                    .dropLast(1)
                                    .map { it.role to it.text }

                                scope.launch {
                                    val result = repo.askCoAIde(historyPairs, prompt)
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
                            Text("Bhejein")
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
    val isUser = message.role == "user"
    var copied by remember(message.text) { mutableStateOf(false) }

    val containerColor = when {
        message.isError -> MaterialTheme.colorScheme.errorContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val borderColor = when {
        message.isError -> MaterialTheme.colorScheme.error
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 340.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = containerColor,
            border = if (message.isError) BorderStroke(1.dp, borderColor) else null
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
                        color = if (message.isError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
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
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                val parts = remember(message.text) { parseMessageParts(message.text) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    parts.forEach { part ->
                        when (part) {
                            is MessagePart.TextPart -> {
                                Text(
                                    text = part.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (message.isError) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }

                            is MessagePart.CodePart -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            color = Color(0xFF161B22),
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
                                            color = Color(0xFF8B949E)
                                        )
                                        TextButton(
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                            modifier = Modifier.height(24.dp),
                                            onClick = { onCopy(part.code) }
                                        ) {
                                            Text(
                                                text = "Copy Code",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF58A6FF)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = part.code,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFE6EDF3),
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
