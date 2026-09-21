package com.kirjasto.kirjastobotti.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * On-screen Finnish virtual keyboard with QWERTY layout, Finnish vowels (Å, Ä, Ö),
 * numbers, and library navigation symbols (-, .).
 *
 * Avoids triggering or relying on the Android system IME soft keyboard.
 */
@Composable
fun FinnishVirtualKeyboard(
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isUppercase by remember { mutableStateOf(true) }

    val row1Numbers = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    val row2Letters = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P", "Å")
    val row3Letters = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L", "Ö", "Ä")
    val row4Letters = listOf("Z", "X", "C", "V", "B", "N", "M")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E252D), RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Row 1: Numbers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            row1Numbers.forEach { digit ->
                KeyButton(
                    text = digit,
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyPress(digit) },
                    containerColor = Color(0xFF2B3642),
                    contentColor = Color.White
                )
            }
        }

        // Row 2: QWERTY...Å
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            row2Letters.forEach { letter ->
                val display = if (isUppercase) letter else letter.lowercase()
                KeyButton(
                    text = display,
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyPress(display) },
                    containerColor = Color(0xFF334155),
                    contentColor = Color.White
                )
            }
        }

        // Row 3: ASDF...Ö, Ä
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            row3Letters.forEach { letter ->
                val display = if (isUppercase) letter else letter.lowercase()
                KeyButton(
                    text = display,
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyPress(display) },
                    containerColor = Color(0xFF334155),
                    contentColor = Color.White
                )
            }
        }

        // Row 4: Shift, Z-M, Dot, Dash, Space, Backspace
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shift / Caps toggle
            KeyButton(
                text = if (isUppercase) "⇪ ISO" else "⇩ pieni",
                modifier = Modifier.weight(1.3f),
                onClick = { isUppercase = !isUppercase },
                containerColor = if (isUppercase) Color(0xFF0284C7) else Color(0xFF475569),
                contentColor = Color.White,
                fontSize = 13.sp
            )

            // Letters Z-M
            row4Letters.forEach { letter ->
                val display = if (isUppercase) letter else letter.lowercase()
                KeyButton(
                    text = display,
                    modifier = Modifier.weight(1f),
                    onClick = { onKeyPress(display) },
                    containerColor = Color(0xFF334155),
                    contentColor = Color.White
                )
            }

            // Dot '.'
            KeyButton(
                text = ".",
                modifier = Modifier.weight(0.9f),
                onClick = { onKeyPress(".") },
                containerColor = Color(0xFF2B3642),
                contentColor = Color(0xFF38BDF8),
                fontWeight = FontWeight.Bold
            )

            // Dash '-'
            KeyButton(
                text = "-",
                modifier = Modifier.weight(0.9f),
                onClick = { onKeyPress("-") },
                containerColor = Color(0xFF2B3642),
                contentColor = Color(0xFF38BDF8),
                fontWeight = FontWeight.Bold
            )

            // Space
            KeyButton(
                text = "VÄLI",
                modifier = Modifier.weight(1.8f),
                onClick = { onKeyPress(" ") },
                containerColor = Color(0xFF2B3642),
                contentColor = Color(0xFFCBD5E1),
                fontSize = 13.sp
            )

            // Backspace ⌫
            KeyButton(
                text = "⌫",
                modifier = Modifier.weight(1.3f),
                onClick = onBackspace,
                containerColor = Color(0xFF7F1D1D),
                contentColor = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
private fun KeyButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    containerColor: Color = Color(0xFF334155),
    contentColor: Color = Color.White,
    fontSize: androidx.compose.ui.unit.TextUnit = 17.sp,
    fontWeight: FontWeight = FontWeight.SemiBold
) {
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = Color.White.copy(alpha = 0.3f)),
                onClick = onClick
            ),
        color = containerColor,
        shape = RoundedCornerShape(6.dp),
        shadowElevation = 2.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = contentColor,
                fontSize = fontSize,
                fontWeight = fontWeight,
                maxLines = 1
            )
        }
    }
}
