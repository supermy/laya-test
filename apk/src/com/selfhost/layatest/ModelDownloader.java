package com.selfhost.layatest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

/** ModelScope 模型包下载(supermy/myjev 公有仓库):文件树列举 + 单文件流式下载 */
final class ModelDownloader {
  private ModelDownloader() {}

  static final String OWNER = "supermy";
  static final String REPO = "myjev";
  static final String API = "https://modelscope.cn/api/v1/models/" + OWNER + "/" + REPO;

  interface Progress { void update(String s); }

  /** 拉取仓库文件树(递归),返回 Files 数组;失败抛异常 */
  static JSONArray listFiles() throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(API + "/repo/files?Recursive=true").openConnection();
    c.setConnectTimeout(10_000); c.setReadTimeout(30_000);
    int code = c.getResponseCode();
    InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
    String body = read(in);
    check2(code < 400, "HTTP " + code);
    return new JSONObject(body).getJSONObject("Data").getJSONArray("Files");
  }

  /** 单文件流式下载(覆盖写),progress 收到已下载字节数;expectedSize>0 时校验防截断 */
  static void download(String path, File dst, long expectedSize, Progress progress) throws Exception {
    dst.getParentFile().mkdirs(); // 先建目标目录(phone/ 下载前不存在)
    StringBuilder spec = new StringBuilder(API + "/repo?FilePath=");
    for (String seg : path.split("/")) // 逐段 URL 编码(保留 /;防空格/中文/特殊字符断链)
      spec.append(java.net.URLEncoder.encode(seg, StandardCharsets.UTF_8.name())).append("%2F");
    spec.setLength(spec.length() - 3); // 去掉末尾多余的 %2F
    spec.append("&Revision=master");
    HttpURLConnection c = (HttpURLConnection) new URL(spec.toString()).openConnection();
    c.setConnectTimeout(10_000); c.setReadTimeout(60_000);
    int code = c.getResponseCode();
    check2(code < 400, "HTTP " + code + " " + path);
    InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
    File tmp = new File(dst.getAbsolutePath() + ".part");
    long total = 0;
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      byte[] buf = new byte[256 * 1024];
      for (int n; (n = in.read(buf)) > 0; ) {
        out.write(buf, 0, n);
        total += n;
        if (progress != null && total % (8 << 20) < buf.length) progress.update("… " + (total >> 20) + "MB");
      }
    }
    in.close();
    check2(expectedSize <= 0 || total == expectedSize,
        "截断: " + path + " 期望 " + expectedSize + "B 实得 " + total + "B");
    if (!tmp.renameTo(dst)) { // 跨文件系统 rename 失败则复制
      java.nio.file.Files.copy(tmp.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      tmp.delete();
    }
  }

  private static String read(InputStream in) throws Exception {
    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
    return bo.toString(StandardCharsets.UTF_8.name());
  }

  private static void check2(boolean b, String msg) throws Exception { if (!b) throw new Exception(msg); }
}
