#include <dlfcn.h>
#include <stdio.h>
int main(int argc, char** argv) {
  void* h = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  if (!h) { printf("dlopen FAIL: %s\n", dlerror()); return 1; }
  printf("dlopen OK: %s\n", argv[1]);
  void (*reg)(void) = dlsym(h, "LiteRtGetDispatchPluginMetadata_CApi");
  printf("metadata sym: %p\n", reg);
  return 0;
}
