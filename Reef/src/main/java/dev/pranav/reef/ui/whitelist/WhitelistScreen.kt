package dev.pranav.reef.ui.whitelist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserHandle
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import dev.pranav.reef.timer.TimerStateManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import dev.pranav.reef.R

@Stable
data class WhitelistedApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
    val isWhitelisted: Boolean,
    val user: UserHandle,
    val isSystemApp: Boolean = false
)

sealed interface AllowedAppsState {
    data object Loading: AllowedAppsState
    data class Success(val apps: List<WhitelistedApp>): AllowedAppsState
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WhitelistScreen(
    onBackPress: () -> Unit,
    uiState: AllowedAppsState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggle: (WhitelistedApp) -> Unit,
    hideSystemApps: Boolean = true,
    onToggleHideSystemApps: () -> Unit = {},
) {
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Disable edits during an active focus session
    val timerState by TimerStateManager.state.collectAsState()
    val sessionActive = timerState.isRunning || timerState.isPaused
    val effectiveOnToggle: (WhitelistedApp) -> Unit = { if (!sessionActive) onToggle(it) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                MediumTopAppBar(
                    title = {
                        Text(stringResource(R.string.whitelist_apps_title))
                    },
                    navigationIcon = {
                        IconButton(onClick = { onBackPress() }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    scrollBehavior = scrollBehavior
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = {
                        Text(
                            "Search apps...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear search"
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(28.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    )
                )

                // ── Filter chip row ────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = hideSystemApps,
                        onClick = onToggleHideSystemApps,
                        label = { Text(stringResource(R.string.hide_system_apps)) },
                        leadingIcon = if (hideSystemApps) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Session-active lock banner
            if (sessionActive) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Whitelist cannot be edited during an active focus session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            val listModifier = if (sessionActive)
                Modifier.fillMaxSize().padding(top = 58.dp) // offset for banner
            else
                Modifier.fillMaxSize()

            when (uiState) {
                is AllowedAppsState.Loading -> {
                    ContainedLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is AllowedAppsState.Success -> {
                    if (uiState.apps.isEmpty()) {
                        Text(
                            text = "No apps found",
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        LazyColumn(
                            modifier = listModifier,
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            itemsIndexed(
                                items = uiState.apps,
                                key = { _, app -> app.packageName + app.user.hashCode() }
                            ) { index, app ->
                                WhitelistItem(
                                    app = app,
                                    index = index,
                                    listSize = uiState.apps.size,
                                    enabled = !sessionActive,
                                    onToggle = { effectiveOnToggle(app) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WhitelistItem(
    app: WhitelistedApp,
    index: Int,
    listSize: Int,
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    val shape = when {
        listSize == 1 -> RoundedCornerShape(24.dp)
        index == 0 -> RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomStart = 6.dp,
            bottomEnd = 6.dp
        )

        index == listSize - 1 -> RoundedCornerShape(
            topStart = 6.dp,
            topEnd = 6.dp,
            bottomStart = 24.dp,
            bottomEnd = 24.dp
        )

        else -> RoundedCornerShape(6.dp)
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + scaleIn(
            initialScale = 0.95f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        ),
        exit = fadeOut() + shrinkVertically()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (enabled)
                    MaterialTheme.colorScheme.surfaceContainer
                else
                    MaterialTheme.colorScheme.surfaceContainerLow
            ),
            shape = shape
        ) {
            ListItem(
                modifier = Modifier
                    .clickable(enabled = enabled, onClick = onToggle)
                    .padding(4.dp),
                headlineContent = {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                },
                leadingContent = {
                    Image(
                        painter = BitmapPainter(app.icon),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                },
                trailingContent = {
                    Checkbox(
                        checked = app.isWhitelisted,
                        onCheckedChange = { if (enabled) onToggle() },
                        enabled = enabled
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }
}

fun Drawable.toBitmap(): Bitmap {
    if (this is BitmapDrawable) {
        return bitmap
    }
    val bitmap = createBitmap(
        intrinsicWidth.takeIf { it > 0 } ?: 1,
        intrinsicHeight.takeIf { it > 0 } ?: 1,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}
