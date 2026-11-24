package app.selah.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.material3.AssistChip as M3AssistChip
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Checkbox as M3Checkbox
import androidx.compose.material3.FilterChip as M3FilterChip
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.Slider as M3Slider
import androidx.compose.material3.Switch as M3Switch
import androidx.compose.material3.TextButton as M3TextButton

/*
 * Versiones de los controles de Material 3 que muestran el cursor de "mano" al pasar el mouse.
 * Todo el escritorio usa estas (mismo nombre, en este paquete) en vez de las originales,
 * así ningún botón queda sin la mano.
 */

/** Cursor de mano solo si el control está activo. */
fun Modifier.hand(enabled: Boolean = true): Modifier = if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

/** clickable + cursor de mano. */
fun Modifier.handClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    hand(enabled).clickable(enabled = enabled, onClick = onClick)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = M3Button(onClick, modifier.hand(enabled), enabled, colors = colors, border = border, contentPadding = contentPadding, content = content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = M3OutlinedButton(onClick, modifier.hand(enabled), enabled, colors = colors, border = border, contentPadding = contentPadding, content = content)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) = M3TextButton(onClick, modifier.hand(enabled), enabled, colors = colors, contentPadding = contentPadding, content = content)

@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
) = M3FilterChip(selected, onClick, label, modifier.hand(enabled), enabled, leadingIcon = leadingIcon)

@Composable
fun AssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = M3AssistChip(onClick, label, modifier.hand(enabled), enabled)

@Composable
fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) =
    M3Switch(checked, onCheckedChange, modifier.hand(enabled), enabled = enabled)

@Composable
fun Checkbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) =
    M3Checkbox(checked, onCheckedChange, modifier.hand(enabled), enabled)

@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) = M3Slider(value, onValueChange, modifier.hand(enabled), enabled, valueRange, colors = SliderDefaults.colors())

/**
 * Cuando aparece o cambia [key] (y no es null), mueve el desplazamiento para que esto se vea: el avance de un proceso o
 * el mensaje de "Listo" no quedan escondidos arriba o abajo. Si ya se ve, no mueve nada.
 */
@Composable
fun Modifier.showWhen(key: Any?): Modifier {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(key) { if (key != null) requester.bringIntoView() }
    return bringIntoViewRequester(requester)
}
