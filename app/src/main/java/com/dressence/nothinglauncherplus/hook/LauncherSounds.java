package com.dressence.nothinglauncherplus.hook;

import android.content.Context;
import android.os.Bundle;

import com.dressence.nothinglauncherplus.Config;
import com.dressence.nothinglauncherplus.sound.Sfx;
import com.dressence.nothinglauncherplus.sound.SoundEngine;
import com.dressence.nothinglauncherplus.sound.Style;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Console-style sound for the launcher: background music while the home screen or drawer is in
 * front, and interface sounds for the drawer, folders, launching apps, pages and menus. Both are
 * off until switched on in the module.
 */
final class LauncherSounds {

    private static final String CLS_LAUNCHER = "com.android.launcher3.Launcher";
    private static final String CLS_STATE = "com.android.launcher3.LauncherState";
    private static final String CLS_FOLDER = "com.android.launcher3.folder.Folder";
    private static final String CLS_WORKSPACE = "com.android.launcher3.Workspace";
    private static final String CLS_POPUP = "com.android.launcher3.popup.ArrowPopup";
    private static final float SFX_VOLUME = 0.7f;

    private static LauncherSounds instance;

    private final ClassLoader cl;
    private final ConfigStore store;
    private SoundEngine engine;
    private boolean resumed;
    private Object lastState;

    private LauncherSounds(ClassLoader cl, ConfigStore store) {
        this.cl = cl;
        this.store = store;
    }

    static void install(ClassLoader cl, ConfigStore store) {
        LauncherSounds sounds = new LauncherSounds(cl, store);
        instance = sounds;
        Hooks.install("sound: music", sounds::hookLifecycle);
        Hooks.install("sound: drawer", sounds::hookDrawer);
        Hooks.install("sound: launch", sounds::hookLaunch);
        Hooks.install("sound: folders", sounds::hookFolders);
        Hooks.install("sound: pages", sounds::hookPages);
        Hooks.install("sound: menus", sounds::hookMenus);
    }

    /** Plays {@code sfx} if interface sounds are on; for other hooks (controller, sheets). */
    static void effect(Sfx sfx) {
        LauncherSounds sounds = instance;
        if (sounds != null) {
            sounds.play(sfx);
        }
    }

    private void play(Sfx sfx) {
        if (engine != null && resumed && store.get().uiSounds) {
            engine.play(sfx, SFX_VOLUME);
        }
    }

    // -------------------------------------------------------------------------------------
    // Music follows the launcher being in front
    // -------------------------------------------------------------------------------------

    private void hookLifecycle() {
        Class<?> launcher = XposedHelpers.findClass(CLS_LAUNCHER, cl);
        XposedHelpers.findAndHookMethod(launcher, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                engine = SoundEngine.get((Context) param.thisObject);
            }
        });
        XposedHelpers.findAndHookMethod(launcher, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                resumed = true;
                apply();
            }
        });
        XposedHelpers.findAndHookMethod(launcher, "onPause", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                resumed = false;
                apply();
            }
        });
        store.addChangeListener(this::apply);
    }

    private void apply() {
        if (engine == null) {
            return;
        }
        Config config = store.get();
        if (resumed && config.music) {
            engine.playMusic(Style.parse(config.musicStyle), config.musicVolume / 100f);
        } else {
            engine.stopMusic();
        }
        if (config.uiSounds) {
            engine.prepareSounds();
        }
    }

    // -------------------------------------------------------------------------------------
    // Interface sounds
    // -------------------------------------------------------------------------------------

    private void hookDrawer() {
        Class<?> states = XposedHelpers.findClass(CLS_STATE, cl);
        Object normal = XposedHelpers.getStaticObjectField(states, "NORMAL");
        Object allApps = XposedHelpers.getStaticObjectField(states, "ALL_APPS");
        XposedHelpers.findAndHookMethod(CLS_LAUNCHER, cl, "onStateSetStart", states,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object state = param.args[0];
                        if (state == allApps && lastState != allApps) {
                            play(Sfx.OPEN_DRAWER);
                        } else if (state == normal && lastState == allApps) {
                            play(Sfx.CLOSE_DRAWER);
                        }
                        lastState = state;
                    }
                });
    }

    private void hookLaunch() {
        XposedHelpers.findAndHookMethod(CLS_LAUNCHER, cl, "startActivitySafely",
                android.view.View.class, android.content.Intent.class,
                XposedHelpers.findClass("com.android.launcher3.model.data.ItemInfo", cl),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() != null) {
                            play(Sfx.LAUNCH);
                        }
                    }
                });
    }

    private void hookFolders() {
        Class<?> folder = XposedHelpers.findClass(CLS_FOLDER, cl);
        XposedHelpers.findAndHookMethod(folder, "animateOpen", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                play(Sfx.OPEN_FOLDER);
            }
        });
        XposedHelpers.findAndHookMethod(folder, "handleClose", boolean.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (XposedHelpers.getBooleanField(param.thisObject, "mIsOpen")) {
                    play(Sfx.CLOSE_FOLDER);
                }
            }
        });
        // Smart drawer folder sheets: opening is reported by SmartDrawer's opener hook.
        XposedHelpers.findAndHookMethod(SmartDrawer.SMART_PKG + "SmartFullSheet", cl,
                "handleClose", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (XposedHelpers.getBooleanField(param.thisObject, "mIsOpen")) {
                            play(Sfx.CLOSE_FOLDER);
                        }
                    }
                });
    }

    private void hookPages() {
        XposedHelpers.findAndHookMethod(CLS_WORKSPACE, cl, "notifyPageSwitchListener", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int previous = (Integer) param.args[0];
                        int current = XposedHelpers.getIntField(param.thisObject, "mCurrentPage");
                        if (previous != current) {
                            play(Sfx.PAGE);
                        }
                    }
                });
    }

    private void hookMenus() {
        XposedHelpers.findAndHookMethod(CLS_POPUP, cl, "show", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                play(Sfx.MENU);
            }
        });
    }
}
