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
 * 「空间精简」: every installed, non-critical system package of the dual space with a checkbox.
 * Checked = hidden + suspended inside the space (data stays, unchecking restores it). The fork's
 * default blocklist arrives checked; critical packages are not listed at all.
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
                val rows = state.visible
                items(rows, key = { it.pkg }) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.saving) { vm.toggle(row.pkg) }
                            .heightIn(min = 56.dp)
                            .padding(vertical = PrismSpacing.Xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = row.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = row.pkg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (row.inBlocklist) Text(
                            text = stringResource(R.string.lz_trim_default_tag),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = PrismSpacing.Sm),
                        )
                        Checkbox(
                            checked = row.checked,
                            onCheckedChange = null,
                            enabled = !state.saving,
                        )
                    }
                }
            }
        }
    }
}
