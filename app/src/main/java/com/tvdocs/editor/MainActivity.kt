package com.tvdocs.editor

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.TextWatcher
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var editor: EditText
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var selectButton: Button

    private val prefs by lazy { getSharedPreferences("state", Context.MODE_PRIVATE) }
    private val docsDir by lazy { File(filesDir, "documents").apply { mkdirs() } }
    private val draftFile by lazy { File(filesDir, "draft.txt") }

    /** Name of the open document (without .txt), or null when it has never been saved. */
    private var currentName: String? = null
    private var modified = false
    private var selectMode = false
    private var textSizeSp = 22f

    // Simple undo history made of text snapshots.
    private val undoStack = ArrayDeque<Pair<String, Int>>()
    private var lastSnapshotTime = 0L
    private var applyingProgrammaticChange = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editor = findViewById(R.id.editor)
        titleView = findViewById(R.id.title)
        statusView = findViewById(R.id.status)
        selectButton = findViewById(R.id.btn_select)

        textSizeSp = prefs.getFloat("textSize", 22f)
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)

        restoreDraft()
        setupEditor()
        setupButtons()

        if (savedInstanceState == null) handleIncomingIntent(intent)
        updateTitle()
        updateStatus()
        findViewById<View>(R.id.btn_new).requestFocus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        saveDraft()
    }

    // ---------------------------------------------------------------- setup

    private fun setupEditor() {
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (applyingProgrammaticChange) return
                val now = SystemClock.uptimeMillis()
                if (now - lastSnapshotTime > 1000 || undoStack.isEmpty()) {
                    undoStack.addLast(s.toString() to editor.selectionStart.coerceAtLeast(0))
                    while (undoStack.size > 100) undoStack.removeFirst()
                }
                lastSnapshotTime = now
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (!applyingProgrammaticChange && !modified) {
                    modified = true
                    updateTitle()
                }
                updateStatus()
            }
        })

        // Selection mode: the arrows extend the selection instead of moving the cursor.
        editor.setOnKeyListener { _, keyCode, event ->
            if (!selectMode) return@setOnKeyListener false
            val down = event.action == KeyEvent.ACTION_DOWN
            val text = editor.text
            val layout = editor.layout
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) Selection.extendLeft(text, layout); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) Selection.extendRight(text, layout); true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (down) Selection.extendUp(text, layout); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) Selection.extendDown(text, layout); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK -> {
                    if (!down) {
                        setSelectMode(false)
                        selectButton.requestFocus()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupButtons() {
        click(R.id.btn_new) { confirmDiscardThen { newDocument() } }
        click(R.id.btn_docs) { showDocuments() }
        click(R.id.btn_save) { save() }
        click(R.id.btn_save_as) { saveAs() }
        click(R.id.btn_select) { setSelectMode(!selectMode) }
        click(R.id.btn_select_all) {
            setSelectMode(false)
            editor.requestFocus()
            editor.selectAll()
        }
        click(R.id.btn_copy) { copy() }
        click(R.id.btn_cut) { cut() }
        click(R.id.btn_paste) { paste() }
        click(R.id.btn_undo) { undo() }
        click(R.id.btn_smaller) { changeTextSize(-2f) }
        click(R.id.btn_bigger) { changeTextSize(2f) }
        click(R.id.btn_import) { importFile() }
        click(R.id.btn_export) { exportFile() }
    }

    private fun click(id: Int, action: () -> Unit) {
        findViewById<View>(id).setOnClickListener { action() }
    }

    // ------------------------------------------------------- clipboard

    private val clipboard: ClipboardManager
        get() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun selectionRange(): IntRange? {
        val a = editor.selectionStart
        val b = editor.selectionEnd
        if (a < 0 || b < 0 || a == b) return null
        return minOf(a, b) until maxOf(a, b)
    }

    private fun copy() {
        setSelectMode(false)
        val range = selectionRange()
        if (range == null) {
            if (editor.text.isEmpty()) { toast(R.string.nothing_selected); return }
            clipboard.setPrimaryClip(ClipData.newPlainText("text", editor.text.toString()))
            toast(R.string.copied_all)
        } else {
            val selected = editor.text.subSequence(range.first, range.last + 1).toString()
            clipboard.setPrimaryClip(ClipData.newPlainText("text", selected))
            toast(R.string.copied)
        }
    }

    private fun cut() {
        setSelectMode(false)
        val range = selectionRange() ?: run { toast(R.string.nothing_selected); return }
        val selected = editor.text.subSequence(range.first, range.last + 1).toString()
        clipboard.setPrimaryClip(ClipData.newPlainText("text", selected))
        editor.text.delete(range.first, range.last + 1)
        toast(R.string.cut_done)
    }

    private fun paste() {
        setSelectMode(false)
        val clip = clipboard.primaryClip
        val pasted = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } else ""
        if (pasted.isEmpty()) { toast(R.string.clipboard_empty); return }

        var start = editor.selectionStart
        var end = editor.selectionEnd
        if (start < 0 || end < 0) { start = editor.text.length; end = start }
        val from = minOf(start, end)
        val to = maxOf(start, end)
        editor.text.replace(from, to, pasted)
        editor.requestFocus()
        editor.setSelection((from + pasted.length).coerceAtMost(editor.text.length))
        toast(R.string.pasted)
    }

    private fun setSelectMode(on: Boolean) {
        if (selectMode == on) return
        selectMode = on
        selectButton.isActivated = on
        if (on) {
            val pos = editor.selectionEnd.takeIf { it >= 0 } ?: editor.text.length
            editor.requestFocus()
            editor.setSelection(pos)
            toast(R.string.select_mode_on)
        } else {
            toast(R.string.select_mode_off)
        }
    }

    private fun undo() {
        setSelectMode(false)
        val snapshot = undoStack.removeLastOrNull() ?: run { toast(R.string.nothing_to_undo); return }
        setEditorText(snapshot.first, snapshot.second)
        modified = true
        updateTitle()
    }

    // ------------------------------------------------------- documents

    private fun fileFor(name: String) = File(docsDir, "$name.txt")

    private fun newDocument() {
        currentName = null
        undoStack.clear()
        setEditorText("", 0)
        modified = false
        updateTitle()
        editor.requestFocus()
    }

    private fun save(after: (() -> Unit)? = null) {
        val name = currentName
        if (name == null) saveAs(after) else writeDocument(name, after)
    }

    private fun saveAs(after: (() -> Unit)? = null) {
        askName(currentName ?: defaultName()) { name ->
            val target = fileFor(name)
            if (target.exists() && name != currentName) {
                AlertDialog.Builder(this)
                    .setMessage(R.string.doc_exists)
                    .setPositiveButton(R.string.action_ok) { _, _ -> writeDocument(name, after) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            } else {
                writeDocument(name, after)
            }
        }
    }

    private fun writeDocument(name: String, after: (() -> Unit)?) {
        try {
            fileFor(name).writeText(editor.text.toString())
            currentName = name
            modified = false
            updateTitle()
            toast(getString(R.string.saved_as, name))
            after?.invoke()
        } catch (e: Exception) {
            toast(getString(R.string.save_failed, e.message ?: ""))
        }
    }

    private fun openDocument(name: String) {
        try {
            val text = fileFor(name).readText()
            currentName = name
            undoStack.clear()
            setEditorText(text, 0)
            modified = false
            updateTitle()
            editor.requestFocus()
        } catch (e: Exception) {
            toast(getString(R.string.open_failed, e.message ?: ""))
        }
    }

    private fun listDocuments(): List<String> =
        (docsDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".txt") }
            .sortedByDescending { it.lastModified() }
            .map { it.name.removeSuffix(".txt") }

    private fun showDocuments() {
        val names = listDocuments()
        if (names.isEmpty()) { toast(R.string.no_documents); return }
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val labels = names.map { "$it   ·   ${dateFormat.format(Date(fileFor(it).lastModified()))}" }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_docs)
            .setItems(labels.toTypedArray()) { _, which -> showDocumentActions(names[which]) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDocumentActions(name: String) {
        val actions = arrayOf(
            getString(R.string.action_open),
            getString(R.string.action_rename),
            getString(R.string.action_delete),
        )
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> confirmDiscardThen { openDocument(name) }
                    1 -> renameDocument(name)
                    2 -> deleteDocument(name)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun renameDocument(name: String) {
        askName(name) { newName ->
            if (newName == name) return@askName
            if (fileFor(newName).exists()) { toast(R.string.doc_exists); return@askName }
            if (fileFor(name).renameTo(fileFor(newName))) {
                if (currentName == name) currentName = newName
                updateTitle()
            }
        }
    }

    private fun deleteDocument(name: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                fileFor(name).delete()
                if (currentName == name) {
                    currentName = null
                    modified = editor.text.isNotEmpty()
                    updateTitle()
                }
                toast(getString(R.string.deleted, name))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun askName(initial: String, onName: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
        }
        val container = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.doc_name_title)
            .setView(container)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val name = sanitize(input.text.toString())
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
                true
            } else false
        }
        dialog.show()
        input.requestFocus()
    }

    private fun sanitize(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().trim('.').take(80)

    private fun defaultName(): String {
        val firstLine = editor.text.toString().lineSequence()
            .map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val fromText = firstLine?.let { sanitize(it.take(40)) }
        if (!fromText.isNullOrEmpty()) return fromText
        return "Document " + SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.getDefault()).format(Date())
    }

    /** Runs [action] right away, or after asking to save when there are unsaved changes. */
    private fun confirmDiscardThen(action: () -> Unit) {
        if (!modified || editor.text.isEmpty()) { action(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(R.string.unsaved_message)
            .setPositiveButton(R.string.action_save) { _, _ -> save(action) }
            .setNeutralButton(R.string.action_discard) { _, _ -> action() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------- import / export (system file picker)

    private fun importFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }
        launchPicker(intent, REQUEST_IMPORT)
    }

    private fun exportFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, (currentName ?: defaultName()) + ".txt")
        }
        launchPicker(intent, REQUEST_EXPORT)
    }

    @Suppress("DEPRECATION")
    private fun launchPicker(intent: Intent, requestCode: Int) {
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_file_picker)
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_IMPORT -> confirmDiscardThen { loadFromUri(uri) }
            REQUEST_EXPORT -> try {
                contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(editor.text.toString().toByteArray(Charsets.UTF_8))
                }
                toast(R.string.exported)
            } catch (e: Exception) {
                toast(getString(R.string.save_failed, e.message ?: ""))
            }
        }
    }

    private fun loadFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                ?.removeSuffix(".txt")?.let { sanitize(it) }
            currentName = null
            undoStack.clear()
            setEditorText(text, 0)
            modified = true
            // Keep a copy in the app's Documents list, named after the imported file.
            if (!name.isNullOrEmpty() && !fileFor(name).exists()) {
                writeDocument(name, null)
            }
            updateTitle()
            toast(R.string.imported)
        } catch (e: Exception) {
            toast(getString(R.string.open_failed, e.message ?: ""))
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_EDIT) return
        val uri = intent.data ?: return
        confirmDiscardThen { loadFromUri(uri) }
    }

    // ------------------------------------------------------- draft & UI state

    private fun saveDraft() {
        try {
            draftFile.writeText(editor.text.toString())
        } catch (_: Exception) {
        }
        prefs.edit()
            .putString("currentName", currentName)
            .putBoolean("modified", modified)
            .putInt("cursor", editor.selectionEnd.coerceAtLeast(0))
            .putFloat("textSize", textSizeSp)
            .apply()
    }

    private fun restoreDraft() {
        val text = try {
            if (draftFile.exists()) draftFile.readText() else ""
        } catch (_: Exception) {
            ""
        }
        currentName = prefs.getString("currentName", null)?.takeIf { fileFor(it).exists() }
        setEditorText(text, prefs.getInt("cursor", 0))
        modified = prefs.getBoolean("modified", false)
    }

    private fun setEditorText(text: String, cursor: Int) {
        applyingProgrammaticChange = true
        editor.setText(text)
        editor.setSelection(cursor.coerceIn(0, editor.text.length))
        applyingProgrammaticChange = false
        updateStatus()
    }

    private fun changeTextSize(delta: Float) {
        textSizeSp = (textSizeSp + delta).coerceIn(12f, 60f)
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        prefs.edit().putFloat("textSize", textSizeSp).apply()
    }

    private fun updateTitle() {
        val name = currentName ?: getString(R.string.untitled)
        titleView.text = if (modified) "$name  ${getString(R.string.modified_mark)}" else name
    }

    private fun updateStatus() {
        val text = editor.text
        val chars = text.length
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        val lines = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1
        statusView.text = getString(R.string.status_format, chars, words, lines)
    }

    private fun toast(resId: Int) = toast(getString(resId))

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------- remote keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            findViewById<View>(R.id.btn_docs).requestFocus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            selectMode -> setSelectMode(false)
            editor.hasFocus() -> findViewById<View>(R.id.btn_save).requestFocus()
            else -> confirmDiscardThen { finish() }
        }
    }

    companion object {
        private const val REQUEST_IMPORT = 1
        private const val REQUEST_EXPORT = 2
    }
}
