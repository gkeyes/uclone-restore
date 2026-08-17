package com.uclone.crossuser.api;

import com.uclone.crossuser.api.CrossUserSessionState;

oneway interface ICrossUserCallback {
    void onStateChanged(in CrossUserSessionState state);
    void onFirstFrame(String sessionId, long monotonicNanos);
    void onTaskChanged(String sessionId, int taskId, String packageName);
    void onRecoverableError(String sessionId, String code, String message);
    void onTerminal(String sessionId, String code, String message);
}
