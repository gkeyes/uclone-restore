package com.uclone.crossuser.api;

import android.view.Surface;
import com.uclone.crossuser.api.CrossUserCapabilityReport;
import com.uclone.crossuser.api.CrossUserKeyPacket;
import com.uclone.crossuser.api.CrossUserProbeRequest;
import com.uclone.crossuser.api.CrossUserSessionConfig;
import com.uclone.crossuser.api.CrossUserSessionHandle;
import com.uclone.crossuser.api.CrossUserSessionState;
import com.uclone.crossuser.api.CrossUserTouchPacket;
import com.uclone.crossuser.api.ICrossUserCallback;

interface ICrossUserBridge {
    CrossUserCapabilityReport probe(in CrossUserProbeRequest request);
    CrossUserSessionHandle createSession(
        in CrossUserSessionConfig config,
        in Surface surface,
        ICrossUserCallback callback
    );
    CrossUserSessionState replaceSurface(
        String sessionId,
        String sessionToken,
        in Surface surface,
        int widthPx,
        int heightPx,
        int densityDpi,
        int rotation
    );
    CrossUserSessionState getSession(String sessionId, String sessionToken);
    boolean injectTouch(String sessionId, String sessionToken, in CrossUserTouchPacket packet);
    boolean injectKey(String sessionId, String sessionToken, in CrossUserKeyPacket packet);
    void closeSession(String sessionId, String sessionToken, int reason);
    boolean recoverStaleSession(in CrossUserSessionState state);
}
