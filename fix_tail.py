NL = chr(10)
BSN = chr(92) + 'n'
p = '/data/data/com.termux/files/home/laya-test/bench3.c'
s = open(p).read()
start = s.find('  double t0 = now_ms();' + NL + '  LiteRtStatus rs = LiteRtRunCompiledModel(cm_main, 0, 3, mins_in_unused_marker')
assert start > 0, 'tail marker not found'
tail = ('  double t0 = now_ms();' + NL +
'  LiteRtStatus rs = LiteRtRunCompiledModel(cm_main, 0, 3, mins_in, nout, mouts);' + NL +
'  if (rs != kLiteRtStatusOk) {' + NL +
'    printf("first invoke failed %d - retry with padded host output buffers x%d' + BSN + '", (int)rs, B);' + NL +
'    for (int i = 0; i < nout; i++) mouts[i] = mkbuf_pad(env, msig, i, 1, B);' + NL +
'    t0 = now_ms();' + NL +
'    rs = LiteRtRunCompiledModel(cm_main, 0, 3, mins_in, nout, mouts);' + NL +
'    CHECK(rs);' + NL +
'  }' + NL +
'  printf("first run: %.0f ms' + BSN + '", now_ms()-t0);' + NL +
'  double total = 0, best = 1e9;' + NL +
'  for (int r = 0; r < runs; r++) {' + NL +
'    double a = now_ms();' + NL +
'    CHECK(LiteRtRunCompiledModel(cm_main, 0, 3, mins_in, nout, mouts));' + NL +
'    for (int i = 0; i < nout; i++) {' + NL +
'      void* pv;' + NL +
'      if (LiteRtLockTensorBuffer(mouts[i], &pv, kLiteRtTensorBufferLockModeRead) == kLiteRtStatusOk)' + NL +
'        LiteRtUnlockTensorBuffer(mouts[i]);' + NL +
'    }' + NL +
'    double dt = now_ms()-a;' + NL +
'    total += dt; if (dt < best) best = dt;' + NL +
'    printf("  run %2d: %.1f ms' + BSN + '", r, dt);' + NL +
'  }' + NL +
'  printf("main graph: avg %.1f ms | best %.1f ms (%d runs, batch=%d)' + BSN + '", total/runs, best, runs, B);' + NL +
'  return 0;' + NL +
'}')
s = s[:start] + tail
open(p, 'w').write(s)
print('tail fixed')
