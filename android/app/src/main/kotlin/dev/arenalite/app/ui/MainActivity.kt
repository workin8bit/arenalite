package dev.arenalite.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.arenalite.app.ui.theme.ArealiteTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArealiteTheme {
                val model: MainViewModel = viewModel()
                val workspace by model.workspace.collectAsState()
                val chat by model.chat.collectAsState()
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        WorkspaceDrawer(
                            state = workspace,
                            onSelect = {
                                model.select(it)
                                scope.launch { drawerState.close() }
                            },
                            onCreate = {
                                model.createWorkspace()
                                scope.launch { drawerState.close() }
                            },
                            onDelete = model::deleteWorkspace,
                        )
                    },
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        workspace.sessions.firstOrNull { it.id == workspace.activeId }?.title
                                            ?: "Arealite",
                                    )
                                },
                                colors = TopAppBarDefaults.topAppBarColors(),
                                actions = {
                                    QuotaBadge(bytes = workspace.usage?.totalBytes ?: 0)
                                },
                            )
                        },
                        floatingActionButton = {
                            ExtendedFloatingActionButton(
                                onClick = { scope.launch { drawerState.open() } },
                                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                                text = { Text("Workspace") },
                            )
                        },
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            ChatScreen(
                                chat = chat,
                                hasWorkspace = workspace.activeId != null,
                                onSend = model::send,
                                onCancel = model::cancel,
                                onOpenFiles = { scope.launch { drawerState.open() } },
                                onSync = model::syncPush,
                                onSnapshot = model::snapshot,
                                files = workspace.files,
                                currentPath = workspace.currentPath,
                                openFile = workspace.openFile,
                                onOpenFolder = model::openFolder,
                                onOpenFile = model::openFile,
                                onSaveFile = model::saveFile,
                                usage = workspace.usage,
                                deviceFreeBytes = workspace.deviceFreeBytes,
                                onFork = model::forkWorkspace,
                            )
                        }
                    }
                }
            }
        }
    }
}
