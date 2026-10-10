package com.selfhost.layatest;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** ④ 系统页(从 MainActivity 拆出):语言切换 / 模型包上传 / 业务卡片管理 / 图表子页 */
class SystemPage {
  private final MainActivity m;
  private LinearLayout bizListPanel;
  private EditText upSrc, upTask;
  private TextView upStatus, sysView;

  SystemPage(MainActivity host) { m = host; }

  void applyPicked(android.net.Uri uri, String fname) {
    upStatus.setText(m.getString(R.string.picking_zip));
    new Thread(() -> {
      try {
        File dst = new File(m.getCacheDir(), "picked-upload.zip");
        long total = 0;
        try (InputStream in = m.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
          byte[] buf = new byte[256 * 1024];
          int n;
          while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
        }
        final long mb = total / 1048576;
        final String dstPath = dst.getAbsolutePath();
        m.runOnUiThread(() -> {
          upSrc.setText(dstPath);
          if (upTask.getText().toString().trim().isEmpty()) {
            String guess = fname.replaceFirst("(?i)\\.zip$", "").replaceFirst("^laya-litert-", "");
            if (guess.matches("[a-zA-Z0-9_-]{1,32}")) upTask.setText(guess);
          }
          upStatus.setText(m.getString(R.string.picked_msg, fname, mb));
        });
      } catch (Throwable e) {
        m.runOnUiThread(() -> upStatus.setText(m.getString(R.string.read_failed, e.getMessage())));
      }
    }).start();
  }



  void build() {
    m.refreshTasks();
    LinearLayout l = new LinearLayout(m);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(Ui.dp(m,12), Ui.dp(m,8), Ui.dp(m,12), Ui.dp(m,8));

    // ---- 语言 / Language(应用内切换,立即生效) ----
    LinearLayout langRow = new LinearLayout(m);
    langRow.setOrientation(LinearLayout.VERTICAL);
    langRow.setBackground(Ui.pill(0xFFF0F4FF, Ui.dp(m,10)));
    langRow.setPadding(Ui.dp(m,10), Ui.dp(m,8), Ui.dp(m,10), Ui.dp(m,8));
    LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    langLp.bottomMargin = Ui.dp(m,10);
    langRow.setLayoutParams(langLp);
    TextView langHead = new TextView(m);
    langHead.setText(m.getString(R.string.lang_label)); langHead.setTextSize(14); langHead.setTypeface(Typeface.DEFAULT_BOLD);
    langRow.addView(langHead);
    LinearLayout langBtns = new LinearLayout(m);
    langBtns.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    btnsLp.topMargin = Ui.dp(m,4);
    langBtns.setLayoutParams(btnsLp);
    String curLoc = m.uiLocale();
    String[] locIds = {"sys", "zh", "en"};
    for (String id : locIds) {
      final String fid = id;
      Button b = new Button(m);
      b.setText("sys".equals(id) ? m.getString(R.string.lang_follow) : "zh".equals(id) ? m.getString(R.string.lang_zh) : m.getString(R.string.lang_en));
      b.setAllCaps(false); b.setTextSize(12);
      b.setMinHeight(0); b.setMinimumWidth(0); b.setMinimumHeight(0);
      b.setPadding(Ui.dp(m,12), Ui.dp(m,6), Ui.dp(m,12), Ui.dp(m,6));
      boolean on = id.equals(curLoc);
      b.setTextColor(on ? Color.WHITE : 0xFF1A2B4C);
      b.setBackground(Ui.pill(on ? m.PRIMARY : 0xFFE7EAF2, Ui.dp(m,14)));
      LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      blp.leftMargin = Ui.dp(m,8);
      b.setLayoutParams(blp);
      b.setOnClickListener(v -> {
        if (!fid.equals(m.uiLocale())) {
          m.getSharedPreferences("ui", m.MODE_PRIVATE).edit().putString("locale", fid).apply();
          m.recreate(); // attachBaseContext 读取新 locale 重建整套 UI
        }
      });
      langBtns.addView(b);
    }
    langRow.addView(langBtns);
    l.addView(langRow);

    // ---- 手动上传模型包输入界面 ----
    LinearLayout up = new LinearLayout(m);
    up.setOrientation(LinearLayout.VERTICAL);
    up.setBackground(Ui.pill(0xFFF0F4FF, Ui.dp(m,10)));
    up.setPadding(Ui.dp(m,10), Ui.dp(m,8), Ui.dp(m,10), Ui.dp(m,10));
    LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    ulp.bottomMargin = Ui.dp(m,10);
    up.setLayoutParams(ulp);
    TextView upHead = new TextView(m);
    upHead.setText(m.getString(R.string.upload_head)); upHead.setTextSize(14); upHead.setTypeface(Typeface.DEFAULT_BOLD);
    up.addView(upHead);
    upSrc = Ui.fieldU(m,up, m.getString(R.string.pkg_path_hint));
    Button pickBtn = Ui.button(m,up, m.getString(R.string.pick_zip));
    pickBtn.setOnClickListener(v -> {
      Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      it.addCategory(Intent.CATEGORY_OPENABLE);
      it.setType("*/*");
      it.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
      m.startActivityForResult(it, MainActivity.REQ_PICK_ZIP);
    });
    upTask = Ui.fieldU(m,up, m.getString(R.string.task_name_hint));
    upStatus = new TextView(m);
    upStatus.setTextSize(11); upStatus.setTextColor(0xFF66707E);
    upStatus.setText(m.getString(R.string.upload_note));
    up.addView(upStatus);
    Button impBtn = Ui.button(m,up, m.getString(R.string.upload_register));
    impBtn.setOnClickListener(v -> {
      String src = upSrc.getText().toString().trim();
      String task = upTask.getText().toString().trim();
      if (src.isEmpty() || task.isEmpty()) { upStatus.setText(m.getString(R.string.fill_path_task)); return; }
      upStatus.setText(m.getString(R.string.uploading));
      new Thread(() -> {
        String err = com.laya.DecisionCore.importPackage(m.getApplicationContext(), src, task);
        if (err == null && src.equals(new File(m.getCacheDir(), "picked-upload.zip").getAbsolutePath()))
          new File(m.getCacheDir(), "picked-upload.zip").delete(); // 选择器中转 zip 用完即删
        m.runOnUiThread(() -> {
          if (err == null) {
            upStatus.setText(m.getString(R.string.upload_ok, task));
            rebuildBizList();
          } else upStatus.setText("❌ " + err);
        });
      }).start();
    });
    l.addView(up);

    Button rescan = Ui.button(m,l, m.getString(R.string.rescan));
    rescan.setOnClickListener(v -> rebuildBizList());
    l.addView(Ui.hint(m,m.getString(R.string.biz_list_hint)));
    bizListPanel = new LinearLayout(m);
    bizListPanel.setOrientation(LinearLayout.VERTICAL);
    l.addView(bizListPanel);
    rebuildBizList();

    bizListPanel = new LinearLayout(m);
    bizListPanel.setOrientation(LinearLayout.VERTICAL);
    l.addView(bizListPanel);
    rebuildBizList();

    sysView = new TextView(m);
    sysView.setText(com.laya.DecisionCore.backendInfo(m) + m.getString(R.string.sys_tail));
    sysView.setTextSize(13);
    l.addView(sysView);

    // 泳道数据流独立成「数据流」子页(fig5 同构:一次决策请求的端到端路径)
    LinearLayout p3 = new LinearLayout(m);
    p3.setOrientation(LinearLayout.VERTICAL);
    p3.setPadding(Ui.dp(m,12), Ui.dp(m,8), Ui.dp(m,12), Ui.dp(m,8));
    p3.addView(Ui.hint(m,m.getString(R.string.flow_hint_data)));
    p3.addView(new DiagramView(m, 1));

    // ---- 子标签页:左侧竖排(系统/架构图/流程图/数据流)+ 显隐开关 ----
    LinearLayout p1 = new LinearLayout(m);
    p1.setOrientation(LinearLayout.VERTICAL);
    p1.setPadding(Ui.dp(m,12), Ui.dp(m,8), Ui.dp(m,12), Ui.dp(m,8));
    p1.addView(Ui.hint(m,m.getString(R.string.flow_hint_arch)));
    p1.addView(new DiagramView(m, 0));
    LinearLayout p2 = new LinearLayout(m);
    p2.setOrientation(LinearLayout.VERTICAL);
    p2.setPadding(Ui.dp(m,12), Ui.dp(m,8), Ui.dp(m,12), Ui.dp(m,8));
    p2.addView(Ui.hint(m,m.getString(R.string.flow_hint_proc)));
    p2.addView(new DiagramView(m, 3));

    String[] subNames = {m.getString(R.string.sub_sys), m.getString(R.string.sub_arch), m.getString(R.string.sub_flow), m.getString(R.string.sub_data)};
    LinearLayout[] subPanels = {l, p1, p2, p3};
    final ScrollView[] subScrolls = new ScrollView[4];
    for (int i = 0; i < 4; i++) {
      ScrollView sv = new ScrollView(m);
      sv.addView(subPanels[i]);
      subScrolls[i] = sv;
    }
    final FrameLayout subHolder = new FrameLayout(m);
    final Button[] chips = new Button[4];
    final LinearLayout rail = new LinearLayout(m);
    rail.setOrientation(LinearLayout.VERTICAL);
    for (int i = 0; i < 4; i++) {
      final int k = i;
      Button c = new Button(m);
      c.setText(subNames[i]); c.setAllCaps(false); c.setTextSize(12);
      c.setMinHeight(0); c.setMinimumHeight(0);
      c.setOnClickListener(v -> {
        subHolder.removeAllViews();
        subHolder.addView(subScrolls[k]);
        m.scroller = subScrolls[k];
        for (int j = 0; j < 4; j++) {
          chips[j].setTextColor(j == k ? Color.WHITE : 0xFF1A2B4C);
          chips[j].setBackground(Ui.pill(j == k ? m.PRIMARY : 0xFFE7EAF2, Ui.dp(m,12)));
        }
      });
      chips[i] = c;
      rail.addView(RailChip.make(m, c));
    }
    final LinearLayout leftCol = new LinearLayout(m);
    leftCol.setOrientation(LinearLayout.VERTICAL);
    leftCol.setPadding(Ui.dp(m,4), Ui.dp(m,4), Ui.dp(m,0), Ui.dp(m,0));
    ScrollView railScroll = new ScrollView(m);
    railScroll.addView(rail); // 左侧 tab 菜单可上下滑动(小屏防截断)
    leftCol.addView(railScroll);
    LinearLayout.LayoutParams lclp = new LinearLayout.LayoutParams(Ui.dp(m,40), LinearLayout.LayoutParams.MATCH_PARENT);
    lclp.rightMargin = Ui.dp(m,2);
    leftCol.setLayoutParams(lclp);
    // 显隐开关在标题栏左侧(☰),点击收起/展开左栏
    m.menuBtn.setOnClickListener(v -> {
      boolean show = leftCol.getVisibility() == View.GONE;
      leftCol.setVisibility(show ? View.VISIBLE : View.GONE);
    });
    LinearLayout sysTop = new LinearLayout(m);
    sysTop.setOrientation(LinearLayout.HORIZONTAL);
    sysTop.addView(leftCol);
    subHolder.addView(subScrolls[0]);
    m.scroller = subScrolls[0];
    subHolder.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    sysTop.addView(subHolder);
    sysTop.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
    m.body.addView(sysTop);
    chips[0].performClick();
  }

  /** 单个业务卡片(加载态/操作按钮/详情/导出/删除) */
  private LinearLayout bizCard(String task, String label) {
    boolean loaded = new File(m.getFilesDir(), "laya-" + task + "/laya_ml_s256_embeds_wfp16.tflite").isFile()
        || new File(m.getFilesDir(), "laya-" + task + "/laya_ml_s256_embeds_npu.tflite").isFile();

    LinearLayout card = new LinearLayout(m);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackground(Ui.pill(loaded ? 0xFFEAF3FF : 0xFFF7F8FA, Ui.dp(m,10)));
    card.setPadding(Ui.dp(m,10), Ui.dp(m,8), Ui.dp(m,10), Ui.dp(m,10));
    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    clp.bottomMargin = Ui.dp(m,8);
    card.setLayoutParams(clp);

    TextView head = new TextView(m);
    head.setText((loaded ? "✅ " : "📦 ") + label + "  [" + task + "]");
    head.setTextSize(14); head.setTypeface(Typeface.DEFAULT_BOLD); head.setTextColor(0xFF1A2B4C);
    card.addView(head);
    TextView st = new TextView(m);
    st.setText(m.getString(loaded ? R.string.loaded_state : R.string.not_loaded_state));
    st.setTextSize(11); st.setTextColor(0xFF66707E);
    st.setPadding(0, Ui.dp(m,2), 0, Ui.dp(m,4));
    card.addView(st);

    // 单按钮动态切换:未加载=加载(装入 app);已加载=卸载(释放空间)
    Button toggle = new Button(m);
    toggle.setText(m.getString(loaded ? R.string.uninstall : R.string.load_btn));
    toggle.setAllCaps(false); toggle.setTextSize(12);
    toggle.setTextColor(loaded ? 0xFF444A55 : Color.WHITE);
    toggle.setBackground(Ui.pill(loaded ? m.CHIP_OFF : m.PRIMARY, Ui.dp(m,14)));
    toggle.setPadding(Ui.dp(m,8), Ui.dp(m,6), Ui.dp(m,8), Ui.dp(m,6));
    toggle.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    toggle.setOnClickListener(v -> {
      if (loaded) {
        com.laya.DecisionCore.unload(m, task);
        rebuildBizList();
      } else {
        st.setText(m.getString(R.string.loading_model));
        new Thread(() -> {
          try { com.laya.DecisionCore.preload(m, task); m.runOnUiThread(() -> rebuildBizList()); }
          catch (Throwable e) { m.runOnUiThread(() -> st.setText(m.getString(R.string.load_failed, e.getMessage()))); }
        }).start();
      }
    });
    card.addView(toggle);

    // 模型详情(展开/收起)+ 导出 zip + 删除业务
    LinearLayout row2 = new LinearLayout(m);
    row2.setOrientation(LinearLayout.HORIZONTAL);
    Button detailBtn = new Button(m);
    detailBtn.setText(m.getString(R.string.detail_btn)); detailBtn.setAllCaps(false); detailBtn.setTextSize(12);
    detailBtn.setTextColor(0xFF444A55); detailBtn.setBackground(Ui.pill(m.CHIP_OFF, Ui.dp(m,14)));
    detailBtn.setPadding(Ui.dp(m,4), Ui.dp(m,6), Ui.dp(m,4), Ui.dp(m,6));
    Button expBtn = new Button(m);
    expBtn.setText(m.getString(R.string.export_zip)); expBtn.setAllCaps(false); expBtn.setTextSize(12);
    expBtn.setTextColor(0xFF444A55); expBtn.setBackground(Ui.pill(m.CHIP_OFF, Ui.dp(m,14)));
    expBtn.setPadding(Ui.dp(m,4), Ui.dp(m,6), Ui.dp(m,4), Ui.dp(m,6));
    Button delBtn = new Button(m);
    delBtn.setText(m.getString(R.string.delete_biz)); delBtn.setAllCaps(false); delBtn.setTextSize(12);
    delBtn.setTextColor(0xFFB3261E); delBtn.setBackground(Ui.pill(0xFFFCEAEA, Ui.dp(m,14)));
    delBtn.setPadding(Ui.dp(m,4), Ui.dp(m,6), Ui.dp(m,4), Ui.dp(m,6));
    LinearLayout.LayoutParams half1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    half1.rightMargin = Ui.dp(m,6);
    detailBtn.setLayoutParams(half1);
    LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    half2.rightMargin = Ui.dp(m,6);
    expBtn.setLayoutParams(half2);
    delBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    row2.addView(detailBtn); row2.addView(expBtn); row2.addView(delBtn);
    row2.setPadding(0, Ui.dp(m,6), 0, 0);
    card.addView(row2);

    TextView detail = new TextView(m);
    detail.setText(m.modelDetail(task));
    detail.setTextSize(9);
    detail.setTypeface(Typeface.MONOSPACE);
    detail.setTextColor(0xFF444A55);
    detail.setBackground(Ui.pill(0xFFFFFFFF, Ui.dp(m,8)));
    detail.setPadding(Ui.dp(m,8), Ui.dp(m,6), Ui.dp(m,8), Ui.dp(m,6));
    detail.setVisibility(View.GONE);
    detail.setOnClickListener(v -> detail.setVisibility(View.GONE));
    card.addView(detail);
    detailBtn.setOnClickListener(v ->
        detail.setVisibility(detail.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));

    expBtn.setOnClickListener(v -> {
      final File dst = new File("/sdcard/Download", "laya-litert-" + task + ".zip");
      expBtn.setText(m.getString(R.string.packing)); expBtn.setEnabled(false);
      new Thread(() -> {
        String err = null;
        try {
          dst.getParentFile().mkdirs();
          m.zipDir(m.srcDir(task), dst);
        } catch (Throwable e) {
          err = e.getMessage() != null ? e.getMessage() : e.toString();
          dst.delete();
        }
        final String ferr = err;
        m.runOnUiThread(() -> {
          expBtn.setText(m.getString(R.string.export_zip)); expBtn.setEnabled(true);
          if (ferr == null)
            new AlertDialog.Builder(m).setTitle(m.getString(R.string.export_done_title))
                .setMessage(m.getString(R.string.export_done_msg, dst.getAbsolutePath(), m.human(dst.length())))
                .setPositiveButton(m.getString(R.string.ok_btn), null).show();
          else
            new AlertDialog.Builder(m).setTitle(m.getString(R.string.export_failed_title)).setMessage(ferr).setPositiveButton(m.getString(R.string.ok_btn), null).show();
        });
      }).start();
    });

    delBtn.setOnClickListener(v -> {
      String srcPath = m.srcDir(task).getAbsolutePath();
      new AlertDialog.Builder(m)
          .setTitle(m.getString(R.string.delete_title, task))
          .setMessage(m.getString(R.string.delete_msg, srcPath, task))
          .setNegativeButton(m.getString(R.string.cancel), null)
          .setPositiveButton(m.getString(R.string.delete_btn), (d, w) -> {
            com.laya.DecisionCore.unload(m, task);
            new Thread(() -> {
              m.deleteQuiet(m.srcDir(task).getParentFile());
              m.deleteQuiet(new File(m.getFilesDir(), "laya-" + task));
              m.runOnUiThread(() -> rebuildBizList());
            }).start();
          }).show();
    });

    return card;
  }

  /** 局部刷新:仅重建业务卡片列表(替代整页 setTab(3) 重建,避免闪烁) */
  private void rebuildBizList() {
    m.refreshTasks();
    if (bizListPanel == null) return;
    bizListPanel.removeAllViews();
    for (int i = 0; i < m.taskIds.size(); i++) bizListPanel.addView(bizCard(m.taskIds.get(i), m.taskLabels.get(i)));
  }
}
