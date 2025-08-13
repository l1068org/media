package androidx.media3.ui.overlayView;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;

/**
 * 展示修改进度和缩略图
 */
public class VideoProgressImageView extends FrameLayout {

  private TextView progressText;
  private TextView separatorText;
  private TextView durationText;
  private ImageView imageView;

  public VideoProgressImageView(@NonNull Context context) {
    super(context);
    initView();
  }

  private void initView() {
    progressText = new TextView(getContext());
    separatorText = new TextView(getContext());
    durationText = new TextView(getContext());

    progressText.setTextSize(14);
    separatorText.setTextSize(10);
    durationText.setTextSize(14);

    progressText.setTextColor(Color.WHITE);
    progressText.setTypeface(progressText.getTypeface(), Typeface.BOLD);
    durationText.setTypeface(progressText.getTypeface(), Typeface.BOLD);
    separatorText.setText("  /  ");

    LinearLayout linearLayout = new LinearLayout(getContext());

    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
    params.gravity = Gravity.CENTER_VERTICAL;
    linearLayout.addView(progressText, params);
    linearLayout.addView(separatorText, params);
    linearLayout.addView(durationText, params);

    addView(linearLayout);

  }

  public void setProgressText(String text) {
    progressText.setText(text);
  }

  public void setDurationText(String text) {
    durationText.setText(text);
  }


}
