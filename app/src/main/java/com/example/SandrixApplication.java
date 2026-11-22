package com.example;

import android.content.Context;

import androidx.multidex.MultiDex;
import androidx.multidex.MultiDexApplication;

import com.example.sandrix.logcat.SandrixLogcatBus;

/**
 * MultiDex-enabled Application entry point for Sandrix Studio IDE.
 */
public class SandrixApplication extends MultiDexApplication {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        SandrixLogcatBus.installHooks();
        // Install the engine files bundled in the APK (android.jar etc.) off the main thread.
        Thread engineInstall = new Thread(
                () -> com.example.sandrix.engine.SandrixToolchain.installBundledEngine(this),
                "SandrixEngineInstall");
        engineInstall.setDaemon(true);
        engineInstall.start();
        SandrixLogcatBus.log("I", "SandrixIDE", "Sandrix Studio started (SDK " + android.os.Build.VERSION.SDK_INT + ")");
    }
}
