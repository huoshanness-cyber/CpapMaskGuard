package app.cpapmaskguard.settings

import android.content.Context
import app.cpapmaskguard.core.CloudUploadPolicy
import app.cpapmaskguard.core.FrameVote

/** 脱落串、上传开关、当日恢复次数。commit 写入，进程被系统杀掉后还在。 */
class CloudUploadStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun state(nowMs: Long = System.currentTimeMillis()): CloudUploadPolicy.State {
        val raw = read()
        val rolled = CloudUploadPolicy.roll(raw, nowMs)
        if (rolled != raw) write(rolled)
        return rolled
    }

    fun noteAimInvalid(nowMs: Long = System.currentTimeMillis()): CloudUploadPolicy.State {
        val next = CloudUploadPolicy.onAimInvalid(state(nowMs), nowMs)
        write(next)
        return next
    }

    fun noteCloud(vote: FrameVote, nowMs: Long = System.currentTimeMillis()): CloudUploadPolicy.State {
        val next = CloudUploadPolicy.onCloudVote(state(nowMs), vote, nowMs)
        write(next)
        return next
    }

    fun resume(nowMs: Long = System.currentTimeMillis()): CloudUploadPolicy.Resume {
        val outcome = CloudUploadPolicy.resume(state(nowMs), nowMs)
        write(outcome.state)
        return outcome
    }

    private fun read(): CloudUploadPolicy.State {
        return CloudUploadPolicy.State(
            consecutiveMaskOff = prefs.getInt(KEY_STREAK, 0).coerceAtLeast(0),
            uploadEnabled = prefs.getBoolean(KEY_ENABLED, true),
            resumesUsed = prefs.getInt(KEY_RESUMES, 0).coerceAtLeast(0),
            resumeDay = prefs.getString(KEY_DAY, "").orEmpty(),
            lastCloudWasMaskOff = prefs.getBoolean(KEY_LAST_OFF, false),
        )
    }

    private fun write(state: CloudUploadPolicy.State) {
        prefs.edit()
            .putInt(KEY_STREAK, state.consecutiveMaskOff)
            .putBoolean(KEY_ENABLED, state.uploadEnabled)
            .putInt(KEY_RESUMES, state.resumesUsed)
            .putString(KEY_DAY, state.resumeDay)
            .putBoolean(KEY_LAST_OFF, state.lastCloudWasMaskOff)
            .commit()
    }

    private companion object {
        const val PREFS = "cpap_mask_guard"
        const val KEY_STREAK = "cloud_mask_off_streak"
        const val KEY_ENABLED = "cloud_upload_enabled"
        const val KEY_RESUMES = "cloud_resume_used"
        const val KEY_DAY = "cloud_resume_day"
        const val KEY_LAST_OFF = "cloud_last_mask_off"
    }
}
