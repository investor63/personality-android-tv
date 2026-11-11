package com.personality.tv

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val videos by lazy { getSharedPreferences("videos", MODE_PRIVATE) }
    private val sections = listOf(R.string.home, R.string.buttons, R.string.actions, R.string.videos,
        R.string.display, R.string.timer, R.string.settings, R.string.diagnostics)
    private val categories by lazy { getSharedPreferences("categories", MODE_PRIVATE) }
    private val standardCategories = listOf(
        R.string.fireplace, R.string.stars, R.string.waterfall, R.string.rain, R.string.forest,
        R.string.ocean, R.string.aquarium, R.string.city, R.string.abstract_video
    )
    private var selectedCategory: String? = null
    private var videoPage = 0
    private val thumbnails = LruCache<String, Bitmap>(32)
    private val thumbnailWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var section = 0
    private lateinit var panel: LinearLayout
    private lateinit var nav: LinearLayout
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selections ->
        for (uri in selections) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
                videos.edit().putString(uri.toString(), name).apply()
            }.onFailure { toast(R.string.video_error) }
        }
        render()
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scanLibrary { ScreensaverRepository.importTree(applicationContext, tree) }
    }

    private fun scanLibrary(scan: () -> Int) {
        label(getString(R.string.importing))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            val result = runCatching(scan)
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    result.onFailure { toast(R.string.video_error) }
                    render()
                }
            }
            executor.shutdown()
        }
    }

    override fun onCreate(state: Bundle?) {
        if (Build.VERSION.SDK_INT < 33) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(prefs.getString("language", "en")))
        } else {
            val manager = getSystemService(android.app.LocaleManager::class.java)
            if (!prefs.getBoolean("language_initialized", false)) {
                prefs.edit().putBoolean("language_initialized", true).apply()
                if (manager.applicationLocales.isEmpty) {
                    manager.applicationLocales = android.os.LocaleList.forLanguageTags(prefs.getString("language", "en"))
                }
            }
        }
        super.onCreate(state)
        section = (state?.getInt("section") ?: intent.getIntExtra("section", 0)).coerceIn(sections.indices)
        selectedCategory = state?.getString("category")
        videoPage = state?.getInt("video_page") ?: 0
        render()
    }

    override fun onResume() {
        super.onResume()
        RemoteAccessibilityService.stateListener = { render() }
        render()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putInt("section", section)
        state.putString("category", selectedCategory)
        state.putInt("video_page", videoPage)
        super.onSaveInstanceState(state)
    }

    override fun onPause() {
        RemoteAccessibilityService.listener = null
        RemoteAccessibilityService.stateListener = null
        super.onPause()
    }

    override fun onDestroy() {
        thumbnailWorker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Android TV back navigation")
    override fun onBackPressed() {
        val service = RemoteAccessibilityService.active
        if (service?.isBlack == true) {
            service.restoreScreen()
            return
        }
        if (section == 0) super.onBackPressed() else {
            section = 0
            render()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun shape(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(10).toFloat()
    }

    private fun button(
        parent: LinearLayout,
        label: String,
        accent: String? = null,
        selected: Boolean = false,
        icon: Int? = null,
        action: () -> Unit
    ): Button = Button(this).apply {
        val isTab = accent != null
        text = label
        textSize = if (icon != null) 19f else 16f
        isAllCaps = false
        gravity = android.view.Gravity.CENTER_VERTICAL
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(dp(if (isTab) 11 else 22), 0, dp(20), 0)
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
        backgroundTintList = null
        background = StateListDrawable().apply {
            val focused = shape(if (isTab) "#073C83" else "#087BFA")
            if (isTab && !selected) focused.setStroke(dp(1), Color.parseColor("#409CFF"))
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), shape(when {
                selected -> "#073C83"
                isTab -> "#000000"
                else -> "#19202C"
            }))
        }
        if (accent != null) {
            val marker = shape(accent).apply { setBounds(0, 0, dp(4), dp(24)) }
            setCompoundDrawables(marker, null, null, null)
            compoundDrawablePadding = dp(15)
        }
        if (icon != null) {
            val start = getDrawable(icon)?.apply { setBounds(0, 0, dp(28), dp(28)) }
            val end = getDrawable(R.drawable.dashboard_chevron)?.apply { setBounds(0, 0, dp(19), dp(19)) }
            setCompoundDrawables(start, null, end, null)
            compoundDrawablePadding = dp(23)
        }
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, dp(if (isTab) 42 else if (icon != null) 58 else 48)).apply {
            topMargin = dp(if (icon != null) 8 else 6)
        })
    }

    private fun row(id: Int, action: () -> Unit) = button(panel, getString(id), action = action)

    private fun label(value: String, heading: Boolean = false) {
        panel.addView(TextView(this).apply {
            text = value
            setTextColor(Color.parseColor(if (heading) "#FFFFFF" else "#C5D0DF"))
            textSize = if (heading) 40f else 17f
            if (heading) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
        })
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setPadding(dp(28), dp(32), dp(34), dp(30))
        }
        nav = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(24), dp(19), 0) }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), 0, 0, 0)
        }
        root.addView(ScrollView(this).apply { addView(nav) }, LinearLayout.LayoutParams(dp(173), -1))
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#202630")) }, LinearLayout.LayoutParams(dp(1), -1))
        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(0, -1, 1f))
        sections.forEachIndexed { index, id ->
            val colors = listOf("#00C9FF", "#2AD375", "#FF9218", "#AE7AFF", "#FFDC48", "#66C1FF", "#FF525A", "#AA70FF")
            val tab = button(nav, getString(id), accent = colors[index], selected = section == index) { section = index; render() }
            if (section == index) tab.requestFocus()
        }
        setContentView(root)
        nav.getChildAt(section)?.requestFocus()
        label(getString(if (section == 0) R.string.app_name else sections[section]), true)
        when (section) {
            0 -> {
                serviceStatus()
                label(getString(R.string.dashboard_saver))
                button(panel, getString(R.string.play_saver), icon = R.drawable.dashboard_play) { ScreensaverPlayer.launch(this) }.requestFocus()
                button(panel, getString(R.string.toggle), icon = R.drawable.dashboard_screen) { ActionRegistry.run(this, "toggle") }
                button(panel, getString(R.string.dashboard_parameters), icon = R.drawable.dashboard_settings) { section = 6; render() }
                button(panel, getString(R.string.dashboard_preview), icon = R.drawable.dashboard_preview) { section = 3; render() }
            }
            1 -> showButtons()
            2 -> {
                ActionRegistry.actions.values.forEach { action ->
                    row(action.title) {
                        AlertDialog.Builder(this).setTitle(action.title).setMessage(action.description)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                }
                row(R.string.default_action) {
                    AlertDialog.Builder(this).setTitle(R.string.default_action)
                        .setMessage(R.string.desc_default_action)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                label(getString(R.string.picture))
                label(getString(R.string.sleep_unavailable))
            }
            3 -> showVideos()
            4 -> {
                label(getString(R.string.picture))
                label(getString(R.string.overlay_info))
                row(R.string.toggle) { ActionRegistry.run(this, "toggle") }
            }
            5 -> showTimer()
            6 -> showSettings()
            7 -> {
                label(getString(R.string.device, Build.MANUFACTURER, Build.MODEL,
                    Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.joinToString()))
                label(DeviceDiagnosticsManager.events(this))
                label(getString(R.string.picture))
                serviceStatus()
            }
        }
    }

    private fun serviceStatus() {
        if (RemoteAccessibilityService.active != null) panel.addView(TextView(this).apply {
            text = getString(R.string.service_ready)
            textSize = 17f
            setTextColor(Color.parseColor("#CCD9ED"))
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.dashboard_access, 0, 0, 0)
            compoundDrawablePadding = dp(9)
            setPadding(dp(8), dp(6), 0, dp(20))
        })
        else row(R.string.enable_service) {
            runCatching {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                val destination = packageManager.resolveActivity(intent, 0)?.activityInfo
                if (destination?.packageName == "com.google.android.tv.frameworkpackagestubs") {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } else startActivity(intent)
            }
                .onFailure { toast(R.string.action_failed) }
        }
    }

    private fun showButtons() {
        label(getString(R.string.mapping_hint))
        if (RemoteAccessibilityService.active == null) serviceStatus()
        else row(R.string.listen) { listen() }
        prefs.all.filterKeys { it.startsWith("name:") }.toSortedMap().forEach { (key, name) ->
            val signature = key.removePrefix("name:")
            button(panel, name.toString()) { editMapping(signature, name.toString()) }
        }
    }

    private fun listen() {
        val dialog = AlertDialog.Builder(this).setMessage(R.string.waiting)
            .setNegativeButton(R.string.cancel, null).create()
        dialog.setOnDismissListener { RemoteAccessibilityService.listener = null }
        dialog.show()
        RemoteAccessibilityService.listener = { event ->
            val details = getString(R.string.detected, KeyEvent.keyCodeToString(event.keyCode),
                event.keyCode, event.scanCode, event.deviceId)
            dialog.dismiss()
            if (event.keyCode in RemoteAccessibilityService.protectedKeys) {
                AlertDialog.Builder(this).setTitle(details).setMessage(R.string.reserved)
                    .setPositiveButton(android.R.string.ok, null).show()
            } else {
                editMapping(RemoteAccessibilityService.signature(event), details)
            }
        }
    }

    private fun editMapping(signature: String, name: String) {
        val suffixes = listOf("", ":double", ":long")
        val gestures = listOf(R.string.single_press, R.string.double_press, R.string.long_press)
        val rows = gestures.mapIndexed { index, gesture ->
            val action = prefs.getString("button:$signature${suffixes[index]}", null)
            val title = action?.let { ActionRegistry.resolve(it)?.title } ?: R.string.default_action
            "${getString(gesture)} · ${getString(title)}"
        }
        AlertDialog.Builder(this).setTitle(name).setItems(rows.toTypedArray()) { _, gesture ->
            val choices = ActionRegistry.actions.entries.toList()
            val labels = listOf(getString(R.string.clear_gesture)) + choices.map { getString(it.value.title) }
            AlertDialog.Builder(this).setTitle(gestures[gesture]).setItems(labels.toTypedArray()) { _, index ->
                val key = "button:$signature${suffixes[gesture]}"
                val edit = prefs.edit()
                if (index == 0) edit.remove(key)
                else edit.putString(key, choices[index - 1].key)
                val keepName = index > 0 || suffixes.any {
                    val other = "button:$signature$it"
                    other != key && prefs.contains(other)
                }
                if (keepName) edit.putString("name:$signature", name) else edit.remove("name:$signature")
                edit.apply()
                render()
            }.setNegativeButton(R.string.cancel, null).show()
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun categoryNames(): Map<String, String> =
        standardCategories.mapIndexed { index, id ->
            val key = "standard:$index"
            key to (categories.getString(key, null) ?: getString(id))
        }.toMap() + categories.all.filterKeys { it.startsWith("custom:") }.mapValues { it.value.toString() }

    private fun selectCategory(title: Int, onSelect: (String?) -> Unit) {
        val choices = categoryNames().entries.toList()
        val names = listOf(getString(R.string.all_videos)) + choices.map { it.value }
        AlertDialog.Builder(this).setTitle(title).setItems(names.toTypedArray()) { _, index ->
            onSelect(choices.getOrNull(index - 1)?.key)
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showVideos() {
        val currentCategory = selectedCategory?.let { categoryNames()[it] }
        button(panel, currentCategory ?: getString(R.string.all_videos)) {
            selectCategory(R.string.category) { selectedCategory = it; videoPage = 0; render() }
        }
        row(R.string.add_category) {
            input(R.string.add_category, "", false) { name ->
                if (name.isNotBlank()) {
                    categories.edit().putString("custom:" + java.util.UUID.randomUUID(), name).apply()
                    render()
                }
            }
        }
        val category = selectedCategory
        if (category != null) row(R.string.rename_category) {
            input(R.string.rename_category, currentCategory.orEmpty(), false) { name ->
                if (name.isNotBlank()) categories.edit().putString(category, name).apply()
                render()
            }
        }
        row(R.string.import_video) {
            runCatching { picker.launch(arrayOf("video/*")) }.onFailure { toast(R.string.action_failed) }
        }
        row(R.string.import_folder) {
            runCatching { folderPicker.launch(null) }.onFailure { toast(R.string.action_failed) }
        }
        row(R.string.refresh_library) { scanLibrary { ScreensaverRepository.refresh(applicationContext) } }
        val library = ScreensaverPlayer.library(this).filter { category == null || categories.getString("video:$it", null) == category }
        if (library.isEmpty()) label(getString(R.string.empty))
        else row(R.string.random_saver) { ScreensaverPlayer.launch(this, library.random()) }
        val pageSize = 24
        val lastPage = ((library.size - 1) / pageSize).coerceAtLeast(0)
        videoPage = videoPage.coerceIn(0, lastPage)
        if (videoPage > 0) row(R.string.previous_page) { videoPage--; render() }
        library.drop(videoPage * pageSize).take(pageSize).forEach { uri ->
            val name = videos.getString(uri, "").orEmpty()
            val card = button(panel, name) {
                AlertDialog.Builder(this).setTitle(name).setItems(
                    arrayOf(getString(R.string.play_saver), getString(R.string.rename), getString(R.string.remove), getString(R.string.category))
                ) { _, index ->
                    when (index) {
                        0 -> ScreensaverPlayer.launch(this, uri)
                        1 -> input(R.string.rename, name, false) { newName ->
                            if (newName.isNotBlank()) videos.edit().putString(uri, newName).apply()
                            render()
                        }
                        2 -> confirm(R.string.remove) {
                            videos.edit().remove(uri).apply()
                            categories.edit().remove("video:$uri").apply()
                            runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            render()
                        }
                        3 -> selectCategory(R.string.category) { id ->
                            val edit = categories.edit()
                            if (id == null) edit.remove("video:$uri") else edit.putString("video:$uri", id)
                            edit.apply()
                            render()
                        }
                    }
                }.show()
            }
            loadThumbnail(uri, card)
        }
        if (videoPage < lastPage) row(R.string.next_page) { videoPage++; render() }
    }

    private fun loadThumbnail(uri: String, card: Button) {
        if (Build.VERSION.SDK_INT < 29) return
        fun apply(bitmap: Bitmap) {
            val drawable = BitmapDrawable(resources, bitmap)
            drawable.setBounds(0, 0, dp(88), dp(50))
            card.compoundDrawablePadding = dp(12)
            card.setCompoundDrawablesRelative(drawable, null, null, null)
        }
        thumbnails.get(uri)?.let {
            apply(it)
            return
        }
        thumbnailWorker.execute {
            val bitmap = runCatching {
                contentResolver.loadThumbnail(Uri.parse(uri), Size(176, 100), null)
            }.getOrNull() ?: return@execute
            thumbnails.put(uri, bitmap)
            runOnUiThread { if (card.isAttachedToWindow) apply(bitmap) }
        }
    }

    private fun showTimer() {
        label(getString(R.string.timer_delay))
        val remaining = prefs.getLong("timer", 0) - System.currentTimeMillis()
        if (remaining > 0) label(getString(R.string.timer_remaining, (remaining + 59_999) / 60_000))
        listOf(15, 30, 45, 60, 90, 120).forEach { minutes ->
            button(panel, getString(R.string.minutes, minutes)) { timerAction(minutes) }
        }
        row(R.string.custom) {
            input(R.string.custom, "", true) {
                val minutes = it.toIntOrNull()
                if (minutes != null && minutes > 0) timerAction(minutes) else toast(R.string.invalid_minutes)
            }
        }
        row(R.string.cancel_timer) { SleepTimer.cancel(this); render() }
    }

    private fun timerAction(minutes: Int) {
        val choices = if (RemoteAccessibilityService.active != null) listOf("stop_saver", "home") else listOf("stop_saver")
        val labels = choices.map { getString(ActionRegistry.actions.getValue(it).title) }
        AlertDialog.Builder(this).setTitle(getString(R.string.minutes, minutes))
            .setItems(labels.toTypedArray()) { _, index ->
                confirm(ActionRegistry.actions.getValue(choices[index]).title) {
                    SleepTimer.start(this, minutes, choices[index])
                    toast(R.string.timer_set)
                    render()
                }
            }.show()
    }

    private fun showSettings() {
        row(R.string.language) {
            AlertDialog.Builder(this).setTitle(R.string.language).setItems(arrayOf("English", "Русский")) { _, index ->
                val tag = if (index == 0) "en" else "ru"
                prefs.edit().putString("language", tag).apply()
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }.show()
        }
        serviceStatus()
        row(R.string.player_controls) {
            val directions = listOf(R.string.left, R.string.right, R.string.up, R.string.down)
            AlertDialog.Builder(this).setTitle(R.string.player_controls)
                .setItems(directions.map { getString(it) }.toTypedArray()) { _, direction ->
                    val options = listOf(R.string.previous_saver, R.string.next_saver,
                        R.string.random_saver, R.string.mute, R.string.none)
                    AlertDialog.Builder(this).setTitle(directions[direction])
                        .setItems(options.map { getString(it) }.toTypedArray()) { _, option ->
                            prefs.edit().putInt("player_direction:$direction", option).apply()
                            toast(R.string.saved)
                        }.show()
                }.show()
        }
        row(R.string.launch_app) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val apps = packageManager.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(packageManager).toString() }
            AlertDialog.Builder(this).setTitle(R.string.launch_app)
                .setItems(apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()) { _, index ->
                    prefs.edit().putString("launch_package", apps[index].activityInfo.packageName).apply()
                    toast(R.string.saved)
                }.show()
        }
        label(getString(R.string.media_access))
        val enabled = prefs.getBoolean("enabled", true)
        row(if (enabled) R.string.disable_mapping else R.string.enable_mapping) {
            prefs.edit().putBoolean("enabled", !enabled).apply()
            render()
        }
        row(R.string.reset) {
            confirm(R.string.reset) {
                val edit = prefs.edit()
                prefs.all.keys.filter { it.startsWith("button:") || it.startsWith("name:") }.forEach(edit::remove)
                edit.apply()
                render()
            }
        }
    }

    private fun confirm(title: Int, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setPositiveButton(R.string.confirm) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun input(title: Int, initial: String, numeric: Boolean, action: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(initial)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this).setTitle(title).setView(field)
            .setPositiveButton(R.string.confirm) { _, _ -> action(field.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null).show()
    }
}
