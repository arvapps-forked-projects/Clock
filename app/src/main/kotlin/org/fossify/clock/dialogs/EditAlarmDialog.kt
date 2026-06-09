package org.fossify.clock.dialogs

import android.app.TimePickerDialog
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.RingtoneManager
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import org.fossify.clock.R
import org.fossify.clock.activities.SimpleActivity
import org.fossify.clock.databinding.DialogEditAlarmBinding
import org.fossify.clock.databinding.ItemFlocareerTimeBinding
import org.fossify.clock.extensions.alarmController
import org.fossify.clock.extensions.checkAlarmsWithDeletedSoundUri
import org.fossify.clock.extensions.colorCompoundDrawable
import org.fossify.clock.extensions.config
import org.fossify.clock.extensions.dbHelper
import org.fossify.clock.extensions.getFormattedTime
import org.fossify.clock.extensions.handleFullScreenNotificationsPermission
import org.fossify.clock.extensions.rotateWeekdays
import org.fossify.clock.helpers.PICK_AUDIO_FILE_INTENT_ID
import org.fossify.clock.helpers.getCurrentDayMinutes
import org.fossify.clock.helpers.updateNonRecurringAlarmDay
import org.fossify.clock.models.Alarm
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.SelectAlarmSoundDialog
import org.fossify.commons.extensions.addBit
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getDefaultAlarmSound
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getTimePickerDialogTheme
import org.fossify.commons.extensions.isDynamicTheme
import org.fossify.commons.extensions.removeBit
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.AlarmSound
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class EditAlarmDialog(
    val activity: SimpleActivity,
    val alarm: Alarm,
    val onDismiss: () -> Unit = {},
    val callback: (alarmId: Int) -> Unit,
) {
    private val binding = DialogEditAlarmBinding.inflate(activity.layoutInflater)
    private val textColor = activity.getProperTextColor()
    private var selectedDate: Date? = null
    private val dayViews = ArrayList<TextView>()
    private val enabledFlocareerTimes = HashSet<Int>()

    init {
        restoreLastAlarm()
        updateAlarmTime()

        if (alarm.dateString.isNotEmpty()) {
            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                selectedDate = sdf.parse(alarm.dateString)
            } catch (_: Exception) {}
        }

        binding.apply {
            editAlarmTypeRegular.setTextColor(textColor)
            editAlarmTypeFlocareer.setTextColor(textColor)
            editAlarmTypeDisabled.setTextColor(textColor)

            if (alarm.id == 0) {
                editAlarmTypeGroup.visibility = View.VISIBLE
                editAlarmTypeRegular.isChecked = true
                editAlarmRegularHolder.visibility = View.VISIBLE
                editAlarmFlocareerHolder.visibility = View.GONE
            } else {
                editAlarmTypeGroup.visibility = View.GONE
                editAlarmRegularHolder.visibility = View.VISIBLE
                editAlarmFlocareerHolder.visibility = View.GONE
            }

            editAlarmTypeGroup.setOnCheckedChangeListener { _, checkedId ->
                if (checkedId == R.id.edit_alarm_type_flocareer) {
                    editAlarmRegularHolder.visibility = View.GONE
                    editAlarmFlocareerHolder.visibility = View.VISIBLE
                } else {
                    editAlarmRegularHolder.visibility = View.VISIBLE
                    editAlarmFlocareerHolder.visibility = View.GONE
                }
            }

            editAlarmDateIcon.setColorFilter(textColor)
            editAlarmDateClear.setColorFilter(textColor)
            editAlarmDateTitle.setTextColor(textColor)
            updateDateView()

            val dayLetters =
                activity.resources.getStringArray(org.fossify.commons.R.array.week_day_letters)
                    .toList() as ArrayList<String>
            val dayIndexes = activity.rotateWeekdays(arrayListOf(0, 1, 2, 3, 4, 5, 6))

            editAlarmDateHolder.setOnClickListener {
                val builder = MaterialDatePicker.Builder.datePicker()
                builder.setTitleText("Select Alarm Date")
                if (selectedDate != null) {
                    builder.setSelection(selectedDate!!.time)
                }
                val datePicker = builder.build()
                datePicker.addOnPositiveButtonClickListener { selection ->
                    selectedDate = Date(selection)
                    alarm.days = 0
                    alarm.dateString = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selectedDate!!)
                    updateDateView()
                    updateDaysViews(dayIndexes)
                    checkDaylessAlarm()
                }
                datePicker.show(activity.supportFragmentManager, "date_picker")
            }

            editAlarmDateClear.setOnClickListener {
                selectedDate = null
                alarm.dateString = ""
                updateDateView()
                checkDaylessAlarm()
            }

            setupFlocareerTimes()

            editAlarmTime.setOnClickListener {
                if (activity.isDynamicTheme()) {
                    val timeFormat = if (activity.config.use24HourFormat) {
                        TimeFormat.CLOCK_24H
                    } else {
                        TimeFormat.CLOCK_12H
                    }

                    val timePicker = MaterialTimePicker.Builder()
                        .setTimeFormat(timeFormat)
                        .setHour(alarm.timeInMinutes / 60)
                        .setMinute(alarm.timeInMinutes % 60)
                        .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                        .build()

                    timePicker.addOnPositiveButtonClickListener {
                        timePicked(timePicker.hour, timePicker.minute)
                    }

                    timePicker.show(activity.supportFragmentManager, "")
                } else {
                    TimePickerDialog(
                        root.context,
                        root.context.getTimePickerDialogTheme(),
                        timeSetListener,
                        alarm.timeInMinutes / 60,
                        alarm.timeInMinutes % 60,
                        activity.config.use24HourFormat
                    ).show()
                }
            }

            editAlarmSound.colorCompoundDrawable(textColor)
            editAlarmSound.text = alarm.soundTitle
            editAlarmSound.setOnClickListener {
                SelectAlarmSoundDialog(
                    activity = activity,
                    currentUri = alarm.soundUri,
                    audioStream = AudioManager.STREAM_ALARM,
                    pickAudioIntentId = PICK_AUDIO_FILE_INTENT_ID,
                    type = RingtoneManager.TYPE_ALARM,
                    loopAudio = true,
                    onAlarmPicked = {
                        if (it != null) {
                            updateSelectedAlarmSound(it)
                        }
                    },
                    onAlarmSoundDeleted = {
                        if (alarm.soundUri == it.uri) {
                            val defaultAlarm =
                                root.context.getDefaultAlarmSound(RingtoneManager.TYPE_ALARM)
                            updateSelectedAlarmSound(defaultAlarm)
                        }
                        activity.checkAlarmsWithDeletedSoundUri(it.uri)
                    })
            }

            editAlarmVibrateIcon.setColorFilter(textColor)
            editAlarmVibrate.isChecked = alarm.vibrate
            editAlarmVibrateHolder.setOnClickListener {
                editAlarmVibrate.toggle()
                alarm.vibrate = editAlarmVibrate.isChecked
            }

            editAlarmLabelImage.applyColorFilter(textColor)
            editAlarm.setText(alarm.label)

            dayIndexes.forEach { it ->
                val bitmask = 1 shl it
                val day = activity.layoutInflater.inflate(
                    R.layout.alarm_day, editAlarmDaysHolder, false
                ) as TextView
                day.text = dayLetters[it]

                val isDayChecked = alarm.isRecurring() && alarm.days and bitmask != 0
                day.background = getProperDayDrawable(isDayChecked)

                day.setTextColor(if (isDayChecked) root.context.getProperBackgroundColor() else textColor)
                day.setOnClickListener {
                    if (alarm.dateString.isNotEmpty()) {
                        alarm.dateString = ""
                        selectedDate = null
                        updateDateView()
                    }
                    if (!alarm.isRecurring()) {
                        alarm.days = 0
                    }

                    val selectDay = alarm.days and bitmask == 0
                    if (selectDay) {
                        alarm.days = alarm.days.addBit(bitmask)
                    } else {
                        alarm.days = alarm.days.removeBit(bitmask)
                    }
                    updateDaysViews(dayIndexes)
                    checkDaylessAlarm()
                }

                dayViews.add(day)
                editAlarmDaysHolder.addView(day)
            }
        }

        activity.getAlertDialogBuilder()
            .setOnDismissListener { onDismiss() }
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (!activity.config.wasAlarmWarningShown) {
                            ConfirmationDialog(
                                activity = activity,
                                messageId = org.fossify.commons.R.string.alarm_warning,
                                positive = org.fossify.commons.R.string.ok,
                                negative = 0
                            ) {
                                activity.config.wasAlarmWarningShown = true
                                it.performClick()
                            }

                            return@setOnClickListener
                        }

                        if (binding.editAlarmTypeGroup.checkedRadioButtonId == R.id.edit_alarm_type_flocareer) {
                            if (enabledFlocareerTimes.isEmpty()) {
                                activity.toast("Please select at least one Flocareer time.")
                                return@setOnClickListener
                            }

                            val customLabel = binding.editAlarm.value
                            val soundTitle = alarm.soundTitle
                            val soundUri = alarm.soundUri
                            val vibrate = alarm.vibrate
                            val dateStr = alarm.dateString

                            activity.handleFullScreenNotificationsPermission { granted ->
                                if (granted) {
                                    ensureBackgroundThread {
                                        enabledFlocareerTimes.forEach { targetMinutes ->
                                            val offsets = listOf(-30, -15, -5, -1)
                                            offsets.forEach { offset ->
                                                val precursorMinutes = (targetMinutes + offset + 1440) % 1440
                                                val targetTimeStr = formatTimeInMinutes(targetMinutes, activity.config.use24HourFormat)
                                                val alarmLabel = if (customLabel.isNotEmpty()) {
                                                    "$customLabel - Flocareer $targetTimeStr (${offset}m)"
                                                } else {
                                                    "Flocareer $targetTimeStr (${offset}m)"
                                                }

                                                val newAlarm = Alarm(
                                                    id = 0,
                                                    timeInMinutes = precursorMinutes,
                                                    days = alarm.days,
                                                    isEnabled = true,
                                                    vibrate = vibrate,
                                                    soundTitle = soundTitle,
                                                    soundUri = soundUri,
                                                    label = alarmLabel,
                                                    oneShot = false,
                                                    dateString = dateStr
                                                )

                                                if (newAlarm.dateString.isEmpty()) {
                                                    updateNonRecurringAlarmDay(newAlarm)
                                                }

                                                val alarmId = activity.dbHelper.insertAlarm(newAlarm)
                                                if (alarmId != -1) {
                                                    newAlarm.id = alarmId
                                                    activity.alarmController.scheduleNextOccurrence(newAlarm, false)
                                                }
                                            }
                                        }

                                        val firstTarget = enabledFlocareerTimes.first()
                                        val dummyAlarm = Alarm(
                                            id = 0,
                                            timeInMinutes = (firstTarget - 30 + 1440) % 1440,
                                            days = alarm.days,
                                            isEnabled = true,
                                            vibrate = vibrate,
                                            soundTitle = soundTitle,
                                            soundUri = soundUri,
                                            label = customLabel,
                                            oneShot = false,
                                            dateString = dateStr
                                        )
                                        activity.config.alarmLastConfig = dummyAlarm

                                        activity.runOnUiThread {
                                            callback(0)
                                            alertDialog.dismiss()
                                        }
                                    }
                                }
                            }
                        } else {
                            updateNonRecurringAlarmDay(alarm)

                            alarm.label = binding.editAlarm.value
                            alarm.isEnabled = true
                            alarm.oneShot = false

                            var alarmId = alarm.id
                            activity.handleFullScreenNotificationsPermission { granted ->
                                if (granted) {
                                    if (alarm.id == 0) {
                                        alarmId = activity.dbHelper.insertAlarm(alarm)
                                        if (alarmId == -1) {
                                            activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                                        }
                                    } else {
                                        if (!activity.dbHelper.updateAlarm(alarm)) {
                                            activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                                        }
                                    }

                                    activity.config.alarmLastConfig = alarm
                                    callback(alarmId)
                                    alertDialog.dismiss()
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun setupFlocareerTimes() {
        val flocareerTimes = (360..1080 step 15).toList()
        val layoutInflater = activity.layoutInflater
        var currentRow: LinearLayout? = null
        val itemsPerRow = 3

        flocareerTimes.forEachIndexed { index, timeInMinutes ->
            if (index % itemsPerRow == 0) {
                currentRow = LinearLayout(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 4, 0, 4)
                    }
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = itemsPerRow.toFloat()
                }
                binding.editAlarmFlocareerTimesContainer.addView(currentRow)
            }

            val itemBinding = ItemFlocareerTimeBinding.inflate(layoutInflater, currentRow, false)
            val view = itemBinding.root

            val timeText = formatTimeInMinutes(timeInMinutes, activity.config.use24HourFormat)
            itemBinding.flocareerTimeText.text = timeText
            itemBinding.flocareerTimeText.setTextColor(textColor)

            itemBinding.flocareerTimeSwitch.setColors(textColor, activity.getProperPrimaryColor(), activity.getProperBackgroundColor())
            itemBinding.flocareerTimeSwitch.isChecked = enabledFlocareerTimes.contains(timeInMinutes)

            view.setOnClickListener {
                val isChecked = !itemBinding.flocareerTimeSwitch.isChecked
                itemBinding.flocareerTimeSwitch.isChecked = isChecked
                if (isChecked) {
                    enabledFlocareerTimes.add(timeInMinutes)
                } else {
                    enabledFlocareerTimes.remove(timeInMinutes)
                }
            }

            val backgroundDrawable = getProperDayDrawable(false)
            view.background = backgroundDrawable

            currentRow?.addView(view)
        }

        val remaining = flocareerTimes.size % itemsPerRow
        if (remaining > 0 && currentRow != null) {
            for (i in 0 until (itemsPerRow - remaining)) {
                val spacer = View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                }
                currentRow.addView(spacer)
            }
        }
    }

    private fun formatTimeInMinutes(minutes: Int, use24Hour: Boolean): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (use24Hour) {
            String.format("%02d:%02d", h, m)
        } else {
            val ampm = if (h >= 12) "PM" else "AM"
            val h12 = if (h == 0 || h == 12) 12 else h % 12
            String.format("%d:%02d %s", h12, m, ampm)
        }
    }

    private fun updateDateView() {
        if (selectedDate != null) {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())
            binding.editAlarmDateValue.text = sdf.format(selectedDate!!)
            binding.editAlarmDateClear.visibility = View.VISIBLE
        } else {
            binding.editAlarmDateValue.text = "Not scheduled"
            binding.editAlarmDateClear.visibility = View.GONE
        }
    }

    private fun updateDaysViews(dayIndexes: List<Int>) {
        dayIndexes.forEachIndexed { index, it ->
            val bitmask = 1 shl it
            val isDayChecked = alarm.isRecurring() && alarm.days and bitmask != 0
            if (index < dayViews.size) {
                val dayView = dayViews[index]
                dayView.background = getProperDayDrawable(isDayChecked)
                dayView.setTextColor(if (isDayChecked) activity.getProperBackgroundColor() else textColor)
            }
        }
    }

    private fun restoreLastAlarm() {
        if (alarm.id == 0) {
            activity.config.alarmLastConfig?.let { lastConfig ->
                alarm.label = lastConfig.label
                alarm.days = lastConfig.days
                alarm.soundTitle = lastConfig.soundTitle
                alarm.soundUri = lastConfig.soundUri
                alarm.timeInMinutes = lastConfig.timeInMinutes
                alarm.vibrate = lastConfig.vibrate
                alarm.dateString = lastConfig.dateString
            }
        }
    }

    private val timeSetListener = TimePickerDialog.OnTimeSetListener { _, hourOfDay, minute ->
        timePicked(hourOfDay, minute)
    }

    private fun timePicked(hours: Int, minutes: Int) {
        alarm.timeInMinutes = hours * 60 + minutes
        updateAlarmTime()
    }

    private fun updateAlarmTime() {
        binding.editAlarmTime.text = activity.getFormattedTime(
            passedSeconds = alarm.timeInMinutes * 60,
            showSeconds = false,
            makeAmPmSmaller = true
        )
        checkDaylessAlarm()
    }

    private fun checkDaylessAlarm() {
        if (alarm.dateString.isNotEmpty()) {
            binding.editAlarmDaylessLabel.beVisibleIf(false)
            return
        }
        if (!alarm.isRecurring()) {
            val textId = if (alarm.timeInMinutes > getCurrentDayMinutes()) {
                org.fossify.commons.R.string.today
            } else {
                org.fossify.commons.R.string.tomorrow
            }

            binding.editAlarmDaylessLabel.text = "(${activity.getString(textId)})"
        }
        binding.editAlarmDaylessLabel.beVisibleIf(!alarm.isRecurring())
    }

    private fun getProperDayDrawable(selected: Boolean): Drawable {
        val drawableId = if (selected) {
            R.drawable.circle_background_filled
        } else {
            R.drawable.circle_background_stroke
        }

        val drawable = activity.resources.getDrawable(drawableId)
        drawable.applyColorFilter(textColor)
        return drawable
    }

    fun updateSelectedAlarmSound(alarmSound: AlarmSound) {
        alarm.soundTitle = alarmSound.title
        alarm.soundUri = alarmSound.uri
        binding.editAlarmSound.text = alarmSound.title
    }
}
