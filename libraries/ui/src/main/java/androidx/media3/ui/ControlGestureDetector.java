package androidx.media3.ui;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Log;

public class ControlGestureDetector extends GestureDetector.SimpleOnGestureListener  {

  private final String TAG = "ControlGestureDetector";
  private final int SLIDE_THRESHOLD = 10;

  private final GestureDetector gestureDetector;
  private final LongPressDetector longPressDetector;
  private final OnControlGestureListener listener;
  private final int touchSlop;
  private final int screenWidth;

  // 滑动状态跟踪
  enum SlideType { NONE, LEFT_VERTICAL, RIGHT_VERTICAL, HORIZONTAL, PRESS_HORIZONTAL}
  private SlideType currentSlideType = SlideType.NONE;
  private float totalDeltaX;
  private float totalDeltaY;


  public ControlGestureDetector(@Nullable Context context, @NonNull OnControlGestureListener listener) {
    gestureDetector = new GestureDetector(context, this);
    gestureDetector.setIsLongpressEnabled(false);

    longPressDetector = new LongPressDetector(context, listener);

    this.listener = listener;
    this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    this.screenWidth = context.getResources().getDisplayMetrics().widthPixels;
  }

  public boolean onTouchEvent(MotionEvent event) {
    boolean endEvent = event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL;
    if (endEvent) {
      if (currentSlideType != SlideType.NONE || longPressDetector.inLongPress) {
        SlideType upType = currentSlideType != SlideType.NONE ? currentSlideType : SlideType.PRESS_HORIZONTAL;
        listener.onMoveUp(upType);
      }
      resetSlideState();
    }

    boolean result = gestureDetector.onTouchEvent(event);

    // 不要过滤抬起事件，不然可能导致长按状态无法清除
    if (currentSlideType == SlideType.NONE || endEvent) {
//      Log.d(TAG, "checkLongPress");
      longPressDetector.onTouchEvent(event);
    } else {
      longPressDetector.stop();
    }

    return result;
  }

  // 重置滑动状态
  private void resetSlideState() {
    currentSlideType = SlideType.NONE;
    totalDeltaY = 0;
  }

  @Override
  public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
    if (!longPressDetector.inLongPress) {
      listener.onClick();
    }
    return true;
  }

  @Override
  public boolean onDoubleTap(@NonNull MotionEvent e) {
    listener.onDoubleClick();
    return true;
  }

  /**
   * 保持 onDown() 返回 true 确保接收后续事件
   */
  @Override
  public boolean onDown(@NonNull MotionEvent e) {
    Log.d(TAG, "onDown");
    resetSlideState(); // 每次按下时重置状态
    return true;
  }

  // 处理滑动
  @Override
  public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
    if (e1 == null || e2 == null) return false;
//    Log.d(TAG, "onScroll");

    // 如果是长按事件，那就只能是长按水平滑动
    if (currentSlideType == SlideType.NONE && longPressDetector.inLongPress) {
      totalDeltaX = 0;
      currentSlideType = SlideType.PRESS_HORIZONTAL;
    }

    // 如果是新的滑动序列，初始化状态
    if (currentSlideType == SlideType.NONE) {
      totalDeltaX = 0;
      totalDeltaY = 0;

      // 计算初始滑动方向
      float deltaX = e2.getX() - e1.getX();
      float deltaY = e2.getY() - e1.getY();
      float absDeltaX = Math.abs(deltaX);
      float absDeltaY = Math.abs(deltaY);

      // 确保滑动距离超过阈值
      if (Math.max(absDeltaX, absDeltaY) < touchSlop) {
        return false;
      }

      // 判断水平滑动
      if (absDeltaX > absDeltaY * 1.5) {
        currentSlideType = SlideType.HORIZONTAL;
      }
      // 判断垂直滑动
      else if (absDeltaY > absDeltaX * 1.5) {
        // 根据起始X坐标判断左右区域
        if (e1.getX() < screenWidth / 3) {
          currentSlideType = SlideType.LEFT_VERTICAL;
        } else if (e1.getX() > screenWidth * 2 / 3) {
          currentSlideType = SlideType.RIGHT_VERTICAL;
        }
      }
    }

    // 处理垂直滑动
    if (currentSlideType == SlideType.LEFT_VERTICAL || currentSlideType == SlideType.RIGHT_VERTICAL) {
      // 计算当前增量
      float currentDeltaY = e2.getY() - (e1.getY() + totalDeltaY);
      // 数值过小时拦截，减少回调频率
      if (Math.abs(currentDeltaY) > SLIDE_THRESHOLD) {
        totalDeltaY += currentDeltaY;

        if (currentSlideType == SlideType.LEFT_VERTICAL) {
          listener.onLeftVerticalSlide(currentDeltaY, totalDeltaY);
        } else {
          listener.onRightVerticalSlide(currentDeltaY, totalDeltaY);
        }
      }
      return true;
    }

    // 处理水平滑动
    if (currentSlideType == SlideType.HORIZONTAL || currentSlideType == SlideType.PRESS_HORIZONTAL) {
      // 计算当前增量
      float currentDeltaX = e2.getX() - (e1.getX() + totalDeltaX);
      // 数值过小时拦截，减少回调频率
      if (Math.abs(currentDeltaX) > SLIDE_THRESHOLD) {
        totalDeltaX += currentDeltaX;

        if (currentSlideType == SlideType.HORIZONTAL) {
          listener.onHorizontalSlide(currentDeltaX, totalDeltaX);
        } else {
          listener.onLongPressAndThenHorizontalSlide(currentDeltaX, totalDeltaX);
        }
      }
      return true;
    }

    // 如果是长按则返回true，否则事件未定性，暂不处理
    return longPressDetector.inLongPress;
  }

  private static class LongPressDetector {

    private final GestureDetector gestureDetector;
    private final OnControlGestureListener listener;
    private boolean stop;
    private boolean inLongPress;

    public LongPressDetector(@Nullable Context context, OnControlGestureListener gestureListener) {
      this.listener = gestureListener;
      gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
        @Override
        public void onLongPress(@NonNull MotionEvent e) {

          if (stop) return;

          inLongPress = true;
          listener.onLongPress();
        }
      });
    }

    public void onTouchEvent(MotionEvent event) {
      if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
        stop = false;
        inLongPress = false;
      }
      gestureDetector.onTouchEvent(event);
    }

    public void stop() {
      stop = true;
    }
  }

  /*private class LongPressGestureDetector extends GestureDetector {

    private Field mInLongPressField;

    public LongPressGestureDetector(@Nullable Context context) {
      super(context, new SimpleOnGestureListener() {
        @Override
        public void onLongPress(@NonNull MotionEvent e) {
          super.onLongPress(e);
          listener.onLongPressAndThenHorizontalSlide(0,0);
        }
      });

      try {
        mInLongPressField = GestureDetector.class.getDeclaredField("mInLongPress");
        mInLongPressField.setAccessible(true);
      } catch (Exception ignored) {}
    }

    public boolean inLongPress() {
      boolean inLongPress = false;
      try {
        inLongPress = (boolean) mInLongPressField.get(this);
      } catch (Exception ignored) {}

      Log.d("LongPressDetector", "inLongPress : " + inLongPress);
      return inLongPress;
    }
  }*/
}
