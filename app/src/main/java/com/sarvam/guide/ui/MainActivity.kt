package com.sarvam.guide.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sarvam.guide.R
import com.sarvam.guide.core.Lang
import com.sarvam.guide.core.MedStore
import com.sarvam.guide.core.Medicine
import com.sarvam.guide.core.ReminderScheduler
import com.sarvam.guide.service.GuideHolder
import com.sarvam.guide.service.GuideService
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var helpButton: Button
    private lateinit var helpHintText: TextView
    private lateinit var hintText: TextView
    private lateinit var langButton: Button
    private lateinit var medButton: Button

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startGuideService()
            else statusText.text = Lang.t("perm_denied")
        }

    private val notifyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startGuideService()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            GuideHolder.pendingScreenRequest = false
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, GuideService::class.java).apply {
                    action = GuideService.ACTION_PROJECTION
                    putExtra(GuideService.EXTRA_SCREEN_CAPTURE_INTENT, result.data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                statusText.text = Lang.t("needs_screen")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.init(this)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        helpButton = findViewById(R.id.helpButton)
        helpHintText = findViewById(R.id.helpHintText)
        hintText = findViewById(R.id.hintText)
        langButton = findViewById(R.id.langButton)
        medButton = findViewById(R.id.medButton)

        applyTexts()

        helpButton.setOnClickListener { startGuideService(GuideService.ACTION_WAKE) }
        toggleButton.setOnClickListener {
            if (GuideHolder.running) {
                stopService(Intent(this, GuideService::class.java))
                statusText.text = Lang.t("idle")
                toggleButton.text = Lang.t("start")
            } else {
                ensureStarted()
            }
        }
        langButton.setOnClickListener { showLanguageDialog() }
        medButton.setOnClickListener { showMedicineDialog() }

        ReminderScheduler.rescheduleAll(this)
        ensureStarted()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (GuideHolder.pendingScreenRequest) {
            GuideHolder.pendingScreenRequest = false
            askProjection()
        }
    }

    override fun onResume() {
        super.onResume()
        if (GuideHolder.pendingScreenRequest) {
            GuideHolder.pendingScreenRequest = false
            askProjection()
        }
    }

    private fun applyTexts() {
        helpButton.text = Lang.t("help")
        helpHintText.text = Lang.t("hint")
        hintText.text = Lang.t("hint")
        langButton.text = Lang.t("lang_button")
        medButton.text = Lang.t("med_button")
        if (GuideHolder.running) {
            statusText.text = Lang.t("listening")
            toggleButton.text = Lang.t("stop")
        } else {
            statusText.text = Lang.t("idle")
            toggleButton.text = Lang.t("start")
        }
    }

    private fun showLanguageDialog() {
        val codes = arrayOf(Lang.TE, Lang.HI, Lang.EN)
        val names = arrayOf("తెలుగు", "हिन्दी", "English")
        AlertDialog.Builder(this)
            .setTitle(Lang.t("lang_title"))
            .setItems(names) { _, which ->
                Lang.set(this, codes[which])
                recreate()
            }
            .show()
    }

    private fun showMedicineDialog() {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }
        scroll.addView(box)

        val meds = MedStore.load(this)
        if (meds.isEmpty()) {
            box.addView(TextView(this).apply { text = Lang.t("med_empty") })
        } else {
            meds.forEach { med -> box.addView(medicineRow(med)) }
        }

        val nameInput = EditText(this).apply {
            hint = Lang.t("med_name_hint")
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 20f
        }
        val timePicker = TimePicker(this).apply {
            setIs24HourView(true)
            setPadding(0, 24, 0, 0)
        }
        box.addView(nameInput)
        box.addView(timePicker)

        val dialog = AlertDialog.Builder(this)
            .setTitle(Lang.t("med_title"))
            .setView(scroll)
            .setPositiveButton(Lang.t("med_add"), null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) return@setOnClickListener
                val hour = if (Build.VERSION.SDK_INT >= 23) timePicker.hour else timePicker.currentHour
                val minute = if (Build.VERSION.SDK_INT >= 23) timePicker.minute else timePicker.currentMinute
                val med = MedStore.add(this, name, hour, minute)
                ReminderScheduler.schedule(this, med)
                Toast.makeText(this, Lang.t("med_saved"), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun medicineRow(med: Medicine): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(0, 8, 0, 8)
        }
        row.addView(TextView(this).apply {
            text = String.format(Locale.US, "%02d:%02d  %s", med.hour, med.minute, med.name)
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Button(this).apply {
            text = Lang.t("med_delete")
            textSize = 14f
            setOnClickListener {
                MedStore.remove(this@MainActivity, med.id)
                ReminderScheduler.cancel(this@MainActivity, med)
                row.visibility = android.view.View.GONE
            }
        })
        return row
    }

    /** Start listening right away; screen consent is only asked when guidance needs it. */
    private fun ensureStarted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        requestNotificationAndStart()
    }

    private fun askProjection() {
        if (GuideHolder.projection != null) {
            startGuideService()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun requestNotificationAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startGuideService()
    }

    private fun startGuideService(action: String? = null) {
        val intent = Intent(this, GuideService::class.java).apply {
            if (action != null) this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusText.text = Lang.t("listening")
        toggleButton.text = Lang.t("stop")
    }
}
