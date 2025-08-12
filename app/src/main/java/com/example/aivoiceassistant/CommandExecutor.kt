package com.example.aivoiceassistant

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

// CHANGELOG:
// 1. Refactored methods to accept structured JSONObject arguments from Gemini function calls.
// 2. Removed the top-level `execute` method as Gemini now decides which function to call.
// 3. Methods now take specific parameters (e.g., appName, query) instead of parsing a raw command string.

@Singleton
class CommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "CommandExecutor"

    fun searchWeb(args: JSONObject): String {
        val query = args.optString("query", "")
        if (query.isBlank()) return "What would you like to search for?"
        Log.d(TAG, "Attempting to search for: '$query'")
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Searching for $query..."
    }

    fun setAlarm(args: JSONObject): String {
        val command = args.optString("command", "").lowercase()
        if (command.isBlank()) return "I need a time to set the alarm."

        // Handle relative time ("in 5 minutes")
        if (" in " in command) {
            try {
                val parts = command.split(" in ")[1].trim().split(" ")
                val amount = parts[0].toInt()
                val unit = parts[1]
                val calendar = Calendar.getInstance()
                when {
                    unit.startsWith("minute") -> calendar.add(Calendar.MINUTE, amount)
                    unit.startsWith("hour") -> calendar.add(Calendar.HOUR, amount)
                    else -> throw IllegalArgumentException("Unknown time unit")
                }
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val minute = calendar.get(Calendar.MINUTE)
                launchAlarmIntent(hour, minute, "AI Assistant Alarm")
                return "OK, alarm set for $amount ${unit} from now."
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse relative alarm from command: '$command'", e)
                return "Sorry, I couldn't set the relative alarm."
            }
        }

        // Handle specific time ("at 5 PM")
        try {
            val regex = """(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""".toRegex(RegexOption.IGNORE_CASE)
            val match = regex.find(command)
            if (match != null) {
                var hour = match.groupValues[1].toInt()
                val minute = if (match.groupValues[2].isNotEmpty()) match.groupValues[2].toInt() else 0
                val amPm = match.groupValues[3]
                if (amPm.equals("pm", ignoreCase = true) && hour < 12) hour += 12
                if (amPm.equals("am", ignoreCase = true) && hour == 12) hour = 0
                launchAlarmIntent(hour, minute, "AI Assistant Alarm")
                return "Alarm set for ${match.value}."
            } else {
                throw IllegalArgumentException("No time pattern found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse specific alarm time from command: '$command'", e)
            return "Sorry, I couldn't understand the time."
        }
    }

    private fun launchAlarmIntent(hour: Int, minute: Int, message: String) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openApp(args: JSONObject): String {
        val appName = args.optString("appName", "")
        if (appName.isBlank()) return "Which app would you like to open?"
        Log.d(TAG, "Attempting to open app: '$appName'")

        val packageManager: PackageManager = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val allApps = packageManager.queryIntentActivities(mainIntent, 0)

        var appToLaunch = allApps.firstOrNull { it.loadLabel(packageManager).toString().equals(appName, ignoreCase = true) }
        if (appToLaunch == null) {
            appToLaunch = allApps.firstOrNull { it.loadLabel(packageManager).toString().contains(appName, ignoreCase = true) }
        }

        return if (appToLaunch != null) {
            val appLabel = appToLaunch.loadLabel(packageManager)
            val packageName = appToLaunch.activityInfo.packageName
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                "Opening $appLabel."
            } else {
                "Sorry, there was an error opening that app."
            }
        } else {
            "Sorry, I can't find the app $appName."
        }
    }


    fun callContact(args: JSONObject): String {
        val contactName = args.optString("contactName", "")
        if (contactName.isBlank()) return "Who would you like to call?"
        Log.d(TAG, "Attempting to find contact: '$contactName'")
        val phoneNumber = findPhoneNumber(contactName)

        return if (phoneNumber != null) {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                "Calling ${contactName.replaceFirstChar { it.uppercase() }}..."
            } catch (e: SecurityException) {
                Log.e(TAG, "CALL_PHONE permission not granted.", e)
                "I need phone permission to make calls. Please grant it in settings."
            }
        } else {
            Log.w(TAG, "Could not find contact '$contactName'")
            "Sorry, I couldn't find ${contactName.replaceFirstChar { it.uppercase() }} in your contacts."
        }
    }

    private fun findPhoneNumber(name: String): String? {
        val contentResolver = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")
        val cursor: Cursor? = contentResolver.query(uri, projection, selection, selectionArgs, null)

        var phoneNumber: String? = null
        cursor?.use {
            if (it.moveToFirst()) {
                phoneNumber = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
            }
        }
        return phoneNumber
    }
}