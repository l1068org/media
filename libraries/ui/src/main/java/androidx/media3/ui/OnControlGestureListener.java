package androidx.media3.ui;

public interface OnControlGestureListener {

  void onClick();

  void onDoubleClick();

  void onLeftVerticalSlide(float deltaY, float totalDeltaY);

  void onRightVerticalSlide(float deltaY, float totalDeltaY);

  void onHorizontalSlide(float deltaX, float totalDeltaX);

  void onLongPress();

  void onLongPressAndThenHorizontalSlide(float deltaX, float totalDeltaX);

  void onMoveUp(ControlGestureDetector.SlideType type);

}
