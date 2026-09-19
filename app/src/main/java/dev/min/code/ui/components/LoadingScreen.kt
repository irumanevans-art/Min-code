package dev.min.code.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.min.code.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.min.code.ui.theme.JetbrainsMono

/**
 * 整页加载：首页的字标（光在海里走），状态和进度在下面干净的阅读区里。
 *
 * @param status 一句话说明在等什么
 * @param detail 机器给的细节（版本号、正在下载的文件……）；等宽
 * @param progress 0..1 或 null（不确定）
 */
@Composable
fun LoadingScreen(
    status: String = stringResource(R.string.loading_preparing),
    modifier: Modifier = Modifier,
    detail: String? = null,
    progress: Float? = null,
) {
    HeroCanvas(modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(status, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            InkLineProgress(progress = progress, modifier = Modifier.width(168.dp), height = 4.dp)
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetbrainsMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/**
 * 首页的画布：纸上一个巨大的 Min（[SeaHero]），光在字里的海面上走；内容（[content]）在它下面。
 * 竖屏：字标落在上半屏，内容在下；横屏（或任何宽大于高的窗口）：字标在左半、内容在右半，两者永不相撞。
 * 字号同时受宽和高约束，矮屏上不会把字标压到内容上。
 */
@Composable
fun HeroCanvas(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") seed: Int = 1,
    content: @Composable BoxScope.() -> Unit,
) {
    val config = LocalConfiguration.current
    val w = config.screenWidthDp
    val h = config.screenHeightDp
    val landscape = w > h
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    SeaHero(widthDp = w / 2f, heightDp = h.toFloat())
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        } else {
            SeaHero(
                Modifier.align(Alignment.TopCenter).padding(top = (h * 0.06f).dp),
                widthDp = w.toFloat(),
                heightDp = h.toFloat(),
            )
            content()
        }
    }
}

/** 兼容旧调用的逐字显现：现在直接显示 */
@Composable
fun InscriptionText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    delayPerChar: Long = 110,
    startDelay: Long = 140,
) {
    Text(text, style = style, color = color, modifier = modifier)
}
