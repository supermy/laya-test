NL = chr(10)
p = '/data/data/com.termux/files/home/laya-test/bench3.c'
s = open(p).read()
old = '  int nout_main = 1, oi = 1;'
if old in s:
    # previous attempt applied; fix the broken literal \n line it created
    s = s.replace('LiteRtTensor t_dummy;\\n  LiteRtTensorBuffer mains_in', 'LiteRtTensor t_dummy;' + NL + '  LiteRtTensorBuffer mains_in')
    s = s.replace('LiteRtTensor t_dummy;' + chr(92) + 'n  LiteRtTensorBuffer mains_in', 'LiteRtTensor t_dummy;' + NL + '  LiteRtTensorBuffer mains_in')
else:
    s = s.replace('  LiteRtTensorBuffer mains_in[3] = {in0, in1, in2};',
        '  LiteRtTensor t_dummy;' + NL +
        '  int nout_main = 1, oi = 1;' + NL +
        '  while (LiteRtGetSignatureOutputTensorByIndex(msig, oi, &t_dummy) == kLiteRtStatusOk) { oi++; }' + NL +
        '  nout_main = oi;' + NL +
        '  fprintf(stderr, "signature outputs: %d' + chr(92) + 'n", nout_main);' + NL +
        '  LiteRtTensorBuffer mains_in[3] = {in0, in1, in2};', 1)
# out1 buffer must exist when nout_main==2: make it conditional-safe
if 'LiteRtTensorBuffer out1 = make_buf2' in s:
    s = s.replace('  LiteRtTensorBuffer out1 = make_buf2(env, cm_main, msig, 1, 1); // pooled_cls',
        '  LiteRtTensorBuffer out1 = (nout_main > 1 || total_elems(msig, 1, 1)) ? make_buf2(env, cm_main, msig, 1, 1) : (LiteRtTensorBuffer)NULL; // pooled_cls', 1)
open(p, 'w').write(s)
print('nout probe installed')
