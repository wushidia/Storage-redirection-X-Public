package com.storage.redirect.x.debug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.storage.redirect.x.DebugPage
import com.storage.redirect.x.MainActivity

internal fun Context.openDebugPage(pageId: String?): Bundle {
    val page = DebugPage.from(pageId)
        ?: return debugResult(isOk = false, message = "Unknown page: $pageId", command = METHOD_PAGE_OPEN, target = pageId)

    val intent = Intent(this, MainActivity::class.java).apply {
        action = DebugPage.ACTION_OPEN_PAGE
        putExtra(DebugPage.EXTRA_PAGE, page.id)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    startActivity(intent)
    return debugResult(
        isOk = true,
        message = "Opening page: ${page.id}",
        command = METHOD_PAGE_OPEN,
        target = page.id,
    )
}
