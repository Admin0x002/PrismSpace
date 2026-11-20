package com.yzddmr6.prismspace.prism.compose.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.compose.theme.PrismSpacing
import com.yzddmr6.prismspace.prism.compose.vm.SpaceTrimViewModel

/**
 * 「空间应用」: what runs inside the space right now (privileged only) and, per package, whether it
 * may be listed and whether it may run. The two switches are the platform's own hidden/suspended
 * flags, so the tag always shows which of the four quadrants a row is in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceTrimScreen(onBack: () -> Unit) {
    val vm: SpaceTrimViewModel = viewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.load() }
    BackHandler(enabled = !state.saving) { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lz_trim_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.saving) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.lz_trim_close))
                    }
                },
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PrismSpacing.Lg, vertical = PrismSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(PrismSpacing.Xs),
            ) {
                state.feedback?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.feedbackIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
                Button(
                    onClick = { vm.save() },
                    enabled = !state.saving && !state.loading && state.error == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.lz_trim_save))
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = PrismSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(PrismSpacing.Sm),
        ) {
            Text(
                text = stringResource(R.string.lz_trim_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = PrismSpacing.Sm),
            )
            ProcessesCard(state = state.processes, onRefresh = vm::refreshProcesses)
            OutlinedTextField(
                value = state.filter,
                onValueChange = vm::setFilter,
                singleLine = true,
                label = { Text(stringResource(R.string.lz_trim_search)) },
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (state.loading) Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PrismSpacing.Sm),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.lz_trim_loading), style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.visible, key = { it.pkg }) { row ->
                    QuadrantRow(
                        label = row.label,
                        pkg = row.pkg,
                        hidden = row.hidden,
                        suspended = row.suspended,
                        inBlocklist = row.inBlocklist,
                        enabled = !state.saving,
                        onToggleHidden = { vm.toggle(row.pkg, hidden = !row.hidden) },
                        onToggleSuspended = { vm.toggle(row.pkg, suspended = !row.suspended) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessesCard(state: SpaceTrimViewModel.Processes, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PrismSpacing.Xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.lz_trim_processes_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.lz_trim_processes_refresh)) }
        }
        when (state) {
            SpaceTrimViewModel.Processes.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PrismSpacing.Sm),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.lz_trim_loading), style = MaterialTheme.typography.bodySmall)
            }
            // No approximation is offered on purpose: an app cannot enumerate another user's
            // processes, so the honest answer is "switch to a privileged transport".
            SpaceTrimViewModel.Processes.NoPrivilege -> Text(
                text = stringResource(R.string.lz_trim_processes_no_privilege),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is SpaceTrimViewModel.Processes.Failed -> Text(
                text = stringResource(R.string.lz_trim_processes_failed, state.detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            is SpaceTrimViewModel.Processes.Loaded -> {
                if (state.rows.isEmpty()) {
                    Text(
                        text = stringResource(R.string.lz_trim_processes_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.lz_trim_processes_count, state.rows.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.rows.take(PROCESS_ROWS) .forEach { process ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text(
                                text = process.name,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(R.string.lz_trim_processes_rss, process.rssKb / 1024),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(84.dp),
                            )
                        }
                    }
                    if (state.rows.size > PROCESS_ROWS) Text(
                        text = stringResource(R.string.lz_trim_processes_more, state.rows.size - PROCESS_ROWS),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuadrantRow(
    label: String,
    pkg: String,
    hidden: Boolean,
    suspended: Boolean,
    inBlocklist: Boolean,
    enabled: Boolean,
    onToggleHidden: () -> Unit,
    onToggleSuspended: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = PrismSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (inBlocklist) Text(
                    text = stringResource(R.string.lz_trim_default_tag),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = PrismSpacing.Sm),
                )
            }
            Text(
                text = pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    when {
                        hidden && suspended -> R.string.lz_trim_tag_hidden_suspended
                        hidden -> R.string.lz_trim_tag_hidden
                        suspended -> R.string.lz_trim_tag_suspended
                        else -> R.string.lz_trim_tag_normal
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (hidden || suspended) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 隐藏 / 挂起: two independent platform switches, so two controls.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.lz_trim_hide), style = MaterialTheme.typography.labelSmall)
            Checkbox(checked = hidden, onCheckedChange = { onToggleHidden() }, enabled = enabled)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.lz_trim_suspend), style = MaterialTheme.typography.labelSmall)
            Checkbox(checked = suspended, onCheckedChange = { onToggleSuspended() }, enabled = enabled)
        }
    }
}

private const val PROCESS_ROWS = 8