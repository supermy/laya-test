package com.selfhost.layatest;

import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** 左栏竖排 tab chip 共享组件:整词旋转 90°(slot 定尺寸,词长自适应;中英文同构)。
 *  供决策/报表/网关/系统四页 rail 共用;dp 尺寸经 host(MainActivity)换算。 */
final class RailChip {
  private RailChip() {}

  /** 把按钮 c 包进旋转 90° 的定尺寸 slot 并返回 slot(调用方 addView 进 rail) */
  static FrameLayout make(MainActivity m, Button c) {
    int visW = Ui.dp(m,40);
    int visH = (int) c.getPaint().measureText(c.getText().toString()) + Ui.dp(m,28);
    c.setRotation(90);
    c.setPadding(0, Ui.dp(m,10), 0, Ui.dp(m,10));
    FrameLayout slot = new FrameLayout(m);
    slot.addView(c, new FrameLayout.LayoutParams(visH, visW, Gravity.CENTER));
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(visW, visH);
    slp.bottomMargin = Ui.dp(m,6);
    slot.setLayoutParams(slp);
    return slot;
  }
}
