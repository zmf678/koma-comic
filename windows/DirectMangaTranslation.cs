using System;
using System.Collections;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace KomaComic.Windows
{
    internal static partial class MangaTranslation
    {
        private const string ChatPath = "/chat/completions";
        private const int MaxPreviewEdge = 2048;
        private const int MaxDirectResponse = 2 * 1024 * 1024;
        private static readonly ConcurrentDictionary<string, List<TranslatedRegion>> PendingRegions =
            new ConcurrentDictionary<string, List<TranslatedRegion>>();

        private sealed class TranslatedRegion
        {
            public Rect Bounds { get; set; }
            public string Text { get; set; }
        }

        private static Uri DirectEndpoint(Uri api)
        {
            string address = api.AbsoluteUri.TrimEnd('/');
            string path = api.AbsolutePath.TrimEnd('/');
            if (path.EndsWith(ChatPath, StringComparison.Ordinal)) return new Uri(address);
            return new Uri(address + (path.Length == 0 ? "/v1" : "") + ChatPath);
        }

        private static async Task<BitmapSource> TranslateDirectAsync(BitmapSource original, Uri endpoint, string config,
            TranslationModel model, string directory, CancellationToken token, string usageDirectory, TranslationRequestPriority priority)
        {
            string apiKey = model.DecryptKey();
            ValidateDirectKey(apiKey);

            int longest = Math.Max(original.PixelWidth, original.PixelHeight);
            BitmapSource preview = original;
            if (longest > MaxPreviewEdge)
            {
                double scale = (double)MaxPreviewEdge / longest;
                preview = new TransformedBitmap(original, new ScaleTransform(
                    Math.Max(scale, 1.0 / original.PixelWidth), Math.Max(scale, 1.0 / original.PixelHeight)));
            }
            byte[] image = EncodeBitmap(preview, new JpegBitmapEncoder { QualityLevel = 92 });
            string profile = "direct-regions-v1\n" + model.ModelId + "\n" + model.ReasoningEffort + "\n" + apiKey;
            string cache = CachePath(directory, endpoint, config, profile, image, "regions");
            List<TranslatedRegion> regions = LoadRegions(cache);
            if (regions == null)
            {
                regions = await RunTranslationRequest(priority, token, delegate
                {
                    List<TranslatedRegion> ready = LoadRegions(cache);
                    if (ready != null) return ready;
                    var result = ParseRegions(Complete(endpoint, model, apiKey, ImageContent(TargetLanguage(config), image), usageDirectory, token));
                    SaveRegionsAsync(cache, result);
                    return result;
                }).ConfigureAwait(false);
            }
            token.ThrowIfCancellationRequested();
            return regions.Count == 0 ? original : RenderDirect(original, regions);
        }

        private static List<TranslatedRegion> LoadRegions(string cache)
        {
            if (cache == null) return null;
            List<TranslatedRegion> pending;
            if (PendingRegions.TryGetValue(cache, out pending)) return pending;
            if (!File.Exists(cache)) return null;
            try
            {
                using (Stream stream = File.OpenRead(cache))
                    return DecodeRegions(Encoding.UTF8.GetString(ReadBounded(stream, MaxDirectResponse)));
            }
            catch (Exception) { return null; }
        }

        private static void SaveRegionsAsync(string cache, List<TranslatedRegion> regions)
        {
            if (cache == null || !PendingRegions.TryAdd(cache, regions)) return;
            Task.Run(delegate
            {
                try
                {
                    object[][] boxes = regions.Select(region => new object[] {
                        (int)region.Bounds.Left, (int)region.Bounds.Top, (int)region.Bounds.Right,
                        (int)region.Bounds.Bottom, region.Text
                    }).ToArray();
                    string json = JsonUtil.CreateSerializer().Serialize(new { regions = boxes });
                    SaveCache(cache, Encoding.UTF8.GetBytes(json));
                }
                finally
                {
                    List<TranslatedRegion> published;
                    PendingRegions.TryRemove(cache, out published);
                }
            });
        }

        public static Task<string[]> ListModelsAsync(string address, string apiKey, CancellationToken token)
        {
            string endpoint = DirectEndpoint(ValidateModelAddress(address)).AbsoluteUri;
            return Task.Run(delegate
            {
                var reply = ExchangeDirect(new Uri(endpoint.Substring(0, endpoint.Length - ChatPath.Length) + "/models"), apiKey, null, token);
                object data;
                var models = reply.TryGetValue("data", out data) ? data as IList : null;
                if (models == null) throw new TranslationFailure("API 未返回模型列表");
                return models.Cast<object>().Select(item => JsonUtil.GetStr(item as Dictionary<string, object>, "id", ""))
                    .Where(id => !string.IsNullOrWhiteSpace(id)).Distinct(StringComparer.Ordinal).OrderBy(id => id, StringComparer.Ordinal).ToArray();
            }, token);
        }

        public static Task<string> TranslateTextAsync(string config, TranslationModel model, string source, string usageDirectory, CancellationToken token)
        {
            ValidateModel(model);
            ValidateConfig(config);
            if (string.IsNullOrWhiteSpace(source) || source.Length > 8000) throw new ArgumentException("请输入 1 至 8000 字的原文");
            Uri endpoint = DirectEndpoint(ValidateModelAddress(model.ApiBase));
            string prompt = "Translate the source text into " + TargetLanguage(config) + ". Return only the translation.\n\nSource text:\n" + source;
            return Task.Run(() => CompletionContent(Complete(endpoint, model, model.DecryptKey(), prompt, usageDirectory, token)), token);
        }

        private static Dictionary<string, object> Complete(Uri endpoint, TranslationModel model, string apiKey, object content, string usageDirectory, CancellationToken token)
        {
            var payload = new Dictionary<string, object> {
                { "model", model.ModelId }, { "stream", false },
                { "messages", new[] { new { role = "user", content = content } } }
            };
            if (!string.IsNullOrEmpty(model.ReasoningEffort)) payload["reasoning_effort"] = model.ReasoningEffort;
            byte[] body = Encoding.UTF8.GetBytes(JsonUtil.CreateSerializer().Serialize(payload));
            var reply = ExchangeDirect(endpoint, apiKey, body, token);
            TranslationUsage.Record(usageDirectory, model.ModelId, ReadTokens(reply));
            return reply;
        }

        private static void ValidateDirectKey(string apiKey)
        {
            if (string.IsNullOrWhiteSpace(apiKey)) throw new TranslationFailure("请在漫画翻译设置中填写 API Key");
            if (apiKey.Any(ch => ch < '!' || ch > '~')) throw new TranslationFailure("API Key 格式不正确");
        }

        private static Dictionary<string, object> ExchangeDirect(Uri endpoint, string apiKey, byte[] body, CancellationToken token)
        {
            ValidateDirectKey(apiKey);
            var request = (HttpWebRequest)WebRequest.Create(endpoint);
            request.Method = body == null ? "GET" : "POST";
            request.AllowAutoRedirect = false;
            request.Timeout = request.ReadWriteTimeout = 120000;
            request.Headers[HttpRequestHeader.Authorization] = "Bearer " + apiKey;
            if (body != null)
            {
                request.ContentType = "application/json";
                request.ContentLength = body.Length;
            }
            token.ThrowIfCancellationRequested();
            using (token.Register(request.Abort))
            {
                try
                {
                    if (body != null) using (Stream output = request.GetRequestStream()) output.Write(body, 0, body.Length);
                    using (var response = (HttpWebResponse)request.GetResponse())
                    using (Stream input = response.GetResponseStream())
                    {
                        if ((int)response.StatusCode >= 300) throw DirectResponseError((int)response.StatusCode);
                        string json = Encoding.UTF8.GetString(ReadBounded(input, MaxDirectResponse));
                        try
                        {
                            var reply = JsonUtil.CreateSerializer().Deserialize<Dictionary<string, object>>(json);
                            if (reply != null) return reply;
                        }
                        catch (ArgumentException) { }
                        catch (InvalidOperationException) { }
                        throw new TranslationFailure("API 未返回有效 JSON");
                    }
                }
                catch (WebException error)
                {
                    token.ThrowIfCancellationRequested();
                    using (var response = error.Response as HttpWebResponse)
                        if (response != null) throw DirectResponseError((int)response.StatusCode);
                    throw;
                }
            }
        }

        private static Dictionary<string, object> Child(Dictionary<string, object> obj, string name)
        {
            object value;
            return obj != null && obj.TryGetValue(name, out value) ? value as Dictionary<string, object> : null;
        }

        private static TranslationTokens ReadTokens(Dictionary<string, object> reply)
        {
            var data = Child(reply, "usage");
            int input = JsonUtil.GetInt(data, "prompt_tokens", -1);
            int output = JsonUtil.GetInt(data, "completion_tokens", -1);
            if (input < 0 || output < 0) return new TranslationTokens { Requests = 1 };
            return new TranslationTokens
            {
                Requests = 1, Reported = 1, Input = input, Output = output,
                Reasoning = Math.Max(0, Math.Min(output, JsonUtil.GetInt(Child(data, "completion_tokens_details"), "reasoning_tokens", 0)))
            };
        }

        private static string TargetLanguage(string config)
        {
            var settings = JsonUtil.CreateSerializer().Deserialize<Dictionary<string, object>>(config);
            string target = JsonUtil.GetStr(Child(settings, "translator"), "target_lang", "CHS");
            switch (target)
            {
                case "CHS": return "Simplified Chinese";
                case "CHT": return "Traditional Chinese";
                case "ENG": return "English";
                case "JPN": return "Japanese";
                case "KOR": return "Korean";
                default: return string.IsNullOrWhiteSpace(target) ? "Simplified Chinese" : target;
            }
        }

        private static object[] ImageContent(string language, byte[] image)
        {
            string prompt = "Translate the visible dialogue and captions on this comic page into " + language + ".\n" +
                "Translate dialogue and captions inside balloons or caption boxes. Group every line in the same balloon into one entry.\n" +
                "Return only compact JSON {\"regions\":[[90,110,240,220,\"translated text\"]]}.\n" +
                "Each array contains [left,top,right,bottom,translation].\n" +
                "Coordinates are integers on a 0 to 1000 scale relative to the entire input image, with (0,0) at the top left.\n" +
                "Each box must tightly cover all original text in that balloon, including vertical lines and punctuation. Skip text outside balloons or caption boxes.\n" +
                "Give one concise translation per box. Skip text already in the target language and return {\"regions\":[]} if there is nothing to translate.";
            return new object[]
            {
                new { type = "text", text = prompt },
                new { type = "image_url", image_url = new { url = "data:image/jpeg;base64," + Convert.ToBase64String(image) } }
            };
        }

        private static string CompletionContent(Dictionary<string, object> reply)
        {
            try
            {
                var choices = reply["choices"] as IList;
                if (choices == null || choices.Count == 0) throw new FormatException();
                var choice = choices[0] as Dictionary<string, object>;
                if (choice == null) throw new FormatException();
                if (JsonUtil.GetStr(choice, "finish_reason", "") == "length")
                    throw new TranslationFailure("模型返回的译文被截断，请调整模型的输出长度");
                var message = choice["message"] as Dictionary<string, object>;
                if (message == null) throw new FormatException();
                if (!string.IsNullOrWhiteSpace(JsonUtil.GetStr(message, "refusal", "")))
                    throw new TranslationFailure("模型拒绝处理此页");
                string content = message["content"] as string;
                if (string.IsNullOrWhiteSpace(content)) throw new FormatException();
                return content.Trim();
            }
            catch (TranslationFailure) { throw; }
            catch (Exception) { throw new TranslationFailure("API 未返回有效译文，请检查模型设置"); }
        }

        private static List<TranslatedRegion> ParseRegions(Dictionary<string, object> reply)
        {
            string content = CompletionContent(reply);
            try
            {
                int start = content.IndexOf('{');
                int end = content.LastIndexOf('}');
                if (start < 0 || end < start) throw new FormatException();
                return DecodeRegions(content.Substring(start, end - start + 1));
            }
            catch (TranslationFailure) { throw; }
            catch (Exception)
            {
                throw new TranslationFailure("API 未返回有效译文，请确认模型支持图片输入");
            }
        }

        private static List<TranslatedRegion> DecodeRegions(string json)
        {
            var result = JsonUtil.CreateSerializer().Deserialize<Dictionary<string, object>>(json);
            var regions = result["regions"] as IList;
            if (regions == null || regions.Count > 150) throw new FormatException();
            var translated = new List<TranslatedRegion>(regions.Count);
            foreach (object item in regions)
            {
                var named = item as Dictionary<string, object>;
                IList entry = named != null ? new[] { named["left"], named["top"], named["right"], named["bottom"], named["text"] } : item as IList;
                if (entry == null || entry.Count != 5) throw new FormatException();
                int left = Coordinate(entry[0]);
                int top = Coordinate(entry[1]);
                int right = Coordinate(entry[2]);
                int bottom = Coordinate(entry[3]);
                string text = (entry[4] as string ?? "").Trim();
                if (right <= left || bottom <= top || text.Length == 0 || text.Length > 2000) throw new FormatException();
                translated.Add(new TranslatedRegion { Bounds = new Rect(left, top, right - left, bottom - top), Text = text });
            }
            return translated;
        }

        private static int Coordinate(object coordinate)
        {
            int value;
            if (!int.TryParse(Convert.ToString(coordinate, CultureInfo.InvariantCulture),
                NumberStyles.Integer, CultureInfo.InvariantCulture, out value) || value < 0 || value > 1000)
                throw new FormatException();
            return value;
        }

        private static BitmapSource RenderDirect(BitmapSource original, List<TranslatedRegion> regions)
        {
            const int analysisEdge = 1250;
            double scale = Math.Min(1, (double)analysisEdge / Math.Max(original.PixelWidth, original.PixelHeight));
            BitmapSource analysis = scale == 1 ? original : new TransformedBitmap(original, new ScaleTransform(
                Math.Max(scale, 1.0 / original.PixelWidth), Math.Max(scale, 1.0 / original.PixelHeight)));
            var finder = new LightBalloonFinder(analysis.PixelWidth, analysis.PixelHeight, PixelBuffer(analysis));
            var balloons = new Dictionary<int, Tuple<LightBalloonFinder.Balloon, List<string>>>();
            foreach (TranslatedRegion region in regions)
            {
                var balloon = finder.Find(region.Bounds);
                if (balloon == null) continue;
                Tuple<LightBalloonFinder.Balloon, List<string>> existing;
                if (!balloons.TryGetValue(balloon.Id, out existing))
                    balloons[balloon.Id] = existing = Tuple.Create(balloon, new List<string>());
                if (!existing.Item2.Contains(region.Text)) existing.Item2.Add(region.Text);
            }
            if (balloons.Count == 0) return original;

            int fullWidth = original.PixelWidth, fullHeight = original.PixelHeight;
            double sx = (double)fullWidth / analysis.PixelWidth, sy = (double)fullHeight / analysis.PixelHeight;
            int[] cleared = null;
            var labels = new List<Tuple<Rect, FormattedText, Point>>();
            var typeface = new Typeface("Segoe UI");
            foreach (var placement in balloons.Values)
            {
                var balloon = placement.Item1;
                var box = balloon.TextArea;
                double left = Math.Ceiling(box.X * sx), top = Math.Ceiling(box.Y * sy);
                double right = Math.Floor((box.X + box.Width) * sx), bottom = Math.Floor((box.Y + box.Height) * sy);
                var area = new Rect(left, top, right - left, bottom - top);
                double padding = Math.Min(8, Math.Min(area.Width, area.Height) * 0.08);
                double width = Math.Max(1, area.Width - padding * 2), height = Math.Max(1, area.Height - padding * 2);
                double low = Math.Max(4, Math.Max(fullWidth, fullHeight) / 500.0);
                double high = Math.Max(low, Math.Min(height, fullWidth / 24.0));
                var ink = new FormattedText(string.Join("\n", placement.Item2), CultureInfo.CurrentUICulture,
                    FlowDirection.LeftToRight, typeface, low, Brushes.Black, 1.0)
                    { MaxTextWidth = width, TextAlignment = TextAlignment.Center, Trimming = TextTrimming.None };
                if (ink.Height > height || ink.WidthIncludingTrailingWhitespace > width) continue;
                for (int attempt = 0; attempt < 9; attempt++)
                {
                    double middle = (low + high) / 2;
                    ink.SetFontSize(middle);
                    if (ink.Height <= height && ink.WidthIncludingTrailingWhitespace <= width) low = middle;
                    else high = middle;
                }
                ink.SetFontSize(low);
                if (cleared == null) cleared = PixelBuffer(original);
                EraseBalloonInk(cleared, fullWidth, fullHeight, analysis.PixelWidth, analysis.PixelHeight, balloon, sx, sy);
                labels.Add(Tuple.Create(area, ink, new Point(area.Left + padding, area.Top + (area.Height - ink.Height) / 2)));
            }
            if (cleared == null) return original;

            var visual = new DrawingVisual();
            var cleaned = BitmapSource.Create(fullWidth, fullHeight, 96, 96, PixelFormats.Bgra32, null, cleared, fullWidth * 4);
            using (DrawingContext drawing = visual.RenderOpen())
            {
                drawing.DrawImage(cleaned, new Rect(0, 0, fullWidth, fullHeight));
                foreach (var label in labels)
                {
                    drawing.PushClip(new RectangleGeometry(label.Item1));
                    drawing.DrawText(label.Item2, label.Item3);
                    drawing.Pop();
                }
            }
            var result = new RenderTargetBitmap(original.PixelWidth, original.PixelHeight, 96, 96, PixelFormats.Pbgra32);
            result.Render(visual);
            result.Freeze();
            return result;
        }

        private static int[] PixelBuffer(BitmapSource image)
        {
            if (image.Format != PixelFormats.Bgra32) image = new FormatConvertedBitmap(image, PixelFormats.Bgra32, null, 0);
            var pixels = new int[checked(image.PixelWidth * image.PixelHeight)];
            image.CopyPixels(pixels, image.PixelWidth * 4, 0);
            return pixels;
        }

        private static void EraseBalloonInk(int[] pixels, int width, int height, int analysisWidth, int analysisHeight,
            LightBalloonFinder.Balloon balloon, double sx, double sy)
        {
            Int32Rect shape = balloon.Bounds;
            int threshold = Math.Min(247, LightBalloonFinder.Brightness(balloon.Background) - 8);
            int left = (int)Math.Ceiling(shape.X * sx), right = Math.Min(width, (int)Math.Ceiling((shape.X + shape.Width) * sx));
            int top = (int)Math.Ceiling(shape.Y * sy), bottom = Math.Min(height, (int)Math.Ceiling((shape.Y + shape.Height) * sy));
            var ink = new List<int>();
            for (int y = top; y < bottom; y++)
            {
                int row = (y * analysisHeight / height - shape.Y) * shape.Width;
                for (int x = left; x < right; x++)
                {
                    int index = y * width + x;
                    int mask = row + x * analysisWidth / width - shape.X;
                    if (balloon.Safe[mask] && LightBalloonFinder.Brightness(pixels[index]) < threshold)
                        ink.Add(index);
                }
            }
            // Remove the light antialiasing halo immediately around each original stroke.
            const int fringe = 2;
            int color = balloon.Background & 0xffffff;
            foreach (int stroke in ink)
            {
                int centerX = stroke % width, centerY = stroke / width;
                for (int y = Math.Max(top, centerY - fringe); y <= Math.Min(bottom - 1, centerY + fringe); y++)
                {
                    int row = (y * analysisHeight / height - shape.Y) * shape.Width;
                    for (int x = Math.Max(left, centerX - fringe); x <= Math.Min(right - 1, centerX + fringe); x++)
                    {
                        int mask = row + x * analysisWidth / width - shape.X;
                        if (!balloon.Safe[mask]) continue;
                        int index = y * width + x;
                        pixels[index] = (pixels[index] & unchecked((int)0xff000000)) | color;
                    }
                }
            }
        }

        private static Exception DirectResponseError(int status)
        {
            switch (status)
            {
                case 401: return new TranslationFailure("API Key 无效或已过期（HTTP 401）");
                case 404:
                case 405: return new TranslationFailure("API 地址或模型 ID 无效（HTTP " + status + "）");
                case 400:
                case 415:
                case 422: return new TranslationFailure("API 拒绝请求（HTTP " + status + "），请检查图片输入和思考强度");
                default: return ResponseError(status);
            }
        }
    }
}
