package com.tobevpn.app.presentation.onboarding

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tobevpn.app.R
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.theme.AppAlertDialog

@Composable
internal fun DisplayScaleHintDialog(
    onDismiss: () -> Unit,
    onConfigure: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val laterLabel = stringResource(R.string.later)
    val configureLabel = stringResource(R.string.display_scale_hint_action)
    var sharedButtonFontSize by remember(configureLabel) { mutableStateOf(14.sp) }
    val actionColors = if (isDark) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.buttonColors(
            containerColor = Color(0xFF3F3F3F),
            contentColor = Color.White,
        )
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.AspectRatio,
                contentDescription = null,
                modifier = Modifier.size(38.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.display_scale_hint_title),
                modifier = Modifier.fillMaxWidth(),
                style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.display_scale_hint_description),
                modifier = Modifier.fillMaxWidth(),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = laterLabel,
                        style = fixedLayoutTextStyle(
                            MaterialTheme.typography.labelLarge.copy(
                                fontSize = sharedButtonFontSize,
                            ),
                        ),
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                Button(
                    onClick = onConfigure,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    colors = actionColors,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = configureLabel,
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 10.sp,
                            maxFontSize = 14.sp,
                        ),
                        maxLines = 1,
                        softWrap = false,
                        onTextLayout = { result ->
                            val resolvedSize = result.layoutInput.style.fontSize
                            if (resolvedSize.isSp && resolvedSize != sharedButtonFontSize) {
                                sharedButtonFontSize = resolvedSize
                            }
                        },
                    )
                }
            }
        },
    )
}
