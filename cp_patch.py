NL = chr(10)
p = '/data/data/com.termux/files/home/laya-test/bench3.c'
s = open(p).read()
if 'setvbuf(stdout' not in s:
    i = s.find('int main(int argc, char** argv) {')
    assert i > 0
    j = i + len('int main(int argc, char** argv) {')
    s = s[:j] + NL + '  setvbuf(stdout, NULL, _IONBF, 0);' + s[j:]
# stderr checkpoints after buffer creation and first run
k = s.find('printf("first run: %.0f ms')
if k > 0 and 'CP3' not in s:
    # insert CP3 before the first-run invoke (approximate: before its t0 line)
    i = s.rfind('  double t0 = now_ms();', 0, k)
    s = s[:i] + 'fprintf(stderr, "CP3 buffers ready' + chr(92) + 'n");' + NL + s[i:]
open(p, 'w').write(s)
print('ok')
