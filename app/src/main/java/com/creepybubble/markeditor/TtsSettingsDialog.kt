package com.creepybubble.markeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Deixa o usuário escolher o motor de TTS (Google, Samsung…) e a voz.
 * As escolhas são aplicadas na hora e ficam salvas pelo TtsManager.
 */
@Composable
fun TtsSettingsDialog(tts: TtsManager, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = gruvboxSurface,
        titleContentColor = gruvboxOrange,
        textContentColor = gruvboxText,
        title = { Text(stringResource(R.string.tts_settings_title)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close), color = gruvboxOrange) }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                SectionLabel(stringResource(R.string.tts_speed))
                Slider(
                    value = tts.speechRate,
                    onValueChange = { tts.updateSpeechRate(it) },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = gruvboxOrange,
                        activeTrackColor = gruvboxOrange,
                        inactiveTrackColor = gruvboxBg
                    )
                )
                Text("${"%.1f".format(tts.speechRate)}x", color = gruvboxGray, fontSize = 12.sp)

                SectionLabel(stringResource(R.string.tts_pitch))
                Slider(
                    value = tts.pitch,
                    onValueChange = { tts.updatePitch(it) },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = gruvboxOrange,
                        activeTrackColor = gruvboxOrange,
                        inactiveTrackColor = gruvboxBg
                    )
                )
                Text("%.1f".format(tts.pitch), color = gruvboxGray, fontSize = 12.sp)

                SectionLabel(stringResource(R.string.tts_sleep_timer))
                val sleepOpts = listOf(
                    0 to stringResource(R.string.tts_off),
                    5 to stringResource(R.string.tts_minutes, 5),
                    10 to stringResource(R.string.tts_minutes, 10),
                    15 to stringResource(R.string.tts_minutes, 15),
                    30 to stringResource(R.string.tts_minutes, 30)
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sleepOpts) { (minutes, label) ->
                        LanguageChip(
                            text = label,
                            selected = tts.sleepTimerMinutes == minutes,
                            onClick = { tts.setSleepTimer(minutes) }
                        )
                    }
                }

                Spacer(modifier = Modifier.padding(3.dp))
                TextButton(onClick = { tts.previewVoice() }) {
                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = gruvboxOrange)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.tts_test_voice), color = gruvboxOrange)
                }

                HorizontalDivider(color = gruvboxBg)
                Spacer(modifier = Modifier.padding(4.dp))

                SectionLabel(stringResource(R.string.tts_engine))
                if (tts.engineOptions.isEmpty()) {
                    Text(stringResource(R.string.tts_loading), color = gruvboxGray, fontSize = 13.sp)
                } else {
                    tts.engineOptions.forEach { engine ->
                        SelectableRow(
                            selected = engine.packageName == tts.selectedEngine,
                            title = engine.label,
                            subtitle = engine.packageName,
                            onClick = { tts.selectEngine(engine.packageName) }
                        )
                    }
                }

                Spacer(modifier = Modifier.padding(4.dp))
                HorizontalDivider(color = gruvboxBg)
                Spacer(modifier = Modifier.padding(4.dp))

                SectionLabel(stringResource(R.string.tts_language))
                if (tts.voiceLanguages.isEmpty()) {
                    Text("—", color = gruvboxGray, fontSize = 13.sp)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            LanguageChip(
                                text = stringResource(R.string.tts_all),
                                selected = tts.selectedLanguage == null,
                                onClick = { tts.setLanguageFilter(null) }
                            )
                        }
                        items(tts.voiceLanguages) { lang ->
                            LanguageChip(
                                text = lang.label,
                                selected = tts.selectedLanguage == lang.code,
                                onClick = { tts.setLanguageFilter(lang.code) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.padding(3.dp))

                SectionLabel(stringResource(R.string.tts_voice))
                if (tts.voiceOptions.isEmpty()) {
                    Text(stringResource(R.string.tts_no_voice), color = gruvboxGray, fontSize = 13.sp)
                } else {
                    tts.voiceOptions.forEach { voice ->
                        SelectableRow(
                            selected = voice.name == tts.selectedVoice,
                            title = voice.label,
                            subtitle = voice.subtitle,
                            onClick = { tts.selectVoice(voice.name) }
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = gruvboxOrange,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
private fun LanguageChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) gruvboxBg else gruvboxText,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) gruvboxOrange else gruvboxButton)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun SelectableRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = gruvboxOrange,
                unselectedColor = gruvboxGray
            )
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = title,
                color = if (selected) gruvboxOrange else gruvboxText,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 15.sp
            )
            Text(text = subtitle, color = gruvboxGray, fontSize = 11.sp)
        }
    }
}
