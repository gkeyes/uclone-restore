package com.uclone.restore.crossuser.user

import com.uclone.crossuser.runtime.CrossUserRuntimeErrors
import com.uclone.restore.root.RootShellExecutor

enum class CrossUserCloneUnlockResult {
    ALREADY_UNLOCKED,
    UNLOCKED,
}

class CrossUserCloneUnlockException(
    val errorCode: String,
    message: String,
) : IllegalStateException(message)

/**
 * Unlocks an already-running clone user for a CrossUser session. The credential
 * is delivered only through the root process standard input and is never part
 * of the su command, Binder protocol, session journal, or task history.
 */
class CrossUserCloneCredentialUnlocker(
    private val shell: RootShellExecutor,
) {
    suspend fun unlockStartedUser(userId: Int, credential: String): CrossUserCloneUnlockResult {
        require(userId == TARGET_USER_ID) { CrossUserRuntimeErrors.TARGET_PACKAGE_DENIED }
        val protectedCredential = credential.trim()
        if (protectedCredential.isEmpty()) {
            throw CrossUserCloneUnlockException(
                CrossUserRuntimeErrors.CLONE_CREDENTIAL_MISSING,
                "未保存分系统锁屏密码，请先在设置中填写",
            )
        }
        val result = shell.execStreamingWithInput(
            command = unlockStartedUserScript(userId),
            standardInput = "$protectedCredential\n",
            timeoutSeconds = UNLOCK_TIMEOUT_SECONDS,
            onOutput = {},
        )
        val output = result.stdout + "\n" + result.stderr
        return when {
            result.isSuccess && "CROSSUSER_UNLOCK_RESULT=ALREADY_UNLOCKED" in output ->
                CrossUserCloneUnlockResult.ALREADY_UNLOCKED
            result.isSuccess && "CROSSUSER_UNLOCK_RESULT=UNLOCKED" in output ->
                CrossUserCloneUnlockResult.UNLOCKED
            "ERR_CROSSUSER_PIN_MISSING" in output -> throw CrossUserCloneUnlockException(
                CrossUserRuntimeErrors.CLONE_CREDENTIAL_MISSING,
                "未保存分系统锁屏密码，请先在设置中填写",
            )
            "ERR_CROSSUSER_PIN_VERIFY_FAILED:BAD_CREDENTIAL" in output ->
                throw CrossUserCloneUnlockException(
                    CrossUserRuntimeErrors.CLONE_CREDENTIAL_REJECTED,
                    "分系统锁屏密码验证失败，请在设置中更新密码",
                )
            else -> throw CrossUserCloneUnlockException(
                CrossUserRuntimeErrors.CLONE_UNLOCK_FAILED,
                "分系统自动解锁失败，微信尚未启动",
            )
        }
    }

    internal fun unlockStartedUserScript(userId: Int): String {
        require(userId == TARGET_USER_ID)
        return """
            set -u
            CLONE_USER=$userId
            clone_state() {
              /system/bin/am get-started-user-state "${'$'}CLONE_USER" 2>&1 || true
            }
            STATE_BEFORE_UNLOCK=${'$'}(clone_state)
            case "${'$'}STATE_BEFORE_UNLOCK" in
              *RUNNING_UNLOCKED*)
                echo "CROSSUSER_UNLOCK_RESULT=ALREADY_UNLOCKED"
                exit 0
                ;;
              *RUNNING_LOCKED*|*RUNNING*) ;;
              *)
                echo "ERR_CROSSUSER_USER_NOT_RUNNING" >&2
                exit 86
                ;;
            esac
            CLONE_UNLOCK_CREDENTIAL=""
            IFS= read -r CLONE_UNLOCK_CREDENTIAL || CLONE_UNLOCK_CREDENTIAL=""
            if [ -z "${'$'}CLONE_UNLOCK_CREDENTIAL" ]; then
              echo "ERR_CROSSUSER_PIN_MISSING" >&2
              exit 83
            fi
            VERIFY_EXIT=0
            VERIFY_OUTPUT=${'$'}(/system/bin/cmd lock_settings verify --old "${'$'}CLONE_UNLOCK_CREDENTIAL" --user "${'$'}CLONE_USER" 2>&1) || VERIFY_EXIT=${'$'}?
            CLONE_UNLOCK_CREDENTIAL=""
            case "${'$'}VERIFY_OUTPUT" in
              *"Lock credential verified successfully"*) VERIFY_RESULT="SUCCESS" ;;
              *"didn't match"*) VERIFY_RESULT="BAD_CREDENTIAL" ;;
              *"Profile uses unified challenge"*) VERIFY_RESULT="UNIFIED_CHALLENGE_UNSUPPORTED" ;;
              *"Request throttled"*) VERIFY_RESULT="THROTTLED" ;;
              *"Unknown command"*|*"Unknown option"*|*"Can't find service"*) VERIFY_RESULT="UNSUPPORTED" ;;
              *) VERIFY_RESULT="OTHER" ;;
            esac
            VERIFY_OUTPUT=""
            if [ "${'$'}VERIFY_EXIT" -ne 0 ] || [ "${'$'}VERIFY_RESULT" != "SUCCESS" ]; then
              echo "ERR_CROSSUSER_PIN_VERIFY_FAILED:${'$'}VERIFY_RESULT" >&2
              exit 84
            fi
            WAIT_INDEX=0
            while [ "${'$'}WAIT_INDEX" -lt 120 ]; do
              STATE_AFTER_VERIFY=${'$'}(clone_state)
              case "${'$'}STATE_AFTER_VERIFY" in
                *RUNNING_UNLOCKED*)
                  echo "CROSSUSER_UNLOCK_RESULT=UNLOCKED"
                  exit 0
                  ;;
              esac
              sleep 0.25
              WAIT_INDEX=${'$'}((WAIT_INDEX + 1))
            done
            echo "ERR_CROSSUSER_UNLOCK_TIMEOUT" >&2
            exit 85
        """.trimIndent()
    }

    private companion object {
        const val TARGET_USER_ID = 10
        const val UNLOCK_TIMEOUT_SECONDS = 45L
    }
}
