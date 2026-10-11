package com.selfhost.layatest;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** UI 工具类(从 MainActivity 抽出):dp 换算 / 圆角背景 / 表单输入框 / 提示文本 / 主按钮。
 *  全部静态方法,Context 由调用方传入;颜色常量沿用 MainActivity(同包)。 */
final class Ui {
  private Ui() {}

  /** dp → px */
  static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

  /** 圆角矩形背景 */
  static GradientDrawable pill(int color, float radiusPx) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(radiusPx);
    return g;
  }

  /** 下划线输入框(网关页风格):不带 pill 背景 */
  static EditText fieldU(Context c, LinearLayout parent, String hint) { return fieldU(c, parent, hint, ""); }
  static EditText fieldU(Context c, LinearLayout parent, String hint, String text) {
    EditText e = new EditText(c);
    e.setHint(hint); e.setTextSize(13); e.setText(text); e.setSingleLine(true);
    e.setPadding(dp(c, 4), dp(c, 10), dp(c, 4), dp(c, 10));
    parent.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    return e;
  }

  /** 小号灰色提示文本 */
  static TextView hint(Context c, String s) {
    TextView t = new TextView(c);
    t.setText(s); t.setTextSize(12); t.setTextColor(0xFF666C77); t.setPadding(0, dp(c, 8), 0, dp(c, 4));
    return t;
  }

  /** 白底圆角输入框(报表/系统页风格) */
  static EditText field(Context c, LinearLayout parent, String hint) { return field(c, parent, hint, ""); }
  static EditText field(Context c, LinearLayout parent, String hint, String text) {
    EditText e = new EditText(c);
    e.setHint(hint); e.setTextSize(13); e.setText(text); e.setSingleLine(true);
    e.setBackground(pill(Color.WHITE, dp(c, 10)));
    e.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
    parent.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    return e;
  }

  /** JevLive 式统计卡行:深底大数字卡(每行最多4张);返回数字 TextView 数组供实时刷新 */
  public static TextView[] statsCards(Context c, LinearLayout parent, String[] labels, int[] colors) {
    TextView[] nums = new TextView[labels.length];
    int per = 4;
    int rows = (labels.length + per - 1) / per;
    for (int r = 0; r < rows; r++) {
      LinearLayout gr = new LinearLayout(c);
      for (int i = r * per; i < Math.min(labels.length, (r + 1) * per); i++) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(pill(0xFF161A22, dp(c, 10)));
        TextView num = new TextView(c);
        num.setText("0"); num.setTextSize(16); num.setTypeface(Typeface.DEFAULT_BOLD);
        num.setTextColor(colors[i]); num.setGravity(Gravity.CENTER);
        card.addView(num, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView lab = new TextView(c);
        lab.setText(labels[i]); lab.setTextSize(10); lab.setTextColor(0xFF8A93A0); lab.setGravity(Gravity.CENTER);
        card.addView(lab, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = (i % per == per - 1 || i == labels.length - 1) ? 0 : dp(c, 6);
        clp.bottomMargin = dp(c, 6);
        card.setLayoutParams(clp);
        nums[i] = num;
        gr.addView(card);
      }
      parent.addView(gr, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }
    return nums;
  }

  /** 主色圆角按钮,加入 parent 并带 8dp 底间距 */
  static Button button(Context c, LinearLayout parent, String label) {
    Button b = new Button(c);
    b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(13);
    b.setBackground(pill(MainActivity.PRIMARY, dp(c, 16)));
    b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    blp.bottomMargin = dp(c, 8); // 相邻按钮隔开
    parent.addView(b, blp);
    return b;
  }
}
