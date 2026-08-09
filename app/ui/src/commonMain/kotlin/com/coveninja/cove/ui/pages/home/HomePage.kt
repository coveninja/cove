package com.coveninja.cove.ui.pages.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.tmdbImageSize
import com.coveninja.cove.ui.pages.common.MediaShelf
import com.ongshok.iconify.ui.IconifyIcon

@Composable
fun HomePage(
    media: List<Media>,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onOpenMedia: (Media) -> Unit,
    onExplore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val featured = media.firstOrNull()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            featured?.let { item ->
                FeaturedMedia(
                    media = item,
                    onOpen = { onOpenMedia(item) },
                    onExplore = onExplore,
                    modifier = Modifier
                        .fillMaxWidth(),
                )
            }
        }

        item {
            MediaShelf(
                title = "Trending now",
                subtitle = "Popular picks ready to watch",
                media = media,
                mediaCard = mediaCard,
            )
        }

        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun FeaturedMedia(
    media: Media,
    onOpen: () -> Unit,
    onExplore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(560.dp)
            .clip(RoundedCornerShape(bottomEnd = 24.dp, bottomStart = 24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen),
    ) {
        AsyncImage(
            model = tmdbImageSize(media.backdropUrl, "w1280"),
            contentDescription = "${media.title} backdrop",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.92f),
                            Color.Black.copy(alpha = 0.56f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.48f),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .padding(
                    start = 48.dp,
                    end = 32.dp,
                    top = 74.dp,
                    bottom = 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (media.logoUrl != null) {
                AsyncImage(
                    model = tmdbImageSize(
                        media.logoUrl,
                        "w500",
                    ),
                    contentDescription = "${media.title} logo",
                    modifier = Modifier
                        .width(256.dp)
                        .align(Alignment.CenterHorizontally),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                )
            } else {
                Text(
                    text = media.title ?: media.name ?: "Untitled",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

            Text(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                textAlign = TextAlign.Center,
                text = listOfNotNull(media.released, media.type?.label).joinToString(" • "),
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelMedium,
            )

            media.overview?.let { overview ->
                Text(
                    text = overview,
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            OutlinedButton(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconifyIcon(
                    icon = "lucide:play",
                    modifier = Modifier.size(18.dp),
                    tint = Color.White,
                )
                Spacer(Modifier.size(7.dp))
                Text(
                    "Details"
                )
            }
        }
    }
}