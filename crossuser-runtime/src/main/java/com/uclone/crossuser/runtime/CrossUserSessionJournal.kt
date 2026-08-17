package com.uclone.crossuser.runtime

import android.content.Context
import android.util.AtomicFile
import com.uclone.crossuser.api.CrossUserProtocol
import com.uclone.crossuser.api.CrossUserSessionState
import org.json.JSONObject
import java.io.File

internal class CrossUserSessionJournal(context: Context) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))

    @Synchronized
    fun read(): CrossUserSessionState? = runCatching {
        if (!file.baseFile.isFile) return null
        CrossUserJournalCodec.decode(file.readFully().toString(Charsets.UTF_8))
    }.getOrNull()

    @Synchronized
    fun write(state: CrossUserSessionState) {
        val output = file.startWrite()
        try {
            output.write(CrossUserJournalCodec.encode(state).toByteArray(Charsets.UTF_8))
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    companion object {
        const val FILE_NAME = "crossuser_session_v1.json"
    }
}

internal object CrossUserJournalCodec {
    fun encode(state: CrossUserSessionState): String = JSONObject()
        .put("schemaVersion", state.schemaVersion)
        .put("sessionId", state.sessionId)
        .put("state", state.state)
        .put("displayId", state.displayId)
        .put("taskId", state.taskId)
        .put("mainUserId", state.mainUserId)
        .put("targetUserId", state.targetUserId)
        .put("packageName", state.packageName)
        .put("userWasRunning", state.userWasRunning)
        .put("userStartedBySession", state.userStartedBySession)
        .put("errorCode", state.errorCode ?: JSONObject.NULL)
        .put("message", state.message)
        .toString()

    fun decode(value: String): CrossUserSessionState {
        val json = JSONObject(value)
        require(json.getInt("schemaVersion") == CrossUserProtocol.SCHEMA_VERSION)
        val packageName = json.getString("packageName")
        require(CrossUserProtocol.componentForPackage(packageName) != null)
        return CrossUserSessionState(
            schemaVersion = CrossUserProtocol.SCHEMA_VERSION,
            sessionId = json.getString("sessionId"),
            state = json.getString("state"),
            displayId = json.getInt("displayId"),
            taskId = json.getInt("taskId"),
            mainUserId = json.getInt("mainUserId"),
            targetUserId = json.getInt("targetUserId"),
            packageName = packageName,
            userWasRunning = json.getBoolean("userWasRunning"),
            userStartedBySession = json.getBoolean("userStartedBySession"),
            errorCode = if (json.isNull("errorCode")) null else json.getString("errorCode"),
            message = json.optString("message"),
        )
    }
}
