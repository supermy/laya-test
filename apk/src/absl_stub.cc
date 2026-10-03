// absl_stub.cc — 只补 litert_toml_parser.cc 需要的唯一 absl 符号,
// 避免为 APK 打包整套 abseil(AAR 的 libLiteRt.so 自身静态包含 absl)。
#include <string_view>
#include <cstdlib>
#include <cstring>

namespace absl {
namespace lts_20250814 {
namespace numbers_internal {

bool safe_strto64_base(std::string_view s, long* out, int base) {
  if (s.size() == 0 || s.size() >= 64) return false;
  char buf[64];
  memcpy(buf, s.data(), s.size());
  buf[s.size()] = '\0';
  char* end = nullptr;
  errno = 0;
  long v = strtoll(buf, &end, base);
  if (end == buf) return false;
  while (end && (*end == ' ' || *end == '\t')) end++;
  if (end && *end != '\0') return false;
  *out = v;
  return true;
}

}  // namespace numbers_internal
}  // namespace lts_20250814
}  // namespace absl
