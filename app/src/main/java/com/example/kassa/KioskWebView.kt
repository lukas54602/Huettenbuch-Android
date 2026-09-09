package com.example.kassa

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * WebView der Kassenoberflaeche.
 *
 * HTML-Eingabefelder duerfen fokussiert werden, Android bekommt fuer diese
 * WebView aber keine InputConnection. Dadurch wird die Bildschirmtastatur
 * in der Kasse nicht geoeffnet. Native EditTexts (Setup/Admin) bleiben
 * unveraendert und koennen die Android-Tastatur weiterhin verwenden.
 */
class KioskWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    override fun onCheckIsTextEditor(): Boolean = false

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = null
}
