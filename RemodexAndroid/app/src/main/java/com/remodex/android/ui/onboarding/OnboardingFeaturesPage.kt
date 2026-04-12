package com.remodex.android.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remodex.android.R

private val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

private data class FeatureItem(
    val icon: ImageVector,
    val iconColor: Color,
    val title: String,
    val description: String,
)

private val features = listOf(
    FeatureItem(
        icon = Icons.Rounded.Speed,
        iconColor = Color(0xFFFBBF24), // Yellow
        title = "Fast mode",
        description = "Lower-latency turns for quick interactions",
    ),
    FeatureItem(
        icon = Icons.AutoMirrored.Rounded.CallSplit,
        iconColor = Color(0xFF4ADE80), // Green
        title = "Git from your phone",
        description = "Commit, push, pull, and switch branches",
    ),
    FeatureItem(
        icon = Icons.Rounded.Lock,
        iconColor = Color(0xFF22D3EE), // Cyan
        title = "End-to-end encrypted",
        description = "The relay never sees your prompts or code",
    ),
    FeatureItem(
        icon = Icons.Rounded.GraphicEq,
        iconColor = Color(0xFFC084FC), // Purple
        title = "Voice mode",
        description = "Talk to Codex with speech-to-text",
    ),
    FeatureItem(
        icon = Icons.Rounded.Bolt,
        iconColor = Color(0xFFFB923C), // Orange
        title = "Subagents, skills and /commands",
        description = "Spawn and monitor parallel agents from your phone",
    ),
)

@Composable
fun OnboardingFeaturesPage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 48.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "What you get",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = Geist,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Everything runs on your Mac.\nYour phone is the remote.",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 14.sp,
            fontFamily = Geist,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        features.forEach { feature ->
            FeatureRow(feature)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FeatureRow(feature: FeatureItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(feature.iconColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = null,
                tint = feature.iconColor,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = feature.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = Geist,
            )
            Text(
                text = feature.description,
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                fontFamily = Geist,
                maxLines = 2,
            )
        }
    }
}
