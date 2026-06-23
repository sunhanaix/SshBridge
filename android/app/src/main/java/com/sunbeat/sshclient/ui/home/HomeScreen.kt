package com.sunbeat.sshclient.ui.home

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sunbeat.sshclient.data.import.ConfigImporter
import com.sunbeat.sshclient.domain.model.AuthType
import com.sunbeat.sshclient.domain.model.Session
import com.sunbeat.sshclient.ui.components.ConnectionCard
import kotlinx.coroutines.launch

// Display item: folder node or session leaf
private data class DisplayItem(
    val isFolder: Boolean,
    val isSession: Boolean,
    val path: String = "",
    val name: String = "",
    val depth: Int = 0,
    val count: Int = 0,
    val session: Session? = null,
)

private fun buildDisplayTree(
    sessions: List<Session>,
    expandedPaths: Map<String, Boolean>,
    allExpanded: Boolean,
): List<DisplayItem> {
    // Build a prefix tree from all session folder paths
    data class TreeFolder(
        val name: String,
        val path: String,
        val depth: Int,
        val sessions: MutableList<Session> = mutableListOf(),
        val children: MutableMap<String, TreeFolder> = linkedMapOf(),
    )

    val root = TreeFolder("", "", 0)

    for (session in sessions) {
        val groupPath = session.groupName.ifEmpty { "Ungrouped" }
        if (groupPath == "Ungrouped") {
            root.sessions.add(session)
            continue
        }
        val parts = groupPath.split("/")
        var current = root
        var currentPath = ""
        for (part in parts) {
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            current = current.children.getOrPut(part) {
                TreeFolder(part, currentPath, current.depth + 1)
            }
        }
        current.sessions.add(session)
    }

    fun countLeaves(node: TreeFolder): Int =
        node.sessions.size + node.children.values.sumOf { countLeaves(it) }

    val result = mutableListOf<DisplayItem>()

    fun flatten(node: TreeFolder) {
        if (node.name.isNotEmpty()) {
            val count = countLeaves(node)
            val expanded = expandedPaths[node.path] ?: allExpanded
            result.add(
                DisplayItem(
                    isFolder = true, isSession = false,
                    path = node.path, name = node.name,
                    depth = node.depth, count = count,
                )
            )
            if (!expanded) return
        }
        // Add sessions at this level (sorted by name)
        node.sessions.sortedBy { it.name }.forEach { session ->
            result.add(
                DisplayItem(
                    isFolder = false, isSession = true,
                    session = session, depth = node.depth,
                )
            )
        }
        // Recurse into children (sorted by name)
        node.children.values.sortedBy { it.name }.forEach { flatten(it) }
    }

    flatten(root)
    return result
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onSessionClick: (Session) -> Unit,
    onSettingsClick: () -> Unit,
    onImportClick: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // ── New Group dialog ───────────────────────────────────────────────────
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }

    // ── Add Session dialog state ──────────────────────────────────────────
    var showAddDialog by remember { mutableStateOf(false) }
    var newHostname by remember { mutableStateOf("") }
    var newPort by remember { mutableStateOf("22") }
    var newUsername by remember { mutableStateOf("root") }
    var newPassword by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newAuthType by remember { mutableStateOf(AuthType.PASSWORD) }
    var newKeyPath by remember { mutableStateOf("") }
    var newKeyContent by remember { mutableStateOf("") }

    /** Parse base64 and fill add-dialog fields with first session's data. */
    fun fillFormFromBase64(raw: String): Boolean {
        val result = ConfigImporter.parse(raw)
        if (result.error != null || result.sessions.isEmpty()) {
            Toast.makeText(context, "No valid config in scan", Toast.LENGTH_SHORT).show()
            return false
        }
        val s = result.sessions.first()
        newName = s.name
        newHostname = s.hostname
        newPort = s.port.toString()
        newUsername = s.username
        newAuthType = s.authType
        newPassword = s.plainPassword ?: ""
        newKeyPath = s.identityFile ?: ""
        newKeyContent = s.keyContent ?: ""
        return true
    }

    fun fillFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip ?: return
        if (clip.itemCount == 0) return
        fillFormFromBase64(clip.getItemAt(0).coerceToText(context).toString())
    }

    // ── QR Code scanner ───────────────────────────────────────────────────
    var qrFillForm by remember { mutableStateOf(false) }
    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { contents ->
            if (qrFillForm) {
                qrFillForm = false
                fillFormFromBase64(contents)
            } else {
                viewModel.importFromBase64(contents)
            }
        }
    }

    // ── Clipboard import dialog ───────────────────────────────────────────
    var showClipImport by remember { mutableStateOf(false) }
    var clipText by remember { mutableStateOf("") }

    // Show snackbar on status message
    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    // ── Edit Session dialog state ─────────────────────────────────────────
    var showEditDialog by remember { mutableStateOf(false) }
    var editSession by remember { mutableStateOf<Session?>(null) }
    var editHostname by remember { mutableStateOf("") }
    var editPort by remember { mutableStateOf("22") }
    var editUsername by remember { mutableStateOf("") }
    var editPassword by remember { mutableStateOf("") }
    var editName by remember { mutableStateOf("") }
    var editAuthType by remember { mutableStateOf(AuthType.PASSWORD) }
    var editKeyPath by remember { mutableStateOf("") }
    var editKeyContent by remember { mutableStateOf("") }
    var editGroupName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }

    // ── Group rename / delete state ───────────────────────────────────────
    var showGroupActions by remember { mutableStateOf(false) }
    var groupActionTarget by remember { mutableStateOf("") }
    var showRenameGroup by remember { mutableStateOf(false) }
    var renameGroupNewName by remember { mutableStateOf("") }
    var showDeleteGroup by remember { mutableStateOf(false) }

    // ── Context menu dialog (long-press single session) ──────────────────
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuSession by remember { mutableStateOf<Session?>(null) }

    // ── Batch selection mode state ──────────────────────────────────────
    var showBatchMoveDialog by remember { mutableStateOf(false) }
    var batchMoveTarget by remember { mutableStateOf("") }

    // ── Folder expand state ───────────────────────────────────────────────
    val expandedPaths = remember { mutableStateMapOf<String, Boolean>() }
    var allExpanded by remember { mutableStateOf(true) }

    // ── Filter sessions ───────────────────────────────────────────────────
    val filteredSessions = uiState.sessions.filter { s ->
        uiState.searchQuery.isEmpty() ||
            s.name.contains(uiState.searchQuery, ignoreCase = true) ||
            s.hostname.contains(uiState.searchQuery, ignoreCase = true)
    }

    // ── Build tree from folder paths and flatten ──────────────────────────
    val displayItems = buildDisplayTree(filteredSessions, expandedPaths, allExpanded)

    // ── New Group dialog ──────────────────────────────────────────────────
    if (showNewGroupDialog) {
        val currentGroup = uiState.selectedGroup
        val groupPrefix = if (currentGroup.isNotEmpty()) "$currentGroup/" else ""
        AlertDialog(
            onDismissRequest = { showNewGroupDialog = false },
            title = {
                Text(if (currentGroup.isNotEmpty()) "New Subgroup" else "New Group")
            },
            text = {
                Column {
                    if (currentGroup.isNotEmpty()) {
                        Text(
                            "Creating under: $currentGroup",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        "Enter a name (e.g. \"Servers\"). " +
                            "After creating, new sessions will be placed here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (currentGroup.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Full path: $groupPrefix$newGroupName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newGroupName.trim()
                    if (name.isNotEmpty()) {
                        viewModel.createGroup(groupPrefix + name)
                    }
                    newGroupName = ""
                    showNewGroupDialog = false
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    newGroupName = ""
                    showNewGroupDialog = false
                }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Add Session dialog ────────────────────────────────────────────────
    if (showAddDialog) {
        val currentGroup = uiState.selectedGroup
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Session") },
            text = {
                Column {
                    // Show current group context
                    if (currentGroup.isNotEmpty()) {
                        Text(
                            "Group: $currentGroup",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newHostname,
                        onValueChange = { newHostname = it },
                        label = { Text("Hostname / IP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPort,
                        onValueChange = { newPort = it.filter { c -> c.isDigit() } },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newUsername,
                        onValueChange = { newUsername = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Authentication",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        AuthType.entries.forEach { at ->
                            TextButton(
                                onClick = { newAuthType = at },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = when (at) {
                                        AuthType.PASSWORD -> "Password"
                                        AuthType.KEY -> "Key"
                                        AuthType.BOTH -> "Key+Pwd"
                                    },
                                    color = if (newAuthType == at)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (newAuthType == at) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (newAuthType == AuthType.PASSWORD || newAuthType == AuthType.BOTH) {
                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { newPassword = it },
                            label = { Text("Password") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (newAuthType == AuthType.KEY || newAuthType == AuthType.BOTH) {
                        OutlinedTextField(
                            value = newKeyPath,
                            onValueChange = { newKeyPath = it },
                            label = { Text("Key file path (e.g. /sdcard/id_rsa)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newKeyContent,
                            onValueChange = { newKeyContent = it },
                            label = { Text("Private Key (PEM)") },
                            placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----") },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = { fillFromClipboard() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("📋 Paste")
                        }
                        TextButton(
                            onClick = {
                                qrFillForm = true
                                qrLauncher.launch(ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    setPrompt("Scan SSH config QR code")
                                    setBeepEnabled(false)
                                    setOrientationLocked(false)
                                })
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("📷 Scan QR")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val port = newPort.toIntOrNull() ?: 22
                    val name = newName.ifEmpty { newHostname.ifEmpty { "New Session" } }
                    val session = Session(
                        name = name,
                        hostname = newHostname.ifEmpty { "localhost" },
                        port = port,
                        username = newUsername.ifEmpty { "root" },
                        authType = newAuthType,
                        plainPassword = newPassword.ifEmpty { null },
                        identityFile = newKeyPath.ifEmpty { null },
                        keyContent = newKeyContent.ifEmpty { null },
                        sourceFile = "manual",
                    )
                    viewModel.addSession(session)
                    // Reset form
                    newName = ""
                    newHostname = ""
                    newPort = "22"
                    newUsername = "root"
                    newPassword = ""
                    newAuthType = AuthType.PASSWORD
                    newKeyPath = ""
                    newKeyContent = ""
                    showAddDialog = false
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Edit Session dialog ───────────────────────────────────────────────
    if (showEditDialog && editSession != null) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Session") },
            text = {
                Column {
                    // Group row with move button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Group",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = editGroupName.ifEmpty { "(none — ungrouped)" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (editGroupName.isEmpty())
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else
                                    MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        TextButton(onClick = { showMoveDialog = true }) {
                            Text("Move…")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editHostname,
                        onValueChange = { editHostname = it },
                        label = { Text("Hostname / IP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editPort,
                        onValueChange = { editPort = it.filter { c -> c.isDigit() } },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editUsername,
                        onValueChange = { editUsername = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Authentication",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        AuthType.entries.forEach { at ->
                            TextButton(
                                onClick = { editAuthType = at },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = when (at) {
                                        AuthType.PASSWORD -> "Password"
                                        AuthType.KEY -> "Key"
                                        AuthType.BOTH -> "Key+Pwd"
                                    },
                                    color = if (editAuthType == at)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (editAuthType == at) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (editAuthType == AuthType.PASSWORD || editAuthType == AuthType.BOTH) {
                        OutlinedTextField(
                            value = editPassword,
                            onValueChange = { editPassword = it },
                            label = { Text("Password") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (editAuthType == AuthType.KEY || editAuthType == AuthType.BOTH) {
                        OutlinedTextField(
                            value = editKeyPath,
                            onValueChange = { editKeyPath = it },
                            label = { Text("Key file path (e.g. /sdcard/id_rsa)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editKeyContent,
                            onValueChange = { editKeyContent = it },
                            label = { Text("Private Key (PEM)") },
                            placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----") },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val session = editSession ?: return@TextButton
                    val port = editPort.toIntOrNull() ?: 22
                    val name = editName.ifEmpty { editHostname.ifEmpty { "Session" } }
                    viewModel.updateSession(
                        session.copy(
                            name = name,
                            groupName = editGroupName,
                            hostname = editHostname.ifEmpty { "localhost" },
                            port = port,
                            username = editUsername.ifEmpty { "root" },
                            authType = editAuthType,
                            plainPassword = editPassword.ifEmpty { null },
                            identityFile = editKeyPath.ifEmpty { null },
                            keyContent = editKeyContent.ifEmpty { null },
                        )
                    )
                    showEditDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showEditDialog = false }) {
                        Text("Cancel")
                    }
                }
            },
        )
    }

    // ── Move to Group dialog ────────────────────────────────────────────────
    if (showMoveDialog) {
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text("Move to Group") },
            text = {
                Column {
                    Text(
                        "Select target group for \"${editSession?.name ?: ""}\":",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(280.dp)) {
                        item {
                            TextButton(
                                onClick = {
                                    editGroupName = ""
                                    showMoveDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "(no group — ungrouped)",
                                    color = if (editGroupName.isEmpty())
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        items(uiState.groups) { group ->
                            TextButton(
                                onClick = {
                                    editGroupName = group
                                    showMoveDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    group,
                                    color = if (editGroupName == group)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Group actions dialog (long-press on folder) ─────────────────────────
    if (showGroupActions) {
        AlertDialog(
            onDismissRequest = { showGroupActions = false },
            title = { Text(groupActionTarget) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showGroupActions = false
                            viewModel.navigateToGroup(groupActionTarget)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Enter folder")
                    }
                    TextButton(
                        onClick = {
                            showGroupActions = false
                            renameGroupNewName = groupActionTarget.substringAfterLast("/")
                            showRenameGroup = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Rename")
                    }
                    TextButton(
                        onClick = {
                            showGroupActions = false
                            showDeleteGroup = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGroupActions = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Rename Group dialog ─────────────────────────────────────────────────
    if (showRenameGroup) {
        AlertDialog(
            onDismissRequest = { showRenameGroup = false },
            title = { Text("Rename Group") },
            text = {
                Column {
                    Text(
                        "Rename \"$groupActionTarget\" to:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameGroupNewName,
                        onValueChange = { renameGroupNewName = it },
                        label = { Text("New name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameGroupNewName.trim()
                    if (newName.isNotEmpty() && newName != groupActionTarget) {
                        // Build new full path: keep parent path, change leaf name
                        val parentPath = groupActionTarget.substringBeforeLast("/", "")
                        val newFullPath = if (parentPath.isEmpty()) newName else "$parentPath/$newName"
                        viewModel.renameGroup(groupActionTarget, newFullPath)
                    }
                    showRenameGroup = false
                }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameGroup = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Delete Group confirmation ───────────────────────────────────────────
    if (showDeleteGroup) {
        AlertDialog(
            onDismissRequest = { showDeleteGroup = false },
            title = { Text("Delete Group") },
            text = {
                Text(
                    "Delete \"$groupActionTarget\"?\n\n" +
                        "Sessions in this group will be moved to \"Ungrouped\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteGroup(groupActionTarget)
                    showDeleteGroup = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteGroup = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Context menu dialog (long-press single session) ─────────────────
    if (showContextMenu && contextMenuSession != null) {
        val s = contextMenuSession!!
        AlertDialog(
            onDismissRequest = { showContextMenu = false },
            title = { Text(s.name, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showContextMenu = false
                            // Populate edit dialog fields
                            editSession = s
                            editName = s.name
                            editHostname = s.hostname
                            editPort = s.port.toString()
                            editUsername = s.username
                            editAuthType = s.authType
                            editPassword = s.plainPassword ?: ""
                            editKeyPath = s.identityFile ?: ""
                            editKeyContent = s.keyContent ?: ""
                            editGroupName = s.groupName
                            showEditDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Edit")
                    }
                    TextButton(
                        onClick = {
                            showContextMenu = false
                            viewModel.deleteSession(s)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = {
                            showContextMenu = false
                            viewModel.enterSelectionMode()
                            viewModel.toggleSessionSelection(s.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Select…")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showContextMenu = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Delete confirmation dialog ─────────────────────────────────────────
    if (showDeleteConfirm && editSession != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Session") },
            text = { Text("Delete \"${editSession!!.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    editSession?.let { viewModel.deleteSession(it) }
                    showDeleteConfirm = false
                    showEditDialog = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Clipboard import dialog ────────────────────────────────────────────
    if (showClipImport) {
        AlertDialog(
            onDismissRequest = { showClipImport = false },
            title = { Text("Import from Base64") },
            text = {
                Column {
                    Text(
                        "Paste a base64-encoded config string " +
                            "(with or without SSHCONF: prefix).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = clipText,
                        onValueChange = { clipText = it },
                        label = { Text("Base64 string") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = clipboard?.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                clipText = clip.getItemAt(0).coerceToText(context).toString()
                            }
                        },
                    ) {
                        Text("📋 ", style = MaterialTheme.typography.bodySmall)
                        Text("Paste from clipboard")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (clipText.isNotBlank()) {
                            viewModel.importFromBase64(clipText.trim())
                        }
                        showClipImport = false
                    },
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClipImport = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Batch Move dialog ─────────────────────────────────────────────────
    if (showBatchMoveDialog) {
        AlertDialog(
            onDismissRequest = { showBatchMoveDialog = false },
            title = { Text("Move Selected to Group") },
            text = {
                Column {
                    Text(
                        "Select target group:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(280.dp)) {
                        item {
                            TextButton(
                                onClick = {
                                    batchMoveTarget = ""
                                    showBatchMoveDialog = false
                                    viewModel.batchMove("")
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("(no group — ungrouped)")
                            }
                        }
                        items(uiState.groups) { group ->
                            TextButton(
                                onClick = {
                                    batchMoveTarget = group
                                    showBatchMoveDialog = false
                                    viewModel.batchMove(group)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(group)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBatchMoveDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ── Main UI ───────────────────────────────────────────────────────────
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Groups",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp),
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("All Sessions") },
                    selected = uiState.selectedGroup.isEmpty(),
                    onClick = {
                        viewModel.selectGroup("")
                        scope.launch { drawerState.close() }
                    },
                )
                uiState.groups.forEach { group ->
                    NavigationDrawerItem(
                        label = { Text(group) },
                        selected = uiState.selectedGroup == group,
                        onClick = {
                            viewModel.selectGroup(group)
                            scope.launch { drawerState.close() }
                        },
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                if (uiState.selectionMode) {
                    val totalSelected = uiState.selectedSessionIds.size + uiState.selectedGroupPaths.size
                    TopAppBar(
                        title = { Text("$totalSelected selected") },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.exitSelectionMode() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Cancel selection",
                                )
                            }
                        },
                        actions = {
                            TextButton(onClick = { viewModel.selectAllFiltered() }) {
                                Text("All")
                            }
                            TextButton(onClick = { viewModel.deselectAll() }) {
                                Text("None")
                            }
                            TextButton(
                                onClick = {
                                    viewModel.batchDelete()
                                },
                                enabled = totalSelected > 0,
                            ) {
                                Text(
                                    "Delete",
                                    color = if (totalSelected > 0)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { showBatchMoveDialog = true },
                                enabled = totalSelected > 0,
                            ) {
                                Text("Move")
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(uiState.selectedGroup.ifEmpty { "All Sessions" })
                        },
                        navigationIcon = {
                            if (uiState.selectedGroup.isEmpty()) {
                                IconButton(onClick = {
                                    scope.launch { drawerState.open() }
                                }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Groups")
                                }
                            } else {
                                IconButton(onClick = { viewModel.navigateUp() }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { showNewGroupDialog = true }) {
                                Text("📁+", style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(onClick = {
                                qrLauncher.launch(ScanOptions().apply {
                                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    setPrompt("Scan SSH config QR code")
                                    setBeepEnabled(false)
                                    setOrientationLocked(false)
                                })
                            }) {
                                Text("QR", style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(onClick = { showClipImport = true }) {
                                Text("⎀", style = MaterialTheme.typography.titleMedium)
                            }
                            IconButton(onClick = onSettingsClick) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                if (!uiState.selectionMode) {
                    FloatingActionButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Session")
                    }
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::onSearchQuery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search connections…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )

                // Collapse / Expand all toggle
                if (displayItems.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            allExpanded = !allExpanded
                            expandedPaths.clear()
                        }) {
                            Text(if (allExpanded) "Collapse All" else "Expand All")
                        }
                    }
                }

                if (displayItems.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "No connections.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Tap + to add a session.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn {
                        items(displayItems, key = { item ->
                            if (item.isFolder) "fldr_${item.path}" else "sess_${item.session?.id ?: 0}"
                        }) { item ->
                            if (item.isFolder) {
                                val isExpanded = expandedPaths[item.path] ?: allExpanded
                                val isSelected = item.path in uiState.selectedGroupPaths
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = (16 + item.depth * 20).dp,
                                            end = 16.dp,
                                            top = 8.dp,
                                            bottom = 8.dp,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (uiState.selectionMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { viewModel.toggleGroupSelection(item.path) },
                                            modifier = Modifier.padding(end = 4.dp),
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isExpanded)
                                            Icons.Default.KeyboardArrowDown
                                        else
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .clickable { expandedPaths[item.path] = !isExpanded },
                                    )
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.combinedClickable(
                                            onClick = {
                                                if (uiState.selectionMode) {
                                                    viewModel.toggleGroupSelection(item.path)
                                                } else {
                                                    viewModel.navigateToGroup(item.path)
                                                }
                                            },
                                            onLongClick = {
                                                if (!uiState.selectionMode) {
                                                    viewModel.enterSelectionMode()
                                                    viewModel.toggleGroupSelection(item.path)
                                                }
                                            },
                                        ),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "(${item.count})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                HorizontalDivider()
                            } else {
                                item.session?.let { session ->
                                    val isSelected = session.id in uiState.selectedSessionIds
                                    Row(
                                        modifier = Modifier
                                            .padding(
                                                start = (16 + item.depth * 20).dp,
                                                end = 16.dp,
                                                top = 2.dp,
                                                bottom = 2.dp,
                                            ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (uiState.selectionMode) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = {
                                                    viewModel.toggleSessionSelection(session.id)
                                                },
                                                modifier = Modifier.padding(end = 4.dp),
                                            )
                                        }
                                        ConnectionCard(
                                            session = session,
                                            isConnected = session.id in uiState.activeConnections,
                                            onClick = {
                                                if (uiState.selectionMode) {
                                                    viewModel.toggleSessionSelection(session.id)
                                                } else {
                                                    onSessionClick(session)
                                                }
                                            },
                                            onLongClick = {
                                                if (!uiState.selectionMode) {
                                                    contextMenuSession = session
                                                    showContextMenu = true
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
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
