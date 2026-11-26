import array
import math
import unittest

import laura_voice as lv


def tone(amp, n=lv.FRAME):
    return array.array('h', [int(amp * 32767 * math.sin(i / 5.0)) for i in range(n)]).tobytes()


class GateTest(unittest.TestCase):
    def test_lead_in_and_hang(self):
        g = lv.Gate()
        quiet, loud = tone(0.0005), tone(0.1)
        t = 0.0
        for _ in range(10):
            self.assertEqual(g.push(quiet, lv.rms16(quiet), t), [])
            t += 0.1
        out = g.push(loud, lv.rms16(loud), t)
        self.assertEqual(len(out), lv.GATE_PRE_FRAMES + 1)
        sent = 0
        for _ in range(20):
            t += 0.1
            sent += len(g.push(quiet, lv.rms16(quiet), t))
        self.assertTrue(10 <= sent <= 12)
        self.assertFalse(g.open)


class OpusTest(unittest.TestCase):
    @unittest.skipUnless(lv.Opus.load(), 'libopus missing')
    def test_round_trip(self):
        enc, dec = lv.OpusEncoder(), lv.OpusDecoder(16000)
        pcm = tone(0.3, lv.OPUS_FRAME)
        pkt = enc.encode(pcm)
        self.assertTrue(0 < len(pkt) < 200)
        self.assertEqual(len(dec.decode(pkt)), lv.OPUS_FRAME * 2)


class UrlTest(unittest.TestCase):
    def test_key_and_path(self):
        cfg = dict(lv.DEFAULTS, url='wss://h:8443', key='a b')
        c = lv.Client(cfg, None, lv.WavSink('/dev/null'))
        self.assertEqual(c.url(), 'wss://h:8443/ws?k=a+b')

    def test_pin_normalized(self):
        self.assertEqual(lv.norm_pin('SHA256/AB:cd'), 'abcd')


if __name__ == '__main__':
    unittest.main()
