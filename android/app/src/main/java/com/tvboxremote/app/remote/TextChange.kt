package com.tvboxremote.app.remote

/**
 * The change from the text that the box has to the new text of the phone field: how many characters to delete at
 * the end, and what to insert after that. The box adds text at the cursor (Wi-Fi and Bluetooth), so the phone
 * never sends the whole field again.
 */
data class TextChange(val deletes: Int, val insert: String) {
    companion object {
        fun of(old: String, new: String): TextChange {
            // commonPrefixWith does not split a surrogate pair.
            val common = old.commonPrefixWith(new).length
            return TextChange(old.length - common, new.substring(common))
        }
    }
}
