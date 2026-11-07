using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace DsfDesktop.Wow
{
    /// <summary>
    /// COMPANION-ONLY MODE's GAME ART: the companion builds its pictures (icons, UI kit, maps) from the game's own files with CascLib
    /// (ArtHarvest), normally from a WoW Forever folder on the Thor. A Thor beside Apollo has none, so Desktop hands it the few small
    /// files that make a CASC folder CascLib will open, taken from this PC's install:
    ///  - .build.info (the build the PC runs, its CDN hosts) and the build / CDN configs it names (Data/config/xx/yy/key);
    ///  - 16 EMPTY bucket indexes (Data/data/XX00000001.idx, one entry no key ever matches), so every game file reads as "not on this
    ///    device" and the companion fetches just the ones its art needs from Blizzard's public CDN, as it does for a direct install;
    ///  - the .ds-streamed mark (DataStaleness.STREAMED), so the build's VFS tables come from the CDN too.
    /// Nothing of the PC's data archives is sent, and nothing is ever written to the PC's game folder.
    /// </summary>
    public static class GameKit
    {
        public const string Streamed = ".ds-streamed";

        /// <summary>published path -> bytes, or throws with a reason a person can read</summary>
        public static Dictionary<string, byte[]> Make(string wowRoot)
        {
            if (wowRoot == null) throw new InvalidOperationException("the WoW folder isn't known");
            var buildInfo = Path.Combine(wowRoot, ".build.info");
            if (!File.Exists(buildInfo)) throw new InvalidOperationException("no .build.info in " + wowRoot);
            var info = ReadShared(buildInfo);
            var files = new Dictionary<string, byte[]> { [".build.info"] = info, [Streamed] = new byte[0] };
            foreach (var key in ConfigKeys(Encoding.UTF8.GetString(info)))
            {
                var rel = "Data/config/" + key.Substring(0, 2) + "/" + key.Substring(2, 2) + "/" + key;
                var f = Path.Combine(wowRoot, rel.Replace('/', Path.DirectorySeparatorChar));
                if (File.Exists(f)) files[rel] = ReadShared(f);
            }
            if (!files.Keys.Any(k => k.StartsWith("Data/config/"))) throw new InvalidOperationException("the build's configs are missing from " + wowRoot);
            for (int bucket = 0; bucket < 16; bucket++) files["Data/data/" + bucket.ToString("x2") + "00000001.idx"] = EmptyIndex(bucket);
            return files;
        }

        /// <summary>a file the running game may hold open (read beside it, never locking it)</summary>
        public static byte[] ReadShared(string path)
        {
            using (var s = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            using (var m = new MemoryStream()) { s.CopyTo(m); return m.ToArray(); }
        }

        /// <summary>the Build Key and CDN Key of every .build.info row (lower-case hex, 32 characters)</summary>
        public static IEnumerable<string> ConfigKeys(string buildInfo)
        {
            var lines = buildInfo.Split('\n').Select(l => l.TrimEnd('\r')).Where(l => l.Length > 0).ToList();
            if (lines.Count < 2) yield break;
            var cols = lines[0].Split('|').Select(c => c.Split('!')[0]).ToList();
            int bk = cols.IndexOf("Build Key"), ck = cols.IndexOf("CDN Key");
            foreach (var row in lines.Skip(1).Select(l => l.Split('|')))
                foreach (var i in new[] { bk, ck })
                    if (i >= 0 && i < row.Length && row[i].Length == 32 && row[i].All(Uri.IsHexDigit)) yield return row[i].ToLowerInvariant();
        }

        /// <summary>
        /// A version-7 bucket index (CascLib's CaptureIndexHeader_V2 / CaptureGuardedBlock2) holding ONE entry whose key is all 0xFF:
        /// a valid file CascLib loads (an empty block is refused), whose single entry no real encoded key matches.
        /// </summary>
        public static byte[] EmptyIndex(int bucket)
        {
            var header = new byte[16];
            header[0] = 7;                  // Revision (u16)
            header[2] = (byte)bucket;       // BucketIndex
            header[3] = 0;                  // Flags
            header[4] = 4;                  // SpanSizeBytes
            header[5] = 5;                  // SpanOffsetBytes
            header[6] = 9;                  // KeyBytes
            header[7] = 30;                 // SegmentBits
            BitConverter.GetBytes(0x000000FFC0000000UL).CopyTo(header, 8);   // MaxFileOffset, as the game writes it
            var entry = new byte[18];
            for (int i = 0; i < 9; i++) entry[i] = 0xFF;
            var o = new MemoryStream();
            var w = new BinaryWriter(o);
            w.Write((uint)header.Length); w.Write(HashLittle(header, 0, header.Length, 0)); w.Write(header);
            w.Write(new byte[8]);           // the header's padding
            // the block hash as Blizzget writes it (CascLib accepts it besides the downloader's hashlittle2 form)
            w.Write((uint)entry.Length); w.Write(HashLittle(entry, 0, entry.Length, 0)); w.Write(entry);
            w.Flush();
            return o.ToArray();
        }

        // ---------------------------------------------------------------- Bob Jenkins' lookup3 hashlittle (CascLib's jenkins/lookup3.c)
        static uint Rot(uint x, int k) => (x << k) | (x >> (32 - k));

        public static uint HashLittle(byte[] k, int at, int length, uint initval)
        {
            uint a, b, c;
            a = b = c = 0xdeadbeef + (uint)length + initval;
            while (length > 12)
            {
                a += BitConverter.ToUInt32(k, at); b += BitConverter.ToUInt32(k, at + 4); c += BitConverter.ToUInt32(k, at + 8);
                a -= c; a ^= Rot(c, 4); c += b;
                b -= a; b ^= Rot(a, 6); a += c;
                c -= b; c ^= Rot(b, 8); b += a;
                a -= c; a ^= Rot(c, 16); c += b;
                b -= a; b ^= Rot(a, 19); a += c;
                c -= b; c ^= Rot(b, 4); b += a;
                length -= 12; at += 12;
            }
            switch (length)
            {
                case 12: c += (uint)k[at + 11] << 24; goto case 11;
                case 11: c += (uint)k[at + 10] << 16; goto case 10;
                case 10: c += (uint)k[at + 9] << 8; goto case 9;
                case 9: c += k[at + 8]; goto case 8;
                case 8: b += (uint)k[at + 7] << 24; goto case 7;
                case 7: b += (uint)k[at + 6] << 16; goto case 6;
                case 6: b += (uint)k[at + 5] << 8; goto case 5;
                case 5: b += k[at + 4]; goto case 4;
                case 4: a += (uint)k[at + 3] << 24; goto case 3;
                case 3: a += (uint)k[at + 2] << 16; goto case 2;
                case 2: a += (uint)k[at + 1] << 8; goto case 1;
                case 1: a += k[at]; break;
                case 0: return c;
            }
            c ^= b; c -= Rot(b, 14);
            a ^= c; a -= Rot(c, 11);
            b ^= a; b -= Rot(a, 25);
            c ^= b; c -= Rot(b, 16);
            a ^= c; a -= Rot(c, 4);
            b ^= a; b -= Rot(a, 14);
            c ^= b; c -= Rot(b, 24);
            return c;
        }
    }
}
