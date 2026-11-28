package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.SshAuthType
import me.rerere.rikkahub.data.model.SshHost
import me.rerere.rikkahub.data.model.SshSecret
import me.rerere.rikkahub.data.security.SshSecretStore
import me.rerere.rikkahub.data.ssh.SshManager
import me.rerere.rikkahub.data.ssh.SshTestResult
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType
import org.koin.compose.koinInject

/**
 * 远程主机（SSH）：保存主机、测试连通性、首次连接确认并固定主机指纹。
 *
 * 与 dsh-Go 的主机模型一致：密码/私钥只存本机加密存储，指纹 TOFU，未信任的主机
 * 不会被 AI 工具悄悄接受。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshHostsPage() {
    val settingsStore: SettingsStore = koinInject()
    val secretStore: SshSecretStore = koinInject()
    val sshManager: SshManager = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current

    var editorVisible by remember { mutableStateOf(false) }
    var editorHost by remember { mutableStateOf<SshHost?>(null) }
    var tunnelBusyId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ssh_hosts_page_title)) },
                navigationIcon = { BackButton() },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editorHost = null
                    editorVisible = true
                },
            ) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.ssh_host_add))
            }
        },
    ) { padding ->
        if (settings.sshHosts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = HugeIcons.ComputerTerminal01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = stringResource(R.string.ssh_hosts_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(settings.sshHosts, key = { it.id }) { host ->
                    SshHostCard(
                        host = host,
                        onEdit = {
                            editorHost = host
                            editorVisible = true
                        },
                        tunnelBusy = tunnelBusyId == host.id,
                        onOpenTunnel = {
                            scope.launch {
                                tunnelBusyId = host.id
                                runCatching { sshManager.ensureTunnel(host) }
                                    .onSuccess { toaster.show("SSH 隧道已打开", type = ToastType.Success) }
                                    .onFailure { toaster.show(it.message ?: "SSH 隧道打开失败", type = ToastType.Error) }
                                tunnelBusyId = null
                            }
                        },
                        onDelete = {
                            scope.launch {
                                settingsStore.update { it.copy(sshHosts = it.sshHosts.filterNot { h -> h.id == host.id }) }
                                secretStore.delete(host.id)
                                sshManager.close()
                                toaster.show("已删除 " + host.displayName, type = ToastType.Success)
                            }
                        },
                    )
                }
            }
        }
    }

    if (editorVisible) {
        SshHostEditorDialog(
            initial = editorHost,
            secretStore = secretStore,
            onTest = { host, secret -> sshManager.testConnection(host, secret) },
            onDismiss = { editorVisible = false },
            onSave = { host, secret, pinned ->
                scope.launch {
                    val updated = if (pinned) host else host.withoutPinnedKey()
                    settingsStore.update { current ->
                        current.copy(sshHosts = current.sshHosts.filterNot { h -> h.id == updated.id } + updated)
                    }
                    if (secret != null) secretStore.save(updated.id, secret)
                    sshManager.close()
                    toaster.show("已保存 " + updated.displayName, type = ToastType.Success)
                    editorVisible = false
                }
            },
        )
    }
}

@Composable
private fun SshHostCard(
    host: SshHost,
    tunnelBusy: Boolean,
    onEdit: () -> Unit,
    onOpenTunnel: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = host.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (host.trusted) {
                        stringResource(R.string.ssh_host_trusted)
                    } else {
                        stringResource(R.string.ssh_host_untrusted)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (host.trusted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (host.forwardingEnabled) {
                    Text(
                        text = "隧道 127.0.0.1:" + host.forwardLocalPort +
                            " -> 127.0.0.1:" + host.forwardRemotePort,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (host.forwardingEnabled && host.trusted) {
                TextButton(onClick = onOpenTunnel, enabled = !tunnelBusy) {
                    Text(stringResource(R.string.ssh_host_tunnel_open))
                }
            }
            IconButton(onClick = onEdit) {
                Icon(HugeIcons.Edit02, contentDescription = stringResource(R.string.ssh_host_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.ssh_host_delete))
            }
        }
    }
}

@Composable
private fun SshHostEditorDialog(
    initial: SshHost?,
    secretStore: SshSecretStore,
    onTest: suspend (SshHost, SshSecret) -> SshTestResult,
    onDismiss: () -> Unit,
    onSave: (SshHost, SshSecret?, Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val existingSecret = remember(initial) { initial?.let { secretStore.load(it.id) } }

    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(initial) { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember(initial) { mutableStateOf(initial?.user.orEmpty()) }
    var auth by remember(initial) { mutableStateOf(initial?.auth ?: SshAuthType.PASSWORD) }
    var password by remember(initial) { mutableStateOf(existingSecret?.password.orEmpty()) }
    var privateKey by remember(initial) { mutableStateOf(existingSecret?.privateKeyPem.orEmpty()) }
    var passphrase by remember(initial) { mutableStateOf(existingSecret?.passphrase.orEmpty()) }
    var workingDirectory by remember(initial) { mutableStateOf(initial?.workingDirectory.orEmpty()) }
    var forwardLocalPort by remember(initial) {
        mutableStateOf(initial?.forwardLocalPort?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var forwardRemotePort by remember(initial) {
        mutableStateOf(initial?.forwardRemotePort?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<SshTestResult?>(null) }

    fun buildHost(): SshHost = (initial ?: SshHost()).copy(
        name = name.trim(),
        host = host.trim(),
        port = port.toIntOrNull()?.coerceIn(1, 65535) ?: 22,
        user = user.trim(),
        auth = auth,
        workingDirectory = workingDirectory.trim(),
        forwardLocalPort = forwardLocalPort.toIntOrNull() ?: 0,
        forwardRemotePort = forwardRemotePort.toIntOrNull() ?: 0,
    )

    fun buildSecret(): SshSecret = SshSecret(
        password = password,
        privateKeyPem = privateKey,
        passphrase = passphrase,
    )

    val canSave = host.isNotBlank() && user.isNotBlank() &&
        (initial?.trusted == true || testResult?.ok == true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial == null) {
                    stringResource(R.string.ssh_host_add)
                } else {
                    stringResource(R.string.ssh_host_edit)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ssh_host_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; testResult = null },
                    label = { Text(stringResource(R.string.ssh_host_host)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit); testResult = null },
                    label = { Text(stringResource(R.string.ssh_host_port)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it; testResult = null },
                    label = { Text(stringResource(R.string.ssh_host_user)) },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = auth == SshAuthType.PASSWORD,
                        onClick = { auth = SshAuthType.PASSWORD; testResult = null },
                        label = { Text(stringResource(R.string.ssh_host_auth_password)) },
                    )
                    FilterChip(
                        selected = auth == SshAuthType.PRIVATE_KEY,
                        onClick = { auth = SshAuthType.PRIVATE_KEY; testResult = null },
                        label = { Text(stringResource(R.string.ssh_host_auth_private_key)) },
                    )
                }
                if (auth == SshAuthType.PASSWORD) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; testResult = null },
                        label = { Text(stringResource(R.string.ssh_host_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                } else {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it; testResult = null },
                        label = { Text(stringResource(R.string.ssh_host_private_key)) },
                        minLines = 4,
                        maxLines = 8,
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it; testResult = null },
                        label = { Text(stringResource(R.string.ssh_host_passphrase)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                OutlinedTextField(
                    value = workingDirectory,
                    onValueChange = { workingDirectory = it },
                    label = { Text(stringResource(R.string.ssh_host_working_dir)) },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = forwardLocalPort,
                        onValueChange = { forwardLocalPort = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.ssh_host_forward_local)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = forwardRemotePort,
                        onValueChange = { forwardRemotePort = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.ssh_host_forward_remote)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(R.string.ssh_host_forward_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.ssh_host_secret_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                testResult?.let { result ->
                    if (result.ok) {
                        Text(
                            text = stringResource(
                                R.string.ssh_host_fingerprint,
                                result.hostKeyFingerprint ?: "",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.ssh_host_fingerprint_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.ssh_host_test_failed, result.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val secret = buildSecret().takeIf { it.isConfigured }
                    var host = buildHost()
                    // 换了地址/端口后旧指纹属于另一台机器，必须重新测试确认
                    if (initial != null &&
                        testResult?.ok != true &&
                        (host.host != initial.host || host.port != initial.port)
                    ) {
                        host = host.withoutPinnedKey()
                    }
                    onSave(host, secret, testResult?.ok == true)
                },
            ) {
                Text(
                    if (testResult?.ok == true && initial?.trusted != true) {
                        stringResource(R.string.ssh_host_trust_and_save)
                    } else {
                        stringResource(R.string.ssh_host_save)
                    }
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !testing,
                    onClick = {
                        scope.launch {
                            testing = true
                            testResult = onTest(buildHost(), buildSecret())
                            testing = false
                        }
                    },
                ) {
                    Text(stringResource(R.string.ssh_host_test))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}
