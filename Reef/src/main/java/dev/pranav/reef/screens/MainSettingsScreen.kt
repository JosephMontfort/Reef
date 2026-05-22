package dev.pranav.reef.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import dev.pranav.reef.AboutActivity
import dev.pranav.reef.R
import dev.pranav.reef.ui.about.DonateButton
import dev.pranav.reef.util.append
import dev.pranav.reef.util.prefs

@Composable
fun MainSettingsContent(
    contentPadding: PaddingValues = PaddingValues(),
    onNavigate: (SettingsScreenRoute) -> Unit,
    onSoundPicker: () -> Unit
) {
    val context = LocalContext.current
    var enableDND by remember { mutableStateOf(prefs.getBoolean("enable_dnd", false)) }
    var soundEnabled by remember { mutableStateOf(prefs.getBoolean("pomodoro_sound_enabled", true)) }
    var vibrationEnabled by remember { mutableStateOf(prefs.getBoolean("pomodoro_vibration_enabled", true)) }

    val menuItems = listOf(
        SettingsMenuItem(
            icon = Icons.Rounded.Info,
            title = stringResource(R.string.about),
            subtitle = stringResource(R.string.about_subtitle),
            destination = SettingsScreenRoute.Main
        ),
        SettingsMenuItem(
            icon = Icons.Rounded.Notifications,
            title = stringResource(R.string.notifications),
            subtitle = stringResource(R.string.notifications_subtitle),
            destination = SettingsScreenRoute.Notifications
        )
    )

    LazyColumn(
        contentPadding = contentPadding.append(horizontal = 16.dp)
    ) {
        // ── Do Not Disturb ────────────────────────────────────────────────────
        item {
            SettingsCard(index = 0, listSize = 1) {
                ListItem(
                    modifier = Modifier
                        .clickable {
                            enableDND = !enableDND
                            prefs.edit { putBoolean("enable_dnd", enableDND) }
                        }
                        .padding(4.dp),
                    headlineContent = {
                        Text(stringResource(R.string.enable_dnd),
                            style = MaterialTheme.typography.titleMedium)
                    },
                    supportingContent = {
                        Text(stringResource(R.string.dnd_description),
                            style = MaterialTheme.typography.bodySmall)
                    },
                    trailingContent = {
                        Switch(
                            checked = enableDND,
                            onCheckedChange = { enableDND = it; prefs.edit { putBoolean("enable_dnd", it) } }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) }

        // ── Pomodoro Notifications ────────────────────────────────────────────
        item {
            Text(
                text = stringResource(R.string.pomodoro_notifications_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }

        item {
            SettingsCard(index = 0, listSize = 3) {
                ListItem(
                    modifier = Modifier
                        .clickable {
                            soundEnabled = !soundEnabled
                            prefs.edit { putBoolean("pomodoro_sound_enabled", soundEnabled) }
                        }
                        .padding(4.dp),
                    headlineContent = {
                        Text(stringResource(R.string.transition_sound),
                            style = MaterialTheme.typography.titleMedium)
                    },
                    supportingContent = {
                        Text(stringResource(R.string.transition_sound_description),
                            style = MaterialTheme.typography.bodySmall)
                    },
                    trailingContent = {
                        Switch(
                            checked = soundEnabled,
                            onCheckedChange = { soundEnabled = it; prefs.edit { putBoolean("pomodoro_sound_enabled", it) } }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }

        item {
            SettingsCard(index = 1, listSize = 3) {
                ListItem(
                    modifier = Modifier
                        .clickable(enabled = soundEnabled) { onSoundPicker() }
                        .padding(4.dp),
                    leadingContent = {
                        Icon(
                            Icons.Rounded.MusicNote, null,
                            tint = if (soundEnabled) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    },
                    headlineContent = {
                        Text(
                            stringResource(R.string.select_sound),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (soundEnabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    },
                    supportingContent = {
                        Text(
                            stringResource(R.string.select_sound_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (soundEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                            tint = if (soundEnabled) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }

        item {
            SettingsCard(index = 2, listSize = 3) {
                ListItem(
                    modifier = Modifier
                        .clickable {
                            vibrationEnabled = !vibrationEnabled
                            prefs.edit { putBoolean("pomodoro_vibration_enabled", vibrationEnabled) }
                        }
                        .padding(4.dp),
                    headlineContent = {
                        Text(stringResource(R.string.transition_vibration),
                            style = MaterialTheme.typography.titleMedium)
                    },
                    supportingContent = {
                        Text(stringResource(R.string.transition_vibration_description),
                            style = MaterialTheme.typography.bodySmall)
                    },
                    trailingContent = {
                        Switch(
                            checked = vibrationEnabled,
                            onCheckedChange = { vibrationEnabled = it; prefs.edit { putBoolean("pomodoro_vibration_enabled", it) } }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) }

        // ── Navigation items (About, Notifications) ───────────────────────────
        itemsIndexed(items = menuItems, key = { _, item -> item.title }) { index, item ->
            SettingsMenuItemRow(
                item = item,
                index = index,
                listSize = menuItems.size,
                onClick = {
                    when (item.destination) {
                        SettingsScreenRoute.Notifications -> onNavigate(SettingsScreenRoute.Notifications)
                        SettingsScreenRoute.Main -> context.startActivity(
                            Intent(context, AboutActivity::class.java)
                        )
                    }
                }
            )
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            DonateButton()
        }
    }
}


@Composable
fun SettingsCard(
    index: Int,
    listSize: Int,
    content: @Composable () -> Unit
) {
    val shape = when {
        listSize == 1 -> RoundedCornerShape(24.dp)
        index == 0 -> RoundedCornerShape(
            topStart = 24.dp, topEnd = 24.dp,
            bottomStart = 6.dp, bottomEnd = 6.dp
        )
        index == listSize - 1 -> RoundedCornerShape(
            topStart = 6.dp, topEnd = 6.dp,
            bottomStart = 24.dp, bottomEnd = 24.dp
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
            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = shape
        ) {
            content()
        }
    }
}
