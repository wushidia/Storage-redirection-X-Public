// 改编自 Kyant0/AndroidLiquidGlass（Apache 2.0），与 compose-miuix-ui 示例保持一致。

package com.storage.redirect.x.ui.component.liquid

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

// 液态玻璃的饱和度增强效果
fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}
