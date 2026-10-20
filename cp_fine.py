NL = chr(10)
p = '/data/data/com.termux/files/home/laya-test/bench3.c'
s = open(p).read()

def after(anchor, cp):
    global s
    i = s.find(anchor)
    assert i > 0, anchor[:40]
    j = i + len(anchor)
    s = s[:j] + NL + '  fprintf(stderr, "' + cp + chr(92) + 'n");' + s[j:]

def before(anchor, cp):
    global s
    i = s.find(anchor)
    assert i > 0, anchor[:40]
    s = s[:i] + '  fprintf(stderr, "' + cp + chr(92) + 'n");' + NL + s[i:]

after('LiteRtTensorBuffer in1 = make_buf2(env, cm_main, msig, 1, 0);', 'CP-B1')
after('LiteRtTensorBuffer in2 = make_buf2(env, cm_main, msig, 2, 0);', 'CP-B2')
after('LiteRtTensorBuffer out0 = make_buf2(env, cm_main, msig, 0, 1);', 'CP-B3')
if 'LiteRtTensorBuffer out1 = make_buf2' in s:
    after('LiteRtTensorBuffer out1 = make_buf2(env, cm_main, msig, 1, 1);', 'CP-B4')
before('fill(in0, e_elems*4, 0.1f); fill(in1, m_elems*4, 1.0f); fill(in2, q_elems*4, 1.0f);', 'CP-FILL')
i = s.find('double t0 = now_ms();')
assert i > 0
s = s[:i] + '  fprintf(stderr, "CP-INVOKE' + chr(92) + 'n");' + NL + s[i:]
open(p, 'w').write(s)
print('fine checkpoints added')
