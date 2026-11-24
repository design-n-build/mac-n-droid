package app.macndroid.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.macndroid.core.Computer
import app.macndroid.core.Link
import app.macndroid.core.Pause
import org.json.JSONObject

/**
 * Opened by the "Type to send" widget: a small dialog over the home screen with the keyboard up. Send puts the text
 * on the computer's clipboard, ready to paste.
 */
class TypeToComputerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MacNDroidTheme {
                var text by remember { mutableStateOf("") }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                fun send() {
                    val t = text.trim()
                    if (t.isEmpty()) return
                    if (Pause.active) toast("Sharing is paused. Open Mac n Droid to resume.")
                    else if (!Link.isConnected) toast("Not connected to ${Computer.your}")
                    else {
                        Link.post(JSONObject().put("t", "clip").put("text", t).put("explicit", true))
                        app.macndroid.core.Haptics.play(app.macndroid.core.Haptics.Kind.Tick)
                        toast("Copied to ${computerName()}")
                    }
                    finish()
                }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Send to ${computerName()}") },
                    text = {
                        Column {
                            Text("It goes to the clipboard there, ready to paste.")
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                text, { text = it },
                                modifier = Modifier.focusRequester(focus),
                                placeholder = { Text("Type here") },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { send() }),
                                maxLines = 6,
                            )
                        }
                    },
                    confirmButton = { TextButton(onClick = ::send, enabled = text.isNotBlank()) { Text("Send") } },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Cancel") } },
                )
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
