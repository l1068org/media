package androidx.media3.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

public class BrightnessUtil {

  public static Window getWindowFromView(View view) {
    if (view != null) {
      Activity activity = getActivityFromContext(view.getContext());
      if (activity != null) {
        return activity.getWindow();
      }
    }
    return null;
  }

  public static Activity getActivityFromContext(Context context) {
    Context currentContext = context;
    // 循环查找，直到找到 Activity 或确定不是 Activity 上下文
    while (currentContext instanceof ContextWrapper) {
      if (currentContext instanceof Activity) {
        return (Activity) currentContext;
      }
      currentContext = ((ContextWrapper) currentContext).getBaseContext();
    }
    return null;
  }

  /**
   * 判断是否开启了自动亮度调节
   */
  public static boolean isAutoBrightnessEnabled(Context context) {
    try {
      int mode = Settings.System.getInt(
          context.getContentResolver(),
          Settings.System.SCREEN_BRIGHTNESS_MODE
      );
      return mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    } catch (Settings.SettingNotFoundException e) {
      e.printStackTrace();
      return false;
    }
  }

  /**
   * 开启/关闭自动亮度调节
   */
  public static boolean setAutoBrightnessEnabled(Context context, boolean enabled) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
      return false; // 没有权限，需要申请
    }

    int mode = enabled ?
        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC :
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;

    return Settings.System.putInt(
        context.getContentResolver(),
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        mode
    );
  }

  /**
   * 获取系统当前亮度值（0-255）
   */
  public static int getSystemBrightness(Context context) {
    try {
      return Settings.System.getInt(
          context.getContentResolver(),
          Settings.System.SCREEN_BRIGHTNESS
      );
    } catch (Settings.SettingNotFoundException e) {
      e.printStackTrace();
      return 128; // 默认中间亮度
    }
  }

  /**
   * 设置系统亮度（0-255）
   * 注意：需要先关闭自动亮度，且需要WRITE_SETTINGS权限
   */
  public static boolean setSystemBrightness(Context context, int brightness) {
    if (brightness < 0 || brightness > 255) {
      return false; // 亮度值范围必须是0-255
    }

    // 先关闭自动亮度
    if (isAutoBrightnessEnabled(context)) {
      setAutoBrightnessEnabled(context, false);
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
      return false; // 没有权限，需要引导用户开启
    }

    return Settings.System.putInt(
        context.getContentResolver(),
        Settings.System.SCREEN_BRIGHTNESS,
        brightness
    );
  }

  /**
   * 设置当前窗口的亮度（仅影响当前应用）
   * @param window 当前Activity的window
   * @param brightness 亮度值（0.0-1.0，0.0最暗，1.0最亮）
   */
  public static void setWindowBrightness(Window window, float brightness) {
    WindowManager.LayoutParams lp = window.getAttributes();
    // 确保亮度值在0.0-1.0范围内
    lp.screenBrightness = Math.max(0.0f, Math.min(1.0f, brightness));
    window.setAttributes(lp);
  }
  public static float getWindowBrightness(Window window) {
    WindowManager.LayoutParams lp = window.getAttributes();
    // 确保亮度值在0.0-1.0范围内
    return lp.screenBrightness;
  }

  /**
   * 重置窗口亮度（跟随系统亮度）
   */
  public static void resetWindowBrightness(Window window) {
    WindowManager.LayoutParams lp = window.getAttributes();
    lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    window.setAttributes(lp);
  }

  /**
   * 检查是否有写入系统设置的权限（Android 6.0+）
   */
  public static boolean hasWriteSettingsPermission(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return Settings.System.canWrite(context);
    }
    return true; // 6.0以下默认有权限
  }

  /**
   * 获取申请写入系统设置权限的Intent
   */
  public static android.content.Intent getWriteSettingsIntent(Context context) {
    return new android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
        .setData(Uri.parse("package:" + context.getPackageName()));
  }

}
