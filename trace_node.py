#!/usr/bin/env python3
# minimal protobuf wire parser to trace ONNX graph nodes around a target node
import sys

def read_varint(buf, i):
    r = 0; s = 0
    while True:
        b = buf[i]; i += 1
        r |= (b & 0x7f) << s
        if not b & 0x80: return r, i
        s += 7

def parse_fields(buf):
    i = 0; out = []
    while i < len(buf):
        tag, i = read_varint(buf, i)
        fnum, wt = tag >> 3, tag & 7
        if wt == 0:
            v, i = read_varint(buf, i); out.append((fnum, wt, v))
        elif wt == 2:
            l, i = read_varint(buf, i); out.append((fnum, wt, buf[i:i+l])); i += l
        elif wt == 5:
            out.append((fnum, wt, buf[i:i+4])); i += 4
        elif wt == 1:
            out.append((fnum, wt, buf[i:i+8])); i += 8
        else:
            raise ValueError(f"wt={wt}")
    return out

def parse_node(buf):
    ins, outs, name, op = [], [], b'', b''
    for fnum, wt, v in parse_fields(buf):
        if fnum == 1 and wt == 2: ins.append(v.decode())
        elif fnum == 2 and wt == 2: outs.append(v.decode())
        elif fnum == 3 and wt == 2: name = v
        elif fnum == 4 and wt == 2: op = v
    return {'in': ins, 'out': outs, 'name': name.decode(), 'op': op.decode()}

model = open(sys.argv[1], 'rb').read()
nodes = {}
producer = {}   # tensor name -> node
for fnum, wt, v in parse_fields(model):
    if fnum == 7 and wt == 2:  # GraphProto.graph? (ModelProto.graph = field 7)
        for f2, w2, v2 in parse_fields(v):
            if f2 == 1 and w2 == 2:  # GraphProto.node
                n = parse_node(v2)
                nodes[n['name']] = n
                for o in n['out']: producer[o] = n['name']

target = sys.argv[2] if len(sys.argv) > 2 else 'node_view_68'
cur = target
for depth in range(6):
    n = nodes.get(cur)
    if n is None:
        print(f'{cur}: NOT A NODE (initializer/graph input)'); break
    print(f"{'  '*depth}{n['name']} [{n['op']}]  in={n['in']} out={n['out']}")
    if not n['in']: break
    cur = producer.get(n['in'][0], n['in'][0])
