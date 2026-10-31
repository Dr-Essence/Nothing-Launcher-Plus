package com.dressence.nothinglauncherplus.perf;

import android.os.Build;

import java.nio.charset.StandardCharsets;

/**
 * The performance features are for the CMF Phone 1 only (MediaTek MT6878, its sensors and
 * clocks as read from the phone): this says whether the module runs on one, and runs root
 * commands for them.
 */
public final class Device {

    /** ro.product.device of the CMF Phone 1 ("Tetris", model A015). */
    private static final String CMF_PHONE_1 = "Tetris";
    /** Where Magisk / KernelSU keep the PerfMTK module. */
    private static final String PERFMTK_MODULE = "/data/adb/modules/perfmtk";

    private Device() {
    }

    public static boolean isCmfPhone1() {
        return CMF_PHONE_1.equals(Build.DEVICE);
    }

    /** Whether PerfMTK is installed (its module folder is readable only as root). */
    public static boolean hasPerfMtk() {
        return su("[ -d " + PERFMTK_MODULE + " ] && [ ! -e " + PERFMTK_MODULE + "/disable ]")
                == null;
    }

    /**
     * Runs {@code command} as root and waits for it; null if it succeeded, else its output
     * or why it could not run. Call off the main thread: root may ask for permission first.
     */
    public static String su(String command) {
        try {
            Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            int code = p.waitFor();
            return code == 0 ? null : output.isEmpty() ? "failed (" + code + ")" : output;
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : e.toString();
        }
    }
}
