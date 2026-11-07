using System;
using System.IO;
using System.Linq;
using System.Text;
using DsfDesktop.Wow;

namespace DsfDesktop.Tests
{
    /// <summary>companion-only mode's game kit: the CASC folder skeleton the Thor's art harvest opens</summary>
    public static class GameKitTests
    {
        /// <summary>lookup3's own published vectors (hashlittle("", 0, 0) and "Four score and seven years ago")</summary>
        [Test]
        public static void HashLittleMatchesLookup3()
        {
            A.Eq(0xdeadbeefu, GameKit.HashLittle(new byte[0], 0, 0, 0));
            var s = Encoding.ASCII.GetBytes("Four score and seven years ago");
            A.Eq(0x17770551u, GameKit.HashLittle(s, 0, s.Length, 0));
            A.Eq(0xcd628161u, GameKit.HashLittle(s, 0, s.Length, 1));
        }

        /// <summary>the header block of a real bucket index (this PC's game, when installed) checks with our hash</summary>
        [Test]
        public static void RealIndexHeadersCheck()
        {
            var dir = @"C:\Program Files (x86)\World of Warcraft\Data\data";
            if (!Directory.Exists(dir)) return;
            foreach (var f in Directory.GetFiles(dir, "*.idx").Take(4))
            {
                var b = GameKit.ReadShared(f);
                A.Eq(BitConverter.ToUInt32(b, 4), GameKit.HashLittle(b, 8, (int)BitConverter.ToUInt32(b, 0), 0), Path.GetFileName(f));
            }
        }

        [Test]
        public static void EmptyIndexIsWellFormed()
        {
            var b = GameKit.EmptyIndex(0x0d);
            A.Eq(16u, BitConverter.ToUInt32(b, 0));
            A.Eq(BitConverter.ToUInt32(b, 4), GameKit.HashLittle(b, 8, 16, 0));
            A.Eq((byte)7, b[8]); A.Eq((byte)0x0d, b[10]); A.Eq((byte)9, b[14]);
            A.Eq(18u, BitConverter.ToUInt32(b, 32));
            A.Eq(BitConverter.ToUInt32(b, 36), GameKit.HashLittle(b, 40, 18, 0));
            A.Eq(58, b.Length);
        }

        [Test]
        public static void KitHasTheBuildInfoItsConfigsAndSixteenIndexes()
        {
            using (var t = new Tmp())
            {
                const string bk = "6ca8f0e6eae877c8346388d55601a1bc", ck = "6aee492112bf17a47b7aae8e30447df2";
                File.WriteAllText(t.P(".build.info"), "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Product!STRING:0\nus|1|" + bk + "|" + ck + "|wowf\n");
                foreach (var k in new[] { bk, ck })
                {
                    Directory.CreateDirectory(t.P("Data", "config", k.Substring(0, 2), k.Substring(2, 2)));
                    File.WriteAllText(t.P("Data", "config", k.Substring(0, 2), k.Substring(2, 2), k), "x");
                }
                var kit = GameKit.Make(t.Dir);
                A.True(kit.ContainsKey(".build.info") && kit.ContainsKey(GameKit.Streamed));
                A.True(kit.ContainsKey("Data/config/6c/a8/" + bk) && kit.ContainsKey("Data/config/6a/ee/" + ck));
                A.Eq(16, kit.Keys.Count(k => k.StartsWith("Data/data/") && k.EndsWith(".idx")));
                A.True(kit.ContainsKey("Data/data/0f00000001.idx"));
            }
        }
    }
}
