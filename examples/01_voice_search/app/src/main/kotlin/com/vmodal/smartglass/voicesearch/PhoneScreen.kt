package com.vmodal.smartglass.voicesearch

import android.content.Context
import android.graphics.BitmapFactory
import android.text.InputType
import android.view.View
import android.widget.*
import com.vmodal.smartglass.voicesearch.core.*

/** Small phone setup UI; credentials exist only in the current input and SDK session. */
class PhoneScreen(context: Context) : ScrollView(context) {
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
    val key = EditText(context).apply {
        hint = "VModal API key"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    val collection = Spinner(context)
    val stream = EditText(context).apply { hint = "Stream"; setText("default") }
    val authenticate = Button(context).apply { text = "Authenticate" }
    val register = Button(context).apply { text = "Register Meta glasses" }
    val microphone = Button(context).apply { text = "Allow glasses microphone" }
    val connect = Button(context).apply { text = "Connect selected collection and glasses" }
    val listen = Button(context).apply { text = "Listen" }
    val cancel = Button(context).apply { text = "Cancel / dismiss" }
    val retry = Button(context).apply { text = "Retry" }
    val logout = Button(context).apply { text = "Log out" }
    val setupStatus = TextView(context)
    val status = TextView(context).apply { textSize = 20f }
    val transcript = TextView(context)
    private val preview = ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
    private var imageRequest: Long? = null
    private var bitmap: android.graphics.Bitmap? = null
    init {
        addView(column)
        column.addView(TextView(context).apply { text = "VModal Glasses Voice Search"; textSize = 24f })
        for (view in listOf(key, authenticate, collection, stream, register, microphone, connect,
                setupStatus, status, transcript, preview, listen, cancel, retry, logout)) column.addView(view)
    }
    fun showCollections(names: List<String>) {
        collection.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, names)
        connect.isEnabled = names.isNotEmpty()
    }
    fun render(screen: ScreenModel) {
        status.text = screen.status
        transcript.text = screen.transcript.orEmpty()
        listen.isEnabled = Action.Listen in screen.actions
        cancel.isEnabled = Action.Cancel in screen.actions || Action.Dismiss in screen.actions
        retry.isEnabled = Action.Retry in screen.actions
        val image = screen.image
        if (image?.query?.requestId != imageRequest) {
            preview.setImageDrawable(null)
            bitmap?.recycle()
            bitmap = image?.let { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size) }
            preview.setImageBitmap(bitmap)
            imageRequest = image?.query?.requestId
        }
    }
    fun release() { preview.setImageDrawable(null); bitmap?.recycle(); bitmap = null }
}
