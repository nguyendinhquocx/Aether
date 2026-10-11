package com.zhousl.aether.agentmode;

import android.view.Surface;

interface IAetherAgentModeService {
    int createDisplay(String name, int width, int height, int density, in Surface surface) = 1;
    int createOwnedDisplay(String name, int width, int height, int density) = 2;
    void attachPreviewSurface(int displayId, in Surface surface) = 3;
    void detachPreviewSurface(int displayId) = 4;
    void releaseDisplay(int displayId) = 5;
    void launchPackage(String packageName, int displayId) = 6;
    String listDisplaysJson() = 13;
    String listInstalledAppsJson() = 14;
    // The service process exits when [client] dies, so it never outlives the Aether process.
    void linkClient(IBinder client) = 16;
    void destroy() = 16777114;
}
