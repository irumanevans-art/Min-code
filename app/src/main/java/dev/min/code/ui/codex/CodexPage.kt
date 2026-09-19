package dev.min.code.ui.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.min.code.R
import dev.min.code.core.codex.CodexDecision
import dev.min.code.core.codex.CodexEvent
import dev.min.code.core.session.ChatItem
import dev.min.code.core.session.SessionStatus
import dev.min.code.core.settings.CodexAuthMode
import dev.min.code.core.settings.CodexProfile
import dev.min.code.ui.components.BackButton
import dev.min.code.ui.components.InkButton
import dev.min.code.ui.components.InkButtonTone
import dev.min.code.ui.components.InkTextField
import dev.min.code.ui.components.InkTopBar
import dev.min.code.ui.components.Notice
import dev.min.code.ui.components.NoticeTone
import dev.min.code.ui.components.PaperCard
import dev.min.code.ui.nav.LocalNavController
import dev.min.code.ui.nav.Screen
import dev.min.code.ui.theme.JetbrainsMono
import org.koin.androidx.compose.koinViewModel

@Composable
fun CodexPage(vm: CodexVM = koinViewModel()) {
    val runtime by vm.runtimeStatus.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val navigator = LocalNavController.current
    val scroll = rememberScrollState()
    var input by remember { mutableStateOf("") }
    var apiKey by remember(profile.apiKey) { mutableStateOf(profile.apiKey) }
    var baseUrl by remember(profile.baseUrl) { mutableStateOf(profile.baseUrl) }
    var mode by remember(profile.authMode) { mutableStateOf(profile.authMode) }

    Scaffold(
        topBar = {
            InkTopBar(
                title = stringResource(R.string.codex_title),
                subtitle = session.status.name.lowercase(),
                navigationIcon = { BackButton() },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!runtime.installed) {
                    Notice(
                        text = runtime.error ?: stringResource(R.string.codex_install_hint),
                        tone = if (runtime.error != null) NoticeTone.Error else NoticeTone.Info,
                    )
                    InkButton(
                        onClick = vm::install,
                        busy = runtime.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.codex_install)) }
                } else {
                    PaperCard {
                        Text(stringResource(R.string.codex_connection), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            InkButton(onClick = { mode = CodexAuthMode.CLI }, tone = if (mode == CodexAuthMode.CLI) InkButtonTone.Ink else InkButtonTone.Paper, compact = true, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.codex_auth_cli)) }
                            InkButton(onClick = { mode = CodexAuthMode.OPENAI_API_KEY }, tone = if (mode == CodexAuthMode.OPENAI_API_KEY) InkButtonTone.Ink else InkButtonTone.Paper, compact = true, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.codex_auth_openai)) }
                            InkButton(onClick = { mode = CodexAuthMode.RELAY }, tone = if (mode == CodexAuthMode.RELAY) InkButtonTone.Ink else InkButtonTone.Paper, compact = true, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.codex_auth_relay)) }
                        }
                        if (mode != CodexAuthMode.CLI) {
                            InkTextField(value = apiKey, onValueChange = { apiKey = it }, label = stringResource(R.string.codex_api_key), singleLine = true, visualTransformation = PasswordVisualTransformation())
                            if (mode == CodexAuthMode.RELAY) InkTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = stringResource(R.string.codex_base_url), singleLine = true)
                        }
                        InkButton(onClick = { vm.saveProfile(profile.copy(apiKey = apiKey, baseUrl = baseUrl, authMode = mode)) }, modifier = Modifier.fillMaxWidth(), tone = InkButtonTone.Paper) { Text(stringResource(R.string.codex_save_connection)) }
                    }
                    Text(
                        runtime.version ?: stringResource(R.string.codex_installed),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!runtime.authenticated) {
                        Notice(
                            text = runtime.loginStatus ?: stringResource(R.string.codex_login_hint),
                            tone = NoticeTone.Warn,
                        )
                        InkButton(
                            onClick = { navigator.navigate(Screen.Terminal) },
                            tone = InkButtonTone.Paper,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.codex_open_login_terminal)) }
                        Text(
                            "/opt/codex/bin/codex login --device-auth",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetbrainsMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (session.status == SessionStatus.Running) {
                            InkButton(onClick = vm::stop, tone = InkButtonTone.Paper, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.codex_stop))
                            }
                        } else {
                            InkButton(
                                onClick = { vm.start() },
                                enabled = runtime.authenticated,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.codex_start))
                            }
                        }
                    }
                }

                session.errorMessage?.let { Notice(it, tone = NoticeTone.Error) }
                session.pendingApproval?.let { approval ->
                    val body = approval.command
                        ?: approval.reason
                        ?: stringResource(
                            if (approval.kind == CodexEvent.ApprovalRequest.Kind.Command) {
                                R.string.codex_command_approval
                            } else {
                                R.string.codex_file_approval
                            },
                        )
                    PaperCard {
                        Text(body, style = MaterialTheme.typography.bodyMedium, fontFamily = JetbrainsMono)
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                            InkButton(
                                onClick = { vm.answerApproval(CodexDecision.DECLINE) },
                                tone = InkButtonTone.Paper,
                                compact = true,
                            ) {
                                Text(stringResource(R.string.codex_deny))
                            }
                            InkButton(
                                onClick = { vm.answerApproval(CodexDecision.ACCEPT) },
                                compact = true,
                            ) {
                                Text(stringResource(R.string.codex_approve))
                            }
                        }
                    }
                }

                // 会话历史。下一步会换成和 Claude 那边同一套 transcript 渲染
                session.items.forEach { item ->
                    key(item.id) {
                        when (item) {
                            is ChatItem.UserText -> PaperCard { Text(item.text) }
                            is ChatItem.AssistantText -> PaperCard { Text(item.text) }
                            is ChatItem.Thinking -> PaperCard {
                                Text(
                                    item.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            is ChatItem.Note -> Notice(
                                item.text,
                                tone = if (item.isError) NoticeTone.Error else NoticeTone.Info,
                            )

                            is ChatItem.ProcessOutput -> PaperCard {
                                Text(
                                    item.lines.joinToString("\n"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetbrainsMono,
                                )
                            }

                            is ChatItem.ToolCall -> PaperCard {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = JetbrainsMono,
                                )
                                item.result?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetbrainsMono,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                if (session.streamingThinking.isNotBlank()) {
                    PaperCard {
                        Text(
                            session.streamingThinking,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (session.streamingText.isNotBlank()) {
                    PaperCard { Text(session.streamingText, style = MaterialTheme.typography.bodyMedium) }
                }

                InkTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.codex_prompt),
                    minLines = 3,
                    enabled = session.status == SessionStatus.Running && !session.busy,
                )
                InkButton(
                    onClick = { if (vm.send(input)) input = "" },
                    enabled = input.isNotBlank() && session.status == SessionStatus.Running && !session.busy,
                    busy = session.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.codex_send)) }
            }
        }
    }
}
