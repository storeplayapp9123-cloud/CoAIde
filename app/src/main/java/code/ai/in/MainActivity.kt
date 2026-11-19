package code.ai.in

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

data class ChatMessage(val sender: String, val role: String, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = CoAIdeRepository(BuildConfig.GEMINI_API_KEY)

        setContent {
            MaterialTheme {
                CoAIdeScreen(repo = repo)
            }
        }
    }
}

@Composable
fun CoAIdeScreen(repo: CoAIdeRepository) {
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                sender = "CoAIde",
                role = "model",
                text = "Namaste! Main CoAIde hoon. Sirf coding, bugs, ya programming logic se jude sawaal poochein."
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(
            text = "CoAIde",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (msg.role == "user")
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(text = msg.sender, style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = msg.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("CoAIde se code poochein...") },
                maxLines = 4
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                enabled = !isLoading && inputText.isNotBlank(),
                onClick = {
                    val prompt = inputText.trim()
                    inputText = ""
                    messages.add(ChatMessage("Aap", "user", prompt))
                    isLoading = true

                    val historyPairs = messages
                        .dropLast(1)
                        .map { it.role to it.text }

                    scope.launch {
                        val result = repo.askCoAIde(historyPairs, prompt)
                        isLoading = false
                        result.onSuccess { reply ->
                            messages.add(ChatMessage("CoAIde", "model", reply))
                        }.onFailure { err ->
                            messages.add(ChatMessage("Error", "model", err.message ?: "Kuch galat hua"))
                        }
                    }
                }
            ) {
                Text("Bhejein")
            }
        }
    }
}
