using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

namespace KomaComic.Windows
{
    internal sealed class TranslationTokens
    {
        public long Requests { get; set; }
        public long Reported { get; set; }
        public long Input { get; set; }
        public long Output { get; set; }
        public long Reasoning { get; set; }
        public long Total { get { return Input + Output; } }

        public void Add(TranslationTokens value)
        {
            Requests += value.Requests;
            Reported += value.Reported;
            Input += value.Input;
            Output += value.Output;
            Reasoning += value.Reasoning;
        }
    }

    internal sealed class TranslationUsageDay
    {
        public string Day { get; set; }
        public string Model { get; set; }
        public TranslationTokens Tokens { get; set; }
    }

    internal static class TranslationUsage
    {
        private static readonly object Gate = new object();

        public static string Day(DateTime date) { return date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture); }

        public static void Record(string directory, string model, TranslationTokens tokens)
        {
            if (directory == null) return;
            lock (Gate)
            {
                string path = Path.Combine(directory, "translation_usage.json");
                string day = Day(DateTime.Today);
                List<TranslationUsageDay> rows = Read(path);
                TranslationUsageDay current = rows.Find(row => row.Day == day && row.Model == model);
                if (current == null)
                {
                    current = new TranslationUsageDay { Day = day, Model = model, Tokens = new TranslationTokens() };
                    rows.Add(current);
                }
                current.Tokens.Add(tokens);
                string pending = path + ".tmp";
                try
                {
                    File.WriteAllText(pending, JsonUtil.CreateSerializer().Serialize(rows), Encoding.UTF8);
                    if (File.Exists(path)) File.Replace(pending, path, null);
                    else File.Move(pending, path);
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }

        public static List<TranslationUsageDay> Recent(string directory)
        {
            string start = Day(DateTime.Today.AddDays(-6));
            string end = Day(DateTime.Today);
            lock (Gate)
                return Read(Path.Combine(directory, "translation_usage.json"))
                    .Where(row => string.CompareOrdinal(row.Day, start) >= 0 && string.CompareOrdinal(row.Day, end) <= 0)
                    .ToList();
        }

        public static TranslationTokens Sum(IEnumerable<TranslationUsageDay> rows)
        {
            var sum = new TranslationTokens();
            foreach (TranslationUsageDay row in rows) sum.Add(row.Tokens);
            return sum;
        }

        private static List<TranslationUsageDay> Read(string path)
        {
            try
            {
                if (File.Exists(path))
                    return JsonUtil.CreateSerializer().Deserialize<List<TranslationUsageDay>>(File.ReadAllText(path))
                        .Where(row => row != null && row.Tokens != null).ToList();
            }
            catch (Exception) { }
            return new List<TranslationUsageDay>();
        }
    }
}
