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
import org.json.JSONArray;
import org.json.JSONObject;

/** ④ 系统页(从 MainActivity 拆出):语言切换 / 模型包上传 / 业务卡片管理 / 图表子页 */
class SystemPage {
  private final MainActivity m;
  private LinearLayout bizListPanel;
  private volatile String downloadingBiz = null; // 单并发下载

  /** 下载变体:NPU 优先(MTK),否则 GPU */
  private String dlVariant() {
    return com.laya.DecisionCore.mtkNpuAvailable(m) ? "npu" : "gpu";
  }
  private final java.util.HashMap<String, TextView> dlStatus = new java.util.HashMap<>();
  private org.json.JSONObject repoTree;          // myjev 文件树缓存(算各变体包大小)
  private boolean repoTreeLoading = false;
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

    // 语言切换已并入标题栏「⋮」菜单(与外观同面板),此处不再重复
    // ---- 手动上传模型包输入界面 ----
    LinearLayout up = new LinearLayout(m);
    up.setOrientation(LinearLayout.VERTICAL);
    up.setBackground(Ui.pill(Ui.c(m, 0xFFF0F4FF, 0xFF1C2534), Ui.dp(m,10)));
    up.setPadding(Ui.dp(m,10), Ui.dp(m,8), Ui.dp(m,10), Ui.dp(m,10));
    LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    ulp.bottomMargin = Ui.dp(m,10);
    up.setLayoutParams(ulp);
    TextView upHead = new TextView(m);
    upHead.setText(m.getString(R.string.upload_head)); upHead.setTextSize(14); upHead.setTypeface(Typeface.DEFAULT_BOLD); upHead.setTextColor(Ui.c(m, 0xFF1A2B4C, 0xFFCFD8E6));
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
    upStatus.setTextSize(11); upStatus.setTextColor(Ui.c(m, 0xFF66707E, 0xFF8A93A0));
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

    // ---- 模型包下载已并入业务卡片(未安装 → ⬇ 下载;已安装 → 删除/加载) ----

    sysView = new TextView(m);
    sysView.setText(com.laya.DecisionCore.backendInfo(m) + m.getString(R.string.sys_tail));
    sysView.setTextSize(13); sysView.setTextColor(Ui.c(m, 0xFF444A55, 0xFFB8C0CC));
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
          chips[j].setTextColor(j == k ? Color.WHITE : Ui.c(m, 0xFF1A2B4C, 0xFFCFD8E6));
          chips[j].setBackground(Ui.pill(j == k ? m.PRIMARY : Ui.c(m, 0xFFE7EAF2, 0xFF2A3040), Ui.dp(m,12)));
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

  // ---- 模型包下载 ----
  private static final String[][] DL_BIZS = {
      {"ticket", "biz_ticket"}, {"ugc", "biz_ugc"}, {"agent", "biz_agent"},
      {"risk", "biz_risk"}, {"news", "biz_news"}, {"multi", "biz_multi"},
  };
  private static String pkgDirOf(String biz) {
    return "/sdcard/models/" + ("multi".equals(biz) ? "laya-litert" : "laya-litert-" + biz) + "/phone";
  }
  private static boolean pkgInstalled(String biz) {
    File pkg = new File(pkgDirOf(biz));
    return pkg.isDirectory() && (new File(pkg, "laya_ml_s256_embeds_npu.tflite").isFile()
        || new File(pkg, "laya_ml_s256_embeds_wfp16.tflite").isFile()
        || new File(pkg, "laya_ml_s256_embeds.tflite").isFile());
  }
  private int resId(String name) {
    try { return R.string.class.getField(name).getInt(null); } catch (Exception e) { return 0; }
  }

  /** 下载业务包:variant=npu/gpu → base+变体 → phone/;variant=cpu → int8 ONNX → cpu/(ORT 服务用) */
  private void downloadBiz(String biz, String variant) {
    if (downloadingBiz != null) return;
    downloadingBiz = biz + ":" + variant;
    rebuildBizList(); // 业务卡片切换为下载态
    new Thread(() -> {
      String err = null;
      try {
        String pfxBase = biz + "/base/";
        String pfxVar = biz + "/" + variant + "/";
        JSONArray all = ModelDownloader.listFiles();
        java.util.ArrayList<JSONObject> picks = new java.util.ArrayList<>();
        long total = 0;
        for (int i = 0; i < all.length(); i++) {
          JSONObject f = all.optJSONObject(i); if (f == null) continue;
          String p = f.optString("Path");
          if (!f.optString("Type").equals("blob")) continue;
          if (p.startsWith(pfxBase) || p.startsWith(pfxVar)) { picks.add(f); total += f.optLong("Size", 0); }
        }
        if (picks.isEmpty()) err = m.getString(R.string.dl_none, biz + "/" + variant);
        String pkgDir = pkgDirOf(biz);
        long done = 0; int idx = 0;
        for (JSONObject f : picks) {
          String p = f.optString("Path");
          String name = p.substring(p.lastIndexOf('/') + 1);
          long sz = f.optLong("Size", 0);
          idx++;
          final long doneSoFar = done;
          final int fIdx = idx, fTotal = picks.size();
          final long fAll = total;
          final long fSize = sz;
          m.runOnUiThread(() -> { TextView tv = dlStatus.get(biz); if (tv != null)
              tv.setText(m.getString(R.string.dl_progress, fIdx, fTotal, (doneSoFar + sz) / 1048576 + "/" + fAll / 1048576 + "MB")); });
          ModelDownloader.download(p, new File(pkgDir, name), fSize, prog -> {
            long cur = doneSoFar + parseProg(prog);
            String s2 = m.getString(R.string.dl_progress, fIdx, fTotal, cur / 1048576 + "/" + fAll / 1048576 + "MB");
            m.runOnUiThread(() -> { TextView tv = dlStatus.get(biz); if (tv != null) tv.setText(s2); });
          });
        }
      } catch (Throwable t) {
        err = t.getMessage() != null ? t.getMessage() : t.toString();
      }
      final String ferr = err;
      m.runOnUiThread(() -> {
        downloadingBiz = null;
        rebuildBizList();
        if (ferr != null && upStatus != null)
          upStatus.setText("❌ " + m.getString(R.string.dl_fail, biz, ferr));
      });
    }).start();
  }
  private static long parseProg(String s) { // "… 12MB" → 12<<20
    try { int b = s.indexOf("… "); int e = s.indexOf("MB");
      if (b >= 0 && e > b) return Long.parseLong(s.substring(b + 2, e).trim()) << 20;
    } catch (Exception ignored) {}
    return 0;
  }

  /** 单个业务卡片(加载态/操作按钮/详情/导出/删除) */
  private LinearLayout bizCard(String task, String label) {
    boolean loaded = new File(m.getFilesDir(), "laya-" + task + "/laya_ml_s256_embeds_wfp16.tflite").isFile()
        || new File(m.getFilesDir(), "laya-" + task + "/laya_ml_s256_embeds_npu.tflite").isFile();

    LinearLayout card = new LinearLayout(m);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackground(Ui.pill(loaded ? Ui.c(m, 0xFFEAF3FF, 0xFF1E2B44) : Ui.c(m, 0xFFF7F8FA, 0xFF232833), Ui.dp(m,10)));
    card.setPadding(Ui.dp(m,10), Ui.dp(m,8), Ui.dp(m,10), Ui.dp(m,10));
    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    clp.bottomMargin = Ui.dp(m,8);
    card.setLayoutParams(clp);

    TextView head = new TextView(m);
    head.setText((loaded ? "✅ " : "📦 ") + label + "  [" + task + "]");
    head.setTextSize(14); head.setTypeface(Typeface.DEFAULT_BOLD); head.setTextColor(Ui.c(m, 0xFF1A2B4C, 0xFFCFD8E6));
    card.addView(head);
    TextView st = new TextView(m);
    st.setText(m.getString(loaded ? R.string.loaded_state : R.string.not_loaded_state));
    st.setTextSize(11); st.setTextColor(Ui.c(m, 0xFF66707E, 0xFF8A93A0));
    st.setPadding(0, Ui.dp(m,2), 0, Ui.dp(m,4));
    card.addView(st);

    // 单按钮动态切换:未加载=加载(装入 app);已加载=卸载(释放空间)
    Button toggle = new Button(m);
    toggle.setText(m.getString(loaded ? R.string.uninstall : R.string.load_btn));
    toggle.setAllCaps(false); toggle.setTextSize(12);
    toggle.setTextColor(loaded ? Ui.c(m, 0xFF444A55, 0xFFB8C0CC) : Color.WHITE);
    toggle.setBackground(Ui.pill(loaded ? Ui.c(m, m.CHIP_OFF, 0xFF2A3040) : m.PRIMARY, Ui.dp(m,14)));
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
    detailBtn.setTextColor(Ui.c(m, 0xFF444A55, 0xFFB8C0CC)); detailBtn.setBackground(Ui.pill(Ui.c(m, m.CHIP_OFF, 0xFF2A3040), Ui.dp(m,14)));
    detailBtn.setPadding(Ui.dp(m,4), Ui.dp(m,6), Ui.dp(m,4), Ui.dp(m,6));
    Button expBtn = new Button(m);
    expBtn.setText(m.getString(R.string.export_zip)); expBtn.setAllCaps(false); expBtn.setTextSize(12);
    expBtn.setTextColor(Ui.c(m, 0xFF444A55, 0xFFB8C0CC)); expBtn.setBackground(Ui.pill(Ui.c(m, m.CHIP_OFF, 0xFF2A3040), Ui.dp(m,14)));
    expBtn.setPadding(Ui.dp(m,4), Ui.dp(m,6), Ui.dp(m,4), Ui.dp(m,6));
    Button delBtn = new Button(m);
    delBtn.setText(m.getString(R.string.delete_biz)); delBtn.setAllCaps(false); delBtn.setTextSize(12);
    delBtn.setTextColor(Ui.c(m, 0xFFB3261E, 0xFFFF8A80)); delBtn.setBackground(Ui.pill(Ui.c(m, 0xFFFCEAEA, 0xFF3A2226), Ui.dp(m,14)));
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
    detail.setTextColor(Ui.c(m, 0xFF444A55, 0xFFB8C0CC));
    detail.setBackground(Ui.pill(Ui.c(m, 0xFFFFFFFF, 0xFF1E232B), Ui.dp(m,8)));
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

  /** 局部刷新:6 固定业务(未安装 → 下载卡片)+ 自定义业务;下载与删除按钮随包状态合并切换 */
  private void rebuildBizList() {
    m.refreshTasks();
    if (bizListPanel == null) return;
    bizListPanel.removeAllViews();
    dlStatus.clear();
    java.util.ArrayList<String> shown = new java.util.ArrayList<>();
    for (String[] b : DL_BIZS) {
      shown.add(b[0]);
      int idx = m.taskIds.indexOf(b[0]);
      if (idx >= 0) {
        bizListPanel.addView(bizCard(b[0], m.taskLabels.get(idx)));
        if (downloadingBiz != null && downloadingBiz.startsWith(b[0] + ":")) {
          // 重建后恢复下载进度显示
          bizListPanel.addView(downloadRow(b[0], m.getString(R.string.dl_progress, 0, 0, "…")));
        }
      } else {
        bizListPanel.addView(dlCard(b[0], resId(b[1])));
      }
    }
    for (int i = 0; i < m.taskIds.size(); i++) {
      if (!shown.contains(m.taskIds.get(i)))
        bizListPanel.addView(bizCard(m.taskIds.get(i), m.taskLabels.get(i)));
    }
  }

  /** 未安装业务的下载卡片:名称 + 三变体下载按钮(NPU/GPU 推荐 + CPU)+ 进度行 */
  private LinearLayout dlCard(String biz, int labelRes) {
    LinearLayout card = new LinearLayout(m);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackground(Ui.pill(Ui.c(m, 0xFFF7F8FA, 0xFF232833), Ui.dp(m,10)));
    card.setPadding(Ui.dp(m,10), Ui.dp(m,8), Ui.dp(m,10), Ui.dp(m,10));
    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    clp.bottomMargin = Ui.dp(m,8); card.setLayoutParams(clp);

    TextView head = new TextView(m);
    head.setText((downloadingBiz != null && downloadingBiz.startsWith(biz + ":") ? "⏳ " : "⬇ ") + m.getString(labelRes) + "  [" + biz + "]");
    head.setTextSize(14); head.setTypeface(Typeface.DEFAULT_BOLD);
    head.setTextColor(Ui.c(m, 0xFF1A2B4C, 0xFFCFD8E6));
    card.addView(head);
    TextView st = new TextView(m);
    st.setTextSize(11); st.setTextColor(Ui.c(m, 0xFF66707E, 0xFF8A93A0));
    st.setText(downloadingBiz != null && downloadingBiz.startsWith(biz + ":") ? m.getString(R.string.dl_progress, 0, 0, "…") : m.getString(R.string.dl_missing));
    dlStatus.put(biz, st);
    card.addView(st);

    if (!biz.equals(downloadingBiz)) {
      ensureRepoTree();
      if (repoTree != null) {
        LinearLayout btnRow = new LinearLayout(m);
        String[][] vs = {{"npu", "NPU"}, {"gpu", "GPU"}};
        for (String[] v : vs) {
          long size = variantSize(biz, v[0]);
          if (size <= 0) continue; // 无包不显示(如未上传的 cpu 变体)
          String rec = v[0].equals(dlVariant()) ? "★" : ""; // 仅标本机推荐变体
          LinearLayout col = new LinearLayout(m);
          col.setOrientation(LinearLayout.VERTICAL);
          col.setGravity(android.view.Gravity.CENTER);
          Button btn = new Button(m);
          btn.setText(v[1] + (rec.isEmpty() ? "" : "\n" + rec));
          btn.setAllCaps(false); btn.setTextSize(11);
          btn.setMinHeight(0); btn.setMinimumHeight(0); btn.setMinimumWidth(0);
          btn.setPadding(Ui.dp(m,4), Ui.dp(m,6), Ui.dp(m,4), Ui.dp(m,6));
          btn.setTextColor(Color.WHITE); btn.setBackground(Ui.pill(m.PRIMARY, Ui.dp(m,10)));
          btn.setOnClickListener(v2 -> downloadBiz(biz, v[0]));
          col.addView(btn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
          TextView sz = new TextView(m);
          sz.setText(size / 1048576 + "MB");
          sz.setTextSize(10); sz.setGravity(android.view.Gravity.CENTER);
          sz.setTextColor(Ui.c(m, 0xFF66707E, 0xFF8A93A0));
          col.addView(sz, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
          btnRow.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(btnRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
      }
    }
    return card;
  }

  /** 拉取 myjev 文件树(一次),完成后重建列表以标注各包大小 */
  private void ensureRepoTree() {
    if (repoTree != null || repoTreeLoading) return;
    repoTreeLoading = true;
    new Thread(() -> {
      try {
        JSONArray files = ModelDownloader.listFiles();
        repoTree = new org.json.JSONObject().put("Files", files);
        m.runOnUiThread(() -> { repoTreeLoading = false; rebuildBizList(); });
      } catch (Throwable t) { repoTreeLoading = false; }
    }).start();
  }

  /** 变体包大小(字节;树未加载/无文件返回 0);phone=整包,其余=base+变体 */
  private long variantSize(String biz, String variant) {
    if (repoTree == null) return 0;
    JSONArray files = repoTree.optJSONArray("Files"); if (files == null) return 0;
    long sum = 0; boolean any = false;
    for (String pfx : new String[]{biz + "/base/", biz + "/" + variant + "/"}) {
      for (int i = 0; i < files.length(); i++) {
        JSONObject f = files.optJSONObject(i); if (f == null) continue;
        String p = f.optString("Path");
        if (f.optString("Type").equals("blob") && p.startsWith(pfx)) { sum += f.optLong("Size", 0); any = true; }
      }
    }
    return any ? sum : 0;
  }

  /** 下载中的行(卡片重建后恢复进度显示) */
  private LinearLayout downloadRow(String biz, String text) {
    LinearLayout row = new LinearLayout(m);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setPadding(Ui.dp(m,10), 0, Ui.dp(m,10), Ui.dp(m,8));
    TextView t = new TextView(m);
    t.setTextSize(11); t.setTextColor(Ui.c(m, 0xFF66707E, 0xFF8A93A0));
    dlStatus.put(biz, t);
    t.setText(text);
    row.addView(t);
    return row;
  }
}
