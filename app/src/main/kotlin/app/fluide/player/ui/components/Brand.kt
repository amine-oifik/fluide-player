package app.fluide.player.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Fluide Player logo: the mark, followed by the word mark when [withWordmark]. [size] is the
 * height of the mark. (Placeholder drawing: replaced by the brand design.)
 */
@Composable
fun FluideLogo(modifier: Modifier = Modifier, size: Dp = 40.dp, withWordmark: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Glyph.PLAY, Color(0xFF3D7BFF), size = size)
        if (withWordmark) {
            Spacer(Modifier.width(size / 4))
            Text("Fluide Player", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.7f).sp)
        }
    }
}
