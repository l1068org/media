package androidx.media3.ui.overlayView;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.ui.R;


/**
 * 这里继承了LinearLayout，内部填充时就会额外套多一层无用的LinearLayout布局
 */
public class ProgressIconView extends LinearLayout {
  private ImageView iconImageView;
  private ProgressBar progressBar;

  public ProgressIconView(@NonNull Context context) {
    this(context, null);
  }

  public ProgressIconView(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init(context);
  }

  private void init(Context context) {
    // 设置布局方向为垂直
    setOrientation(VERTICAL);

    // 加载布局
    LayoutInflater.from(context).inflate(R.layout.view_progress_icon, this, true);

    // 获取控件引用
    iconImageView = findViewById(R.id.icon_image_view);
    progressBar = findViewById(R.id.progress_bar);
  }

  // 设置图标
  public void setIcon(@DrawableRes int resId) {
    iconImageView.setImageResource(resId);
  }

  // 设置进度条最大值
  public void setMaxProgress(int max) {
    progressBar.setMax(max);
  }

  // 设置当前进度
  public void setCurrentProgress(int progress) {
    progressBar.setProgress(progress);
  }

  // 获取当前进度
  public int getCurrentProgress() {
    return progressBar.getProgress();
  }

  // 获取最大值
  public int getMaxProgress() {
    return progressBar.getMax();
  }
}
